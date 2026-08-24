package com.micatechnologies.minecraft.mcmcp.link;

import javax.annotation.Nullable;

/**
 * The wire vocabulary of the orchestrator link.
 *
 * <h2>Shape of the conversation</h2>
 *
 * The link is newline-delimited JSON over a plain TCP socket, dialled <em>outward</em> by the game
 * instance. Framing is deliberately the dullest thing that works: no length prefixes, no
 * handshakes borrowed from a larger protocol, nothing that needs a dependency. It behaves
 * identically under the vanilla Java 8 launch and both lwjgl3ify launches, and it can be driven by
 * hand from a terminal when something is wrong, which is worth more than elegance here.
 *
 * <p>Two kinds of frame travel over it, told apart by which field they carry:
 *
 * <ul>
 *   <li><b>Control frames</b> have {@code "type"}. Today that is the opening
 *       {@code hello}/{@code welcome}/{@code rejected} exchange.</li>
 *   <li><b>MCP frames</b> have {@code "jsonrpc"}. Everything after a {@code welcome} is ordinary
 *       JSON-RPC, handed to the same {@code McpDispatcher} the HTTP transport uses.</li>
 * </ul>
 *
 * <p>Keeping them distinguishable by field rather than by position is what leaves room for control
 * frames <em>after</em> the handshake — an orchestrator pushing a renamed label, say — without
 * another protocol version. Nothing sends one yet.
 *
 * <h2>Version negotiation</h2>
 *
 * {@link #VERSION} is a single integer, sent in {@code hello} and echoed in {@code welcome}. There
 * is no range negotiation because there is nothing yet to negotiate; an orchestrator that does not
 * recognise the version says so with a {@link #REASON_UNSUPPORTED_PROTOCOL} rejection, which is a
 * clearer failure than a mismatched field discovered three frames later.
 */
public final class LinkProtocol {

    /** Current link protocol version. Bump only for a change that an older peer cannot parse. */
    public static final int VERSION = 1;

    // Frame discriminators
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_JSONRPC = "jsonrpc";

    // Control frame types
    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_WELCOME = "welcome";
    public static final String TYPE_REJECTED = "rejected";

    // Shared control fields
    public static final String FIELD_LINK_PROTOCOL = "linkProtocol";
    public static final String FIELD_REASON = "reason";
    public static final String FIELD_MESSAGE = "message";

    // hello fields
    public static final String FIELD_INSTANCE_ID = "instanceId";
    public static final String FIELD_INSTANCE_SECRET = "instanceSecret";
    public static final String FIELD_INSTANCE_NAME = "instanceName";
    public static final String FIELD_SIDE = "side";
    public static final String FIELD_GAME_DIRECTORY = "gameDirectory";
    public static final String FIELD_MOD_VERSION = "modVersion";
    public static final String FIELD_MINECRAFT_VERSION = "minecraftVersion";
    public static final String FIELD_ENDPOINT_URL = "endpointUrl";

    // welcome fields
    public static final String FIELD_ORCHESTRATOR = "orchestrator";

    /**
     * The instance is not approved yet and a human has been asked.
     *
     * <p>Retryable, and the one rejection that is completely normal: it is what trust-on-first-use
     * looks like from this side while the approval dialog is on screen.
     */
    public static final String REASON_PENDING_APPROVAL = "pending-approval";

    /**
     * The id is known but the secret does not match.
     *
     * <p><b>Not</b> retryable, and the reason retrying is wrong is the whole point of the secret:
     * either this instance's secret was rotated — in which case a human has to re-approve it — or
     * something else is claiming to be this instance, and hammering the orchestrator with a bad
     * secret helps neither case. It gets a loud log line, once.
     */
    public static final String REASON_SECRET_MISMATCH = "secret-mismatch";

    /** The orchestrator does not speak this link protocol version. Not retryable; update one side. */
    public static final String REASON_UNSUPPORTED_PROTOCOL = "unsupported-protocol";

    /** The hello frame could not be parsed. Not retryable — resending the same frame cannot help. */
    public static final String REASON_MALFORMED_HELLO = "malformed-hello";

    /** A human explicitly revoked this instance. Not retryable; they have to approve it again. */
    public static final String REASON_REVOKED = "revoked";

    private LinkProtocol() {
    }

    /**
     * Whether reconnecting after this rejection could plausibly succeed without someone doing
     * something first.
     *
     * <p>An unrecognised reason is treated as retryable. A newer orchestrator inventing a reason
     * this build has never heard of is far more likely than a genuinely fatal condition, and the
     * failure modes are asymmetric: retrying a fatal rejection costs a log line every thirty
     * seconds, while giving up on a transient one silently strands the instance until the game is
     * restarted.
     */
    public static boolean isRetryable(@Nullable String reason) {
        if (reason == null) {
            return true;
        }
        switch (reason) {
            case REASON_SECRET_MISMATCH:
            case REASON_UNSUPPORTED_PROTOCOL:
            case REASON_MALFORMED_HELLO:
            case REASON_REVOKED:
                return false;
            default:
                return true;
        }
    }

    /** A sentence for the log explaining what a rejection means and what to do about it. */
    public static String explain(@Nullable String reason, @Nullable String message) {
        String detail = message == null || message.trim().isEmpty() ? "" : " (" + message.trim() + ")";
        if (reason == null) {
            return "the orchestrator rejected the link without giving a reason" + detail;
        }
        switch (reason) {
            case REASON_PENDING_APPROVAL:
                return "waiting for this instance to be approved in the orchestrator" + detail;
            case REASON_SECRET_MISMATCH:
                return "the orchestrator knows this instance id but not this secret" + detail
                    + ". Either identity.instanceSecret was rotated, or something else is using this "
                    + "instance id. Approve this instance again in the orchestrator to accept the new "
                    + "secret. Not retrying.";
            case REASON_UNSUPPORTED_PROTOCOL:
                return "the orchestrator does not speak link protocol version " + VERSION + detail
                    + ". Update whichever of the mod or the orchestrator is older. Not retrying.";
            case REASON_MALFORMED_HELLO:
                return "the orchestrator could not parse this instance's hello frame" + detail
                    + ". This is a bug; please report it. Not retrying.";
            case REASON_REVOKED:
                return "this instance's approval was revoked in the orchestrator" + detail
                    + ". Approve it again there to reconnect. Not retrying.";
            default:
                return "the orchestrator rejected the link: " + reason + detail;
        }
    }
}
