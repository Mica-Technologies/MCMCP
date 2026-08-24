package com.micatechnologies.minecraft.mcmcp.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpIdentity;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.link.LinkBackoff;
import com.micatechnologies.minecraft.mcmcp.link.LinkFraming;
import com.micatechnologies.minecraft.mcmcp.link.LinkHandshake;
import com.micatechnologies.minecraft.mcmcp.link.LinkProtocol;
import com.micatechnologies.minecraft.mcmcp.link.LinkProtocolException;
import com.micatechnologies.minecraft.mcmcp.link.LinkSettings;
import com.micatechnologies.minecraft.mcmcp.link.LinkState;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSessionManager;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * The outbound half of MCMCP's transport pair: this instance dials an orchestrator instead of
 * waiting to be dialled.
 *
 * <h2>Why outward</h2>
 *
 * {@link HttpMcpTransport} listens, which works beautifully for one game and turns into a chore for
 * three. Every instance needs a port nobody else took, whoever connects has to know which instance
 * ended up on which port, and a second client on a default config simply fails to bind and runs with
 * no MCP at all. Dialling out deletes that whole class of problem rather than managing it: nothing
 * here listens, so nothing can collide, and presence is the socket — an orchestrator knows an
 * instance is alive because the connection is open, with no registry file, heartbeat or stale entry
 * anywhere.
 *
 * <p>It also puts a human at the moment of connection, which is what makes trust-on-first-use worth
 * anything: the orchestrator can ask "this game wants to connect — is that you?" while the person is
 * sitting there.
 *
 * <h2>Threads</h2>
 *
 * Three kinds, and they are kept apart deliberately:
 *
 * <ul>
 *   <li><b>The connector</b> — one scheduled thread that dials, shakes hands, then <em>blocks</em> in
 *       the read loop for the life of the connection. When the loop returns it schedules the next
 *       attempt after a backoff. One thread doing connect-then-read means there is never a window
 *       where two connections exist at once.</li>
 *   <li><b>The writer</b> — one daemon thread per connection, draining the session's outbound queue.
 *       Separate because the reader blocks; a single thread could not do both without polling.</li>
 *   <li><b>The workers</b> — a small fixed pool running tool calls. The read loop must not run them
 *       itself: {@code client_wait} blocks until its condition comes true, and a reader that stops
 *       reading cannot receive the cancellation that would end it.</li>
 * </ul>
 *
 * <p>Every one of them is a daemon. A stuck link must never be the reason a game will not exit.
 */
public class ReverseTransport implements McpTransport {

    /** How long the writer waits for a queued message before looping to re-check liveness. */
    private static final long WRITER_POLL_MILLIS = 5_000L;

    private final LinkSettings settings;
    private final McmcpIdentity identity;
    private final McmcpSide side;
    private final McpDispatcher dispatcher;
    private final McpSessionManager sessions;
    private final HelloDetails helloDetails;

    private volatile boolean running;
    private volatile LinkState state = LinkState.DISABLED;
    private volatile String stateDetail = "";
    private final LinkBackoff backoff;

    /**
     * Whether the "nothing is listening" message has already been logged for this outage.
     *
     * <p>The overwhelmingly common configuration is a game with no orchestrator installed at all.
     * That must cost one INFO line and then silence — a reconnect notice every thirty seconds for a
     * six-hour session would train people to ignore MCMCP's log output entirely, which is the last
     * thing wanted from a component whose failures are otherwise invisible.
     */
    private volatile boolean loggedOffline;

    @Nullable
    private volatile String assignedName;

    @Nullable
    private volatile Socket socket;

    @Nullable
    private ScheduledExecutorService connector;

    @Nullable
    private ExecutorService workers;

    public ReverseTransport(LinkSettings settings,
                            McmcpIdentity identity,
                            McmcpSide side,
                            McpDispatcher dispatcher,
                            McpSessionManager sessions,
                            HelloDetails helloDetails) {
        this.settings = settings;
        this.identity = identity;
        this.side = side;
        this.dispatcher = dispatcher;
        this.sessions = sessions;
        this.helloDetails = helloDetails;
        this.backoff = new LinkBackoff(settings.getBackoffInitialMillis(), settings.getBackoffMaxMillis());
    }

    // ------------------------------------------------------------------
    // McpTransport
    // ------------------------------------------------------------------

    @Override
    public String describeKind() {
        return "orchestrator link";
    }

    @Override
    public String describeTarget() {
        return settings.describeTarget();
    }

