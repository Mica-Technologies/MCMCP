package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import javax.annotation.Nullable;

/**
 * Which agent made a call (#94).
 *
 * <p>Through the orchestrator, every agent's calls reach the game over the one link session, so the
 * session cannot tell them apart. The orchestrator names the agent connection a call came from in
 * {@code _meta["mcmcp/caller"]}, and that is used when present. A client talking to the endpoint
 * directly has a session of its own, and that names it.
 */
public final class CallerId {

    /** The {@code _meta} key the orchestrator puts the caller under; {@code caller.rs} writes it. */
    public static final String META_KEY = "mcmcp/caller";

    private final String id;
    private final String label;
    /** The orchestrator's stamp this came from, to pass on to the server over the companion; or null. */
    @Nullable
    private final JsonObject stamp;

    private CallerId(String id, String label) {
        this(id, label, null);
    }

    private CallerId(String id, String label, @Nullable JsonObject stamp) {
        this.id = id;
        this.label = label;
        this.stamp = stamp;
    }

    /** Who sent a {@code tools/call} with these params on this session. */
    public static CallerId of(McpSession session, @Nullable JsonObject params) {
        JsonObject meta = params == null ? null : Json.getObject(params, "_meta");
        JsonObject stamped = meta == null ? null : Json.getObject(meta, META_KEY);
        if (session.isOrchestratorLink() && stamped != null && Json.getString(stamped, "id", null) != null) {
            String connection = Json.getString(stamped, "id", "");
            String name = Json.getString(stamped, "name", null);
            return new CallerId("orchestrator:" + connection,
                (name == null || name.isEmpty() ? "agent" : name) + " (" + connection + ")", stamped);
        }
        if (!session.isOrchestratorLink() && session.getPrincipal().isCompanion() && stamped != null
            && Json.getString(stamped, "id", null) != null) {
            // Through the companion, every agent the orchestrator routes to one player arrives on one
            // session; the client forwards the orchestrator's stamp unchanged. It names which agent, so
            // one agent's 'release all' is its own (#142). It cannot widen anything: access is the
            // player's, and the player's cap still counts all their agents together.
            String connection = Json.getString(stamped, "id", "");
            String name = Json.getString(stamped, "name", null);
            return new CallerId("agent:" + connection,
                (name == null || name.isEmpty() ? "agent" : name) + " (" + connection + ")");
        }
        if (session.isOrchestratorLink()) {
            // An orchestrator too old to stamp callers: all its agents are one caller, as before.
            return new CallerId("orchestrator", "an agent through the orchestrator");
        }
        String shortId = session.getId().length() > 8 ? session.getId().substring(0, 8) : session.getId();
        return new CallerId("session:" + session.getId(), session.describeClient() + " (session " + shortId + ")");
    }

    /**
     * The {@code _meta} stamp to put on a call this client makes to the server for this agent, such as
     * {@code bringPlayer}'s teleport, so the server sees the same agent the call came from (#144). Null
     * when the call did not come through the orchestrator.
     */
    @Nullable
    public JsonObject stamp() {
        if (stamp == null) {
            return null;
        }
        JsonObject meta = new JsonObject();
        meta.add(META_KEY, Json.parse(Json.write(stamp)));
        return meta;
    }

    /** Stable for the life of the agent's connection; compare these, never labels. */
    public String id() {
        return id;
    }

    /** For people and models to read: the client's name and its connection. */
    public String label() {
        return label;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("label", label);
        return json;
    }
}
