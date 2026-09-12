package com.micatechnologies.minecraft.mcmcp.link;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpIdentity;
import com.micatechnologies.minecraft.mcmcp.game.McmcpProcess;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import javax.annotation.Nullable;

/**
 * The opening exchange on an orchestrator link: {@code hello} out, {@code welcome} or
 * {@code rejected} back.
 *
 * <h2>What hello has to carry, and why</h2>
 *
 * The id and secret are what the orchestrator authenticates. Everything else exists so that the
 * human staring at an approval dialog can answer the only question that matters — <em>which game is
 * this?</em> A prompt that says "instance {@code atm9-3f2a1c} wants to connect" is unanswerable; one
 * that names the directory, the side and the Minecraft version is not. The same fields are what let
 * the orchestrator notice a known id arriving from a new directory, which is the copied-config case.
 *
 * <p>The pid and start time are the answer to a narrower question the roster could not answer at
 * all: <em>which of these two identical-looking rows am I actually talking to?</em> The instance id
 * lives in a config file, so two games launched from one directory share it, and MCMCP's ports go to
 * whichever bound first. Read here rather than passed in because they are facts about the JVM this
 * code is running in, and there is no caller better placed to know them.
 *
 * <p>Pure data: no sockets here and no Minecraft. The mod version and Minecraft version arrive as
 * strings from the caller rather than being read off {@code McmcpConstants} and {@code ForgeVersion},
 * which is what lets the whole handshake be exercised in a unit test.
 */
public final class LinkHandshake {

    private LinkHandshake() {
    }

    /**
     * Builds the {@code hello} frame.
     *
     * @param endpointUrl this instance's own HTTP endpoint, if it has one running. Advisory only —
     *                    the orchestrator does not dial it. It is there so the app can show a human
     *                    the direct URL for the times when talking to one instance without the
     *                    orchestrator in the way is the fastest route to an answer.
     */
    public static JsonObject hello(McmcpIdentity identity,
                                   String side,
                                   @Nullable String gameDirectory,
                                   String modVersion,
                                   String minecraftVersion,
                                   @Nullable String endpointUrl) {
        JsonObject frame = new JsonObject();
        frame.addProperty(LinkProtocol.FIELD_TYPE, LinkProtocol.TYPE_HELLO);
        frame.addProperty(LinkProtocol.FIELD_LINK_PROTOCOL, LinkProtocol.VERSION);
        frame.addProperty(LinkProtocol.FIELD_INSTANCE_ID, identity.getInstanceId());
        frame.addProperty(LinkProtocol.FIELD_INSTANCE_SECRET, identity.getInstanceSecret());
        frame.addProperty(LinkProtocol.FIELD_INSTANCE_NAME, identity.getInstanceName());
        frame.addProperty(LinkProtocol.FIELD_SIDE, side);
        frame.addProperty(LinkProtocol.FIELD_MOD_VERSION, modVersion);
        frame.addProperty(LinkProtocol.FIELD_MINECRAFT_VERSION, minecraftVersion);
        if (gameDirectory != null && !gameDirectory.isEmpty()) {
            frame.addProperty(LinkProtocol.FIELD_GAME_DIRECTORY, gameDirectory);
        }
        if (endpointUrl != null && !endpointUrl.isEmpty()) {
            frame.addProperty(LinkProtocol.FIELD_ENDPOINT_URL, endpointUrl);
        }
        long pid = McmcpProcess.pid();
        if (pid != McmcpProcess.UNKNOWN_PID) {
            frame.addProperty(LinkProtocol.FIELD_PID, pid);
        }
        frame.addProperty(LinkProtocol.FIELD_STARTED_AT, McmcpProcess.startedAtIso());
        return frame;
    }

    /**
     * Interprets the orchestrator's reply.
     *
     * @throws LinkProtocolException if the frame is neither a welcome nor a rejection. An
     *                               orchestrator that answers hello with an MCP message, or with a
     *                               control frame of some type this build has never heard of, has
     *                               not completed a handshake, and proceeding as though it had would
     *                               mean serving tool calls to something unauthenticated.
     */
    public static Result parseResponse(@Nullable JsonObject frame) throws LinkProtocolException {
        if (frame == null) {
            throw new LinkProtocolException("The orchestrator closed the link without answering hello");
        }
        String type = Json.getString(frame, LinkProtocol.FIELD_TYPE, "");
        if (LinkProtocol.TYPE_WELCOME.equals(type)) {
            JsonObject orchestrator = Json.getObject(frame, LinkProtocol.FIELD_ORCHESTRATOR);
            return Result.accepted(
                Json.getInt(frame, LinkProtocol.FIELD_LINK_PROTOCOL, LinkProtocol.VERSION),
                orchestrator == null ? "orchestrator" : Json.getString(orchestrator, "name", "orchestrator"),
                orchestrator == null ? "" : Json.getString(orchestrator, "version", ""),
                emptyToNull(Json.getString(frame, LinkProtocol.FIELD_INSTANCE_NAME, "")));
        }
        if (LinkProtocol.TYPE_REJECTED.equals(type)) {
            return Result.rejected(
                Json.getString(frame, LinkProtocol.FIELD_REASON, ""),
                Json.getString(frame, LinkProtocol.FIELD_MESSAGE, ""));
        }
        throw new LinkProtocolException("Expected a '" + LinkProtocol.TYPE_WELCOME + "' or '"
            + LinkProtocol.TYPE_REJECTED + "' frame in reply to hello, got "
            + (type.isEmpty() ? "a frame with no type" : "'" + type + "'"));
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** The outcome of a handshake. */
    public static final class Result {

        private final boolean accepted;
        private final int linkProtocol;
        private final String orchestratorName;
        private final String orchestratorVersion;

        @Nullable
        private final String assignedName;

        private final String reason;
        private final String message;

        private Result(boolean accepted, int linkProtocol, String orchestratorName,
                       String orchestratorVersion, @Nullable String assignedName,
                       String reason, String message) {
            this.accepted = accepted;
            this.linkProtocol = linkProtocol;
            this.orchestratorName = orchestratorName;
            this.orchestratorVersion = orchestratorVersion;
            this.assignedName = assignedName;
            this.reason = reason;
            this.message = message;
        }

        static Result accepted(int linkProtocol, String name, String version, @Nullable String assignedName) {
            return new Result(true, linkProtocol, name, version, assignedName, "", "");
        }

        static Result rejected(String reason, String message) {
            return new Result(false, LinkProtocol.VERSION, "", "", null, reason, message);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public int getLinkProtocol() {
            return linkProtocol;
        }

        public String getOrchestratorName() {
            return orchestratorName;
        }

        public String getOrchestratorVersion() {
            return orchestratorVersion;
        }

        /**
         * The label a human gave this instance in the orchestrator, if there is one.
         *
         * <p>It wins over {@code identity.instanceName} for display, because somebody typed it into
         * the roster on purpose and the config value is only ever a guess derived from a folder name.
         * It is not written back to the config: the orchestrator owns it, and copying it down would
         * make two places to change one label.
         */
        @Nullable
        public String getAssignedName() {
            return assignedName;
        }

        public String getReason() {
            return reason;
        }

        public String getMessage() {
            return message;
        }

        public boolean isRetryable() {
            return accepted || LinkProtocol.isRetryable(reason);
        }

        /** A log-ready sentence describing this outcome. */
        public String describe() {
            if (accepted) {
                String version = orchestratorVersion.isEmpty() ? "" : " " + orchestratorVersion;
                return "connected to " + orchestratorName + version;
            }
            return LinkProtocol.explain(reason, message);
        }
    }
}
