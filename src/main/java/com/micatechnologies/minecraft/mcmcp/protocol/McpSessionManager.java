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

    public McpSessionManager(long idleTimeoutMillis, int maxSessions) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxSessions = maxSessions;
    }

    /**
     * Creates a session and returns it.
     *
     * @throws JsonRpcException if the endpoint is already at {@code maxSessions}. Refusing is the
     *                          right answer rather than evicting the oldest: each session is a live
     *                          client that would silently stop working, and the cap exists to stop
     *                          an unauthenticated flood from exhausting memory inside the game
     *                          process.
     */
    public McpSession create() {
        sweepExpired();
        if (sessions.size() >= maxSessions) {
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR,
                "This MCMCP endpoint already has its maximum of " + maxSessions + " sessions");
        }

        byte[] entropy = new byte[16];
        RANDOM.nextBytes(entropy);
        StringBuilder id = new StringBuilder(32);
        for (byte b : entropy) {
            id.append(Character.forDigit((b >> 4) & 0xF, 16));
            id.append(Character.forDigit(b & 0xF, 16));
        }

        McpSession session = new McpSession(id.toString(), System.currentTimeMillis());
        sessions.put(session.getId(), session);
        return session;
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
        long now = System.currentTimeMillis();
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
