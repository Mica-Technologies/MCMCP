package com.micatechnologies.minecraft.mcmcp.protocol;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Owns the live {@link McpSession}s for one endpoint, and their expiry.
 *
 * <p>Session ids are 128 bits of {@link SecureRandom}, hex-encoded. That is not paranoia about
 * guessing so much as about the alternative: a sequential or timestamp-derived id is trivially
 * enumerable, and possession of a session id is enough to keep issuing requests on an already
 * authenticated session. The MCP spec requires them to be globally unique and cryptographically
 * secure for exactly this reason.
 *
 * <p>Sessions expire on inactivity rather than being kept until an explicit {@code DELETE}. Clients
 * crash, lose network, and get killed by their host process, and none of those send a shutdown —
 * without a sweep, a long-running dedicated server accumulates sessions (and their queued
 * notifications) until restart.
 */
public class McpSessionManager {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final long idleTimeoutMillis;
    private final int maxSessions;

    /**
     * How long a session must sit idle before a full endpoint may evict it.
     *
     * <p>Long enough that a client between two calls of one task keeps its session; short enough
     * that a script killed a minute ago does not hold a slot for half an hour.
     */
    static final long EVICTION_GRACE_MILLIS = 60_000L;

    public McpSessionManager(long idleTimeoutMillis, int maxSessions) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxSessions = maxSessions;
    }

    /** Creates a direct-client session. See {@link #create(boolean)}. */
    public McpSession create() {
        return create(false);
    }

    /**
     * Creates a session and returns it, evicting an abandoned one if the endpoint is full.
     *
     * <h2>Why a full endpoint evicts rather than refuses</h2>
     *
     * Scripts open a session per run, and a script that is killed — Ctrl-C, a timeout, a stopped
     * background task — never sends {@code DELETE}. Refusing at the cap locked every new client out
     * for up to {@code sessionIdleTimeoutSeconds} after a day of short runs, while every slot
     * belonged to a process that had already exited and could never come back for it.
     *
     * <p>So the longest-idle session goes, provided it has been idle at least
     * {@link #EVICTION_GRACE_MILLIS}, has no request still running, and is not an orchestrator link.
     * A live client that is evicted gets a 404 on its next request, which the MCP spec tells it to
     * answer by initializing again. Only when nothing qualifies — a genuine burst of active clients,
     * which is what the cap exists to bound — is the new session refused, and the refusal says when
     * a slot will free up and what to change instead of waiting.
     *
     * @param orchestratorLink whether this is an orchestrator link's session, which is never evicted
     * @throws JsonRpcException if the endpoint is full and no session can be evicted
     */
    public McpSession create(boolean orchestratorLink) {
        return create(orchestratorLink, System.currentTimeMillis());
    }

    synchronized McpSession create(boolean orchestratorLink, long nowMillis) {
        sweepExpired(nowMillis);
        if (sessions.size() >= maxSessions) {
            McpSession evictable = longestIdleEvictable(nowMillis);
            if (evictable == null) {
                throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR, describeFull(nowMillis));
            }
            sessions.remove(evictable.getId());
            evictable.close();
            Mcmcp.LOGGER.info("MCMCP evicted session " + evictable.getId() + " (client: "
                + evictable.describeClient() + ", idle "
                + (nowMillis - evictable.getLastActivityMillis()) / 1000L + "s) to make room for a new "
                + "one; the endpoint was at its maximum of " + maxSessions + " sessions");
        }

        byte[] entropy = new byte[16];
        RANDOM.nextBytes(entropy);
        StringBuilder id = new StringBuilder(32);
        for (byte b : entropy) {
            id.append(Character.forDigit((b >> 4) & 0xF, 16));
            id.append(Character.forDigit(b & 0xF, 16));
        }

        McpSession session = new McpSession(id.toString(), nowMillis, orchestratorLink);
        sessions.put(session.getId(), session);
        return session;
    }

    @Nullable
    private McpSession longestIdleEvictable(long nowMillis) {
        McpSession oldest = null;
        for (McpSession session : sessions.values()) {
            if (session.isOrchestratorLink() || session.hasInflightRequests()
                || nowMillis - session.getLastActivityMillis() < EVICTION_GRACE_MILLIS) {
                continue;
            }
            if (oldest == null || session.getLastActivityMillis() < oldest.getLastActivityMillis()) {
                oldest = session;
            }
        }
        return oldest;
    }

    /** The refusal for a full endpoint: when a slot frees, and what to change instead of waiting. */
    private String describeFull(long nowMillis) {
        long soonestMillis = Long.MAX_VALUE;
        for (McpSession session : sessions.values()) {
            if (session.isOrchestratorLink() || session.hasInflightRequests()) {
                continue;
            }
            long idle = nowMillis - session.getLastActivityMillis();
            soonestMillis = Math.min(soonestMillis, Math.max(0L, EVICTION_GRACE_MILLIS - idle));
        }
        String when = soonestMillis == Long.MAX_VALUE
            ? "Every session has a request still running, so a slot frees when one of them finishes."
            : "A slot frees in about " + Math.max(1L, (soonestMillis + 999L) / 1000L) + "s, when the "
                + "least recently used session has been idle for " + EVICTION_GRACE_MILLIS / 1000L + "s.";
        return "This MCMCP endpoint already has its maximum of " + maxSessions + " sessions, all used "
            + "within the last " + EVICTION_GRACE_MILLIS / 1000L + "s. " + when + " Close sessions you "
            + "are finished with (HTTP DELETE with their Mcp-Session-Id), or raise limits.maxSessions "
            + "in the MCMCP config and run '/mcmcp restart'.";
    }

    @Nullable
    public McpSession get(@Nullable String id) {
        if (id == null) {
            return null;
        }
        McpSession session = sessions.get(id);
        if (session != null) {
            session.touch(System.currentTimeMillis());
        }
        return session;
    }

    public void remove(@Nullable String id) {
        if (id == null) {
            return;
        }
        McpSession session = sessions.remove(id);
        if (session != null) {
            session.close();
        }
    }

    public Collection<McpSession> all() {
        return new ArrayList<>(sessions.values());
    }

    public int count() {
        return sessions.size();
    }

    /**
     * Broadcasts a notification to every session, or to only those subscribed to {@code uri}.
     *
     * <p>Called from the game thread for resource updates, so it does nothing but append to
     * per-session queues. Any actual I/O happens later on the SSE writer thread.
     */
    public void broadcast(JsonObject notification) {
        for (McpSession session : sessions.values()) {
            session.enqueue(notification);
        }
    }

    public void broadcastToSubscribers(String uri, JsonObject notification) {
        for (McpSession session : sessions.values()) {
            if (session.isSubscribedTo(uri)) {
                session.enqueue(notification);
            }
        }
    }

    /** Drops sessions idle for longer than the configured timeout. */
    public void sweepExpired() {
        sweepExpired(System.currentTimeMillis());
    }

    void sweepExpired(long now) {
        Iterator<Map.Entry<String, McpSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, McpSession> entry = iterator.next();
            McpSession session = entry.getValue();
            if (now - session.getLastActivityMillis() > idleTimeoutMillis) {
                iterator.remove();
                session.close();
                Mcmcp.LOGGER.info("MCMCP dropped idle session " + session.getId()
                    + " (client: " + session.describeClient() + ")");
            }
        }
    }

    /** Closes every session; called when an endpoint stops. */
    public void closeAll() {
        List<McpSession> closing = new ArrayList<>(sessions.values());
        sessions.clear();
        for (McpSession session : closing) {
            session.close();
        }
    }
}
