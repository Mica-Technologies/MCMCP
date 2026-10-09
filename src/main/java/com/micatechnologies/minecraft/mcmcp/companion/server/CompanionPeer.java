package com.micatechnologies.minecraft.mcmcp.companion.server;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionOutbox;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocol;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionReassembler;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * One connected player's companion state on the server: their frames being reassembled, the MCP
 * sessions their client has open through the companion, and the replies waiting to go back.
 *
 * <p>The player is identified by the connection the frames arrived on, never by anything inside
 * them. A client is untrusted code, and a request that names its own sender could name anyone.
 */
final class CompanionPeer {

    final UUID playerId;
    final String playerName;

    /** Fed from the netty thread only. */
    final CompanionReassembler reassembler;

    final CompanionOutbox outbox = new CompanionOutbox(CompanionProtocol.S2C_FRAME);

    /** Sessions by the client's key, oldest first so the oldest is the one evicted. */
    private final Map<String, McpSession> sessions = new LinkedHashMap<>();

    private volatile boolean granted;

    /** Set when the peer broke the protocol; its frames are ignored until it reconnects. */
    private volatile boolean broken;

    CompanionPeer(UUID playerId, String playerName, int maxMessageBytes, int maxOpenMessages) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.reassembler = new CompanionReassembler(maxMessageBytes, maxOpenMessages);
    }

    boolean isGranted() {
        return granted && !broken;
    }

    void grant() {
        granted = true;
    }

    boolean isBroken() {
        return broken;
    }

    void markBroken() {
        broken = true;
        granted = false;
    }

    /**
     * The session for a client key, created on first use. Opening one past {@code max} closes the
     * oldest, which cancels anything it had running.
     */
    synchronized McpSession session(String key, int max) {
        McpSession session = sessions.get(key);
        if (session != null) {
            return session;
        }
        while (sessions.size() >= max) {
            String oldest = sessions.keySet().iterator().next();
            sessions.remove(oldest).close();
        }
        session = new McpSession(playerId + "/" + key, System.currentTimeMillis());
        sessions.put(key, session);
        return session;
    }

    @Nullable
    synchronized McpSession existingSession(String key) {
        return sessions.get(key);
    }

    synchronized void closeSession(String key) {
        McpSession session = sessions.remove(key);
        if (session != null) {
            session.close();
        }
    }

    /** Every session, with its key, for draining their notification queues. */
    synchronized List<Map.Entry<String, McpSession>> sessionEntries() {
        return new ArrayList<>(sessions.entrySet());
    }

    synchronized Collection<McpSession> sessions() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    /** Ends everything: running calls are cancelled, queued replies dropped. */
    synchronized void close() {
        for (McpSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        outbox.clear();
        granted = false;
    }

    /** The envelope a message to this peer's client travels in. */
    static JsonObject envelope(String sessionKey, JsonObject message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(CompanionProtocol.FIELD_SESSION, sessionKey);
        envelope.add(CompanionProtocol.FIELD_MESSAGE, message);
        return envelope;
    }
}