    /**
     * Whether the link can carry traffic <em>right now</em>.
     *
     * <p>Not "the machinery is alive". A link that is retrying because no orchestrator is running is
     * started and healthy and completely unable to deliver a tool call, and reporting that as
     * running would make {@code /mcmcp status} claim an endpoint is reachable when nothing can reach
     * it. {@link #getState()} is what distinguishes retrying from stopped.
     */
    @Override
    public boolean isRunning() {
        return state.isConnected();
    }

    public LinkState getState() {
        return state;
    }

    /** A sentence for {@code /mcmcp link}: what the link is doing and, when it matters, why. */
    public String describeState() {
        String detail = stateDetail;
        return detail.isEmpty() ? state.description() : state.description() + " — " + detail;
    }

    /** The label a human gave this instance in the orchestrator, if it sent one back. */
    @Nullable
    public String getAssignedName() {
        return assignedName;
    }

    /**
     * Begins trying to reach an orchestrator.
     *
     * <p>Does not throw and does not wait for a connection. "Started" here means the attempt loop is
     * running, because the normal case at game launch is that no orchestrator is up yet — blocking
     * startup on one, or failing when it is absent, would make an optional component mandatory.
     */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loggedOffline = false;
        backoff.reset();
        setState(LinkState.CONNECTING, "");

        if (settings.isRemote()) {
            Mcmcp.LOGGER.warn("MCMCP's orchestrator link is configured to dial "
                + settings.describeTarget() + ", which is not this machine. The link is not encrypted "
                + "and carries this instance's secret followed by full control of this game. Only "
                + "loopback is supported; forward the port over SSH if you need it from elsewhere.");
        }

        connector = Executors.newSingleThreadScheduledExecutor(daemonFactory("link", new AtomicInteger(1)));
        workers = Executors.newFixedThreadPool(settings.getWorkerThreads(),
            daemonFactory("link-worker", new AtomicInteger(1)));

        scheduleAttempt(0L);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        setState(LinkState.SHUT_DOWN, "");

        // Closing the socket is what unblocks the reader; interrupting it would not, because a
        // blocking socket read does not respond to interruption.
        closeSocket();

