package com.micatechnologies.minecraft.mcmcp.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Protocol version negotiation and the method-name vocabulary of the Model Context Protocol.
 *
 * <p>MCP versions are dates, not semantic versions, and they are negotiated once per session: the
 * client proposes a version in {@code initialize}, and the server answers with either that same
 * version (if it supports it) or the newest one it does support. The client then either proceeds or
 * disconnects. That is the whole handshake — there is no per-message version tag beyond the
 * {@code MCP-Protocol-Version} HTTP header that 2025-06-18 added for the streamable transport.
 *
 * <p>Everything downstream keys off {@link McpSession#getProtocolVersion()} rather than a global
 * constant, because a single MCMCP endpoint routinely serves several clients at once — an editor
 * on one version and a CLI agent on another — and they must not see each other's wire dialect.
 */
public final class McpProtocol {

    // ------------------------------------------------------------------
    // Versions
    // ------------------------------------------------------------------

    public static final String VERSION_2024_11_05 = "2024-11-05";
    public static final String VERSION_2025_03_26 = "2025-03-26";
    public static final String VERSION_2025_06_18 = "2025-06-18";

    /** What we answer with when the client proposes something we do not know. */
    public static final String LATEST_VERSION = VERSION_2025_06_18;

    /**
     * Every version this server can speak, newest first.
     *
     * <p>The older two are kept deliberately. 2024-11-05 is what the original HTTP+SSE transport and
     * a long tail of shipped clients still send, and the differences that matter to MCMCP are small
     * and localised (see {@link #supportsToolStructuredContent} and friends). Dropping them would
     * buy a little simplicity in exchange for silently refusing to talk to real clients.
     */
    private static final List<String> SUPPORTED_VERSIONS = Collections.unmodifiableList(Arrays.asList(
        VERSION_2025_06_18,
        VERSION_2025_03_26,
        VERSION_2024_11_05));

    private McpProtocol() {
    }

    public static List<String> supportedVersions() {
        return SUPPORTED_VERSIONS;
    }

    public static boolean isSupported(@Nullable String version) {
        return version != null && SUPPORTED_VERSIONS.contains(version);
    }

    /**
     * Picks the version to answer an {@code initialize} with.
     *
     * <p>Per spec: echo the client's proposal when we support it, otherwise answer with our latest
     * and let the client decide whether to continue. Note this deliberately does <em>not</em> fail
     * the request — an unsupported version is not an error, it is a negotiation outcome, and
     * returning {@code -32602} here breaks clients that would happily have downgraded.
     */
    public static String negotiate(@Nullable String requested) {
        return isSupported(requested) ? requested : LATEST_VERSION;
    }

    /**
     * Whether {@code version} understands {@code structuredContent} on a tool result.
     *
     * <p>Added in 2025-06-18. Older clients ignore unknown result fields, so emitting it anyway
     * would be harmless on the wire — but some strict clients validate results against the tool's
     * declared {@code outputSchema} only when they know the field, and others log warnings on
     * unrecognised keys. Gating it keeps old sessions byte-identical to what they expect.
     */
    public static boolean supportsToolStructuredContent(@Nullable String version) {
        return VERSION_2025_06_18.equals(version);
    }

    /** Elicitation ({@code elicitation/create}) was introduced in 2025-06-18. */
    public static boolean supportsElicitation(@Nullable String version) {
        return VERSION_2025_06_18.equals(version);
    }

    /**
     * Whether the client may send several JSON-RPC messages in one array.
     *
     * <p>Batching existed in 2025-03-26 and was removed again in 2025-06-18. MCMCP's transport
     * accepts arrays regardless — refusing a batch a client already sent helps nobody — but this
     * flag is what decides whether the server may *originate* one.
     */
    public static boolean supportsBatching(@Nullable String version) {
        return VERSION_2025_03_26.equals(version);
    }

    // ------------------------------------------------------------------
    // Method names — client to server
    // ------------------------------------------------------------------

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_PING = "ping";

    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";
    public static final String METHOD_RESOURCES_READ = "resources/read";
    public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";
    public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

    public static final String METHOD_PROMPTS_LIST = "prompts/list";
    public static final String METHOD_PROMPTS_GET = "prompts/get";

    public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";
    public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

    // ------------------------------------------------------------------
    // Method names — server to client (issued over the SSE stream)
    // ------------------------------------------------------------------

    public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";
    public static final String METHOD_ELICITATION_CREATE = "elicitation/create";
    public static final String METHOD_ROOTS_LIST = "roots/list";

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    public static final String NOTIFICATION_INITIALIZED = "notifications/initialized";
    public static final String NOTIFICATION_CANCELLED = "notifications/cancelled";
    public static final String NOTIFICATION_PROGRESS = "notifications/progress";
    public static final String NOTIFICATION_MESSAGE = "notifications/message";
    public static final String NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    public static final String NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";
    public static final String NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";
    public static final String NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";
    public static final String NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";

    /**
     * Methods a client may call before {@code initialize} has completed.
     *
     * <p>The spec allows exactly these two: {@code initialize} itself, and {@code ping} (so a client
     * can check liveness of a session it is not sure about). Everything else gets
     * {@link JsonRpcException#invalidRequest} until the handshake finishes — without this check a
     * tool call could run against a session whose protocol version and capabilities are still
     * unknown, which is how you end up emitting a 2025-06-18 result shape to a 2024-11-05 client.
     */
    private static final Set<String> PRE_INITIALIZE_METHODS =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(METHOD_INITIALIZE, METHOD_PING)));

    public static boolean isAllowedBeforeInitialize(@Nullable String method) {
        return method != null && PRE_INITIALIZE_METHODS.contains(method);
    }

    /** True for the {@code notifications/*} namespace, which never carries an id and never gets a reply. */
    public static boolean isNotification(@Nullable String method) {
        return method != null && method.startsWith("notifications/");
    }
}