        ScheduledExecutorService connectorPool = connector;
        connector = null;
        if (connectorPool != null) {
            connectorPool.shutdownNow();
        }
        ExecutorService workerPool = workers;
        workers = null;
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    private void scheduleAttempt(long delayMillis) {
        ScheduledExecutorService pool = connector;
        if (!running || pool == null) {
            return;
        }
        try {
            pool.schedule(new Runnable() {
                @Override
                public void run() {
                    attemptConnection();
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException e) {
            // stop() raced us. Nothing to do: the link is going away.
        }
    }

    /**
     * One full connection attempt: dial, shake hands, serve until the link drops.
     *
     * <p>Returns only when the connection is over, and always schedules the next attempt on the way
     * out unless the rejection was one that retrying cannot clear.
     */
    private void attemptConnection() {
        if (!running) {
            return;
        }

        Socket connected = null;
        McpSession session = null;
        boolean retry = true;

        // Whether this attempt got as far as a completed handshake. It decides what a subsequent
        // IOException means: before it, the orchestrator is simply not there, which is the ordinary
        // state of the world and gets one quiet line. After it, somebody closed a link that was
        // working, and saying "found no orchestrator listening" would describe the wrong event.
        boolean reachedConnected = false;

        try {
            setState(LinkState.CONNECTING, "");
            connected = new Socket();
            connected.connect(new InetSocketAddress(settings.getHost(), settings.getPort()),
                (int) settings.getConnectTimeoutMillis());
            // Frames here are small and latency matters more than packet count: a tool call and its
            // reply are one small write each, and Nagle would sit on them waiting for company.
            connected.setTcpNoDelay(true);

            InputStream in = new BufferedInputStream(connected.getInputStream());
            OutputStream out = connected.getOutputStream();

            LinkFraming.writeFrame(out, LinkHandshake.hello(identity, side.id(),
                helloDetails.getGameDirectory(), helloDetails.getModVersion(),
                helloDetails.getMinecraftVersion(), helloDetails.getEndpointUrl()));

            LinkHandshake.Result result = LinkHandshake.parseResponse(LinkFraming.readFrame(in));
            if (!result.isAccepted()) {
                retry = handleRejection(result);
                return;
            }

            session = sessions.create();
            this.socket = connected;
            this.assignedName = result.getAssignedName();
            backoff.reset();
            loggedOffline = false;
            reachedConnected = true;
            setState(LinkState.CONNECTED, result.describe());
            Mcmcp.LOGGER.info("MCMCP " + side.id() + " endpoint " + result.describe() + " at "
                + settings.describeTarget() + " as " + identity.getInstanceId()
                + (result.getAssignedName() == null ? "" : " (\"" + result.getAssignedName() + "\")"));

            startWriter(session, out, connected);
            readLoop(in, out, session, connected);
        }
        catch (IOException e) {
            if (reachedConnected) {
                noteLinkClosed(e.getMessage());
            }
            else {
                noteOffline(e.getMessage());
            }
        }
        catch (LinkProtocolException e) {
            // A peer that speaks something other than this protocol. Worth saying every time —
            // unlike an absent orchestrator, this one is not the normal state of the world.
            Mcmcp.LOGGER.warn("MCMCP orchestrator link to " + settings.describeTarget()
                + " failed: " + e.getMessage());
            setState(LinkState.RETRYING, e.getMessage());
        }
        catch (JsonRpcException e) {
            // sessions.create() refusing because the endpoint is at maxSessions.
            Mcmcp.LOGGER.warn("MCMCP orchestrator link could not open a session: " + e.getMessage());
            setState(LinkState.RETRYING, e.getMessage());
        }
        catch (Throwable t) {
            // The connector thread must survive anything. Losing it means a link that never
            // reconnects and never says why.
            Mcmcp.LOGGER.error("MCMCP orchestrator link attempt failed unexpectedly", t);
            setState(LinkState.RETRYING, String.valueOf(t.getMessage()));
        }
        finally {
            if (session != null) {
                sessions.remove(session.getId());
            }
            closeQuietly(connected);
            if (this.socket == connected) {
                this.socket = null;
            }
            this.assignedName = null;

            if (running && retry) {
                if (state.isConnected()) {
                    // The read loop returned without throwing: a clean close from the far end.
                    noteLinkClosed(null);
                }
                scheduleAttempt(backoff.nextDelayMillis());
            }
        }
    }

    /**
     * Applies a rejection.
     *
     * @return whether to keep trying
     */
    private boolean handleRejection(LinkHandshake.Result result) {
        if (LinkProtocol.REASON_PENDING_APPROVAL.equals(result.getReason())) {
            // Not a failure. Somebody has an approval dialog on screen; keep knocking, quietly, and
            // do not let the backoff grow to half a minute while they reach for the mouse.
            setState(LinkState.WAITING_FOR_APPROVAL, "");
            if (!loggedOffline) {
                loggedOffline = true;
                Mcmcp.LOGGER.info("MCMCP is waiting for this instance (" + identity.getInstanceId()
                    + ") to be approved in the orchestrator at " + settings.describeTarget() + ".");
            }
            backoff.reset();
            return true;
        }

        if (!result.isRetryable()) {
            Mcmcp.LOGGER.error("MCMCP orchestrator link refused: " + result.describe());
            setState(LinkState.STOPPED, result.describe());
            return false;
        }

        Mcmcp.LOGGER.warn("MCMCP orchestrator link refused: " + result.describe());
        setState(LinkState.RETRYING, result.describe());
        return true;
    }

    /**
     * A link that was working has ended.
     *
     * <p>Kept apart from {@link #noteOffline} because they are different events with different
     * answers: this one means the orchestrator was closed, crashed, or revoked us mid-session, and
     * the person reading the log needs to know the link <em>was</em> up. It always logs, because
     * unlike an absent orchestrator this is not the resting state of anything.
     */
    private void noteLinkClosed(@Nullable String message) {
        setState(LinkState.RETRYING, message == null ? "" : message);
        // Reset so that if the orchestrator stays down, the follow-up "still unavailable" lines drop
        // to debug rather than repeating this one every backoff.
        loggedOffline = true;
        Mcmcp.LOGGER.info("MCMCP orchestrator link closed"
            + (message == null || message.isEmpty() ? "" : " (" + message + ")") + "; reconnecting.");
    }

    private void noteOffline(@Nullable String message) {
        setState(LinkState.RETRYING, message == null ? "" : message);
        if (loggedOffline) {
            Mcmcp.LOGGER.debug("MCMCP orchestrator link to " + settings.describeTarget()
                + " still unavailable: " + message);
            return;
        }
        loggedOffline = true;
        Mcmcp.LOGGER.info("MCMCP found no orchestrator listening on " + settings.describeTarget()
            + "; it will keep trying in the background. This is normal if you are not running one — "
            + "the HTTP endpoint is unaffected. Set orchestrator.enableOrchestratorLink=false to stop "
            + "trying altogether.");
    }

    // ------------------------------------------------------------------
    // Serving
    // ------------------------------------------------------------------

    /**
     * Reads frames until the link ends.
     *
     * <p>Requests are handed to the worker pool rather than run here. A tool call can take seconds —
     * {@code client_wait} can take as long as its condition does — and a reader that has stopped
     * reading cannot receive the {@code notifications/cancelled} that would cut it short.
     */
    private void readLoop(InputStream in, OutputStream out, McpSession session, Socket connected)
        throws IOException, LinkProtocolException {

        while (running && !connected.isClosed()) {
            JsonObject frame = LinkFraming.readFrame(in);
            if (frame == null) {
                return;
            }
            session.touch(System.currentTimeMillis());

            if (LinkFraming.isControlFrame(frame)) {
                // Nothing sends a post-handshake control frame yet. Ignoring an unknown one rather
                // than dropping the link is what lets a newer orchestrator talk to an older mod.
                Mcmcp.LOGGER.debug("MCMCP orchestrator link ignored a control frame of type "
                    + frame.get(LinkProtocol.FIELD_TYPE));
                continue;
            }
            if (!LinkFraming.isMcpMessage(frame)) {
                Mcmcp.LOGGER.debug("MCMCP orchestrator link ignored a frame that is neither MCP nor "
                    + "a control frame");
                continue;
            }

            submit(frame, out, session);
        }
    }

    private void submit(final JsonObject message, final OutputStream out, final McpSession session) {
        ExecutorService pool = workers;
        if (pool == null) {
            return;
        }
        try {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    handleMessage(message, out, session);
                }
            });
        }
        catch (RejectedExecutionException e) {
            // Shutting down. The orchestrator sees the link close, which is a better signal than a
            // half-answered request.
        }
    }

    private void handleMessage(JsonObject message, OutputStream out, McpSession session) {
        JsonElement id = JsonRpc.getId(message);
        try {
            JsonObject response = dispatcher.dispatch(session, message);
            if (response != null) {
                LinkFraming.writeFrame(out, response);
            }
        }
        catch (IOException e) {
            // The link died under us. The reader will notice and reconnect; there is nowhere left
            // to report this.
            Mcmcp.LOGGER.debug("MCMCP orchestrator link write failed: " + e.getMessage());
        }
        catch (Throwable t) {
            // The dispatcher converts tool failures into results itself, so reaching here means a
            // genuine bug. Answering with an error beats leaving the orchestrator — and the model
            // behind it — waiting forever on an id that will never come back.
            Mcmcp.LOGGER.error("MCMCP orchestrator link failed to handle a message", t);
            if (id != null) {
                try {
                    LinkFraming.writeFrame(out, JsonRpc.error(id, JsonRpcException.INTERNAL_ERROR,
                        "MCMCP failed to handle this request: " + t));
                }
                catch (IOException ignored) {
                    // Nothing further to try.
                }
            }
        }
    }

    /**
     * Starts the thread that pushes queued server-to-client messages out.
     *
     * <p>This is the link's equivalent of the SSE stream, and it exists for the same traffic:
     * resource-update notifications, catalogue changes, and log messages. The poll timeout is what
     * lets the thread notice the connection has gone even when nothing is queued.
     */
    private void startWriter(final McpSession session, final OutputStream out, final Socket connected) {
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running && !connected.isClosed()) {
                        JsonObject message = session.pollOutbound(WRITER_POLL_MILLIS);
                        if (message != null) {
                            LinkFraming.writeFrame(out, message);
                        }
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (IOException e) {
                    Mcmcp.LOGGER.debug("MCMCP orchestrator link writer stopped: " + e.getMessage());
                }
            }
        }, "MCMCP-" + side.id() + "-link-writer");
        writer.setDaemon(true);
        writer.start();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private void setState(LinkState next, @Nullable String detail) {
        this.state = next;
        this.stateDetail = detail == null ? "" : detail;
    }

    private void closeSocket() {
        closeQuietly(socket);
        socket = null;
    }

    private static void closeQuietly(@Nullable Socket target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        }
        catch (IOException ignored) {
            // Closing a socket that is already broken is the normal path here.
        }
    }

    private ThreadFactory daemonFactory(final String role, final AtomicInteger counter) {
        final String prefix = "MCMCP-" + side.id() + "-" + role + "-";
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /**
     * The parts of a {@code hello} frame that come from the running game.
     *
     * <p>An interface rather than fixed strings because the HTTP endpoint's URL is not known until it
     * has bound, and on a dedicated server it is never known at all. Resolving these when the
     * handshake is actually sent also means a reconnect after a config reload reports the current
     * values rather than the ones captured at launch.
     */
    public interface HelloDetails {

        @Nullable
        String getGameDirectory();

        String getModVersion();

        String getMinecraftVersion();

        @Nullable
        String getEndpointUrl();
    }
}
