package com.micatechnologies.minecraft.mcmcp.link;

/**
 * What the orchestrator link is currently doing, as reported by {@code /mcmcp link}.
 *
 * <p>The states exist to answer one question a person actually asks — "why can my orchestrator not
 * see this game?" — and the useful answers are genuinely different from one another. "Nothing is
 * listening on 25580" wants the app started. "Waiting for approval" wants a dialog clicked. "The
 * secret does not match" wants the instance re-approved and will never fix itself. Collapsing those
 * into a single connected/disconnected flag throws away the only part that tells someone what to do.
 */
public enum LinkState {

    /** Turned off in the config. Nothing is being attempted. */
    DISABLED("disabled in the config"),

    /** Dialling, or mid-handshake. */
    CONNECTING("connecting"),

    /**
     * Reached the orchestrator, which is waiting for a human to approve this instance.
     *
     * <p>Distinct from {@link #RETRYING} on purpose: something <em>is</em> listening and it has
     * heard us. The next thing to happen is somebody clicking approve, not a network change.
     */
    WAITING_FOR_APPROVAL("waiting to be approved in the orchestrator"),

    /** Connected, handshake complete, serving MCP traffic. */
    CONNECTED("connected"),

    /** Not connected; will try again after a backoff. The ordinary "no orchestrator running" state. */
    RETRYING("retrying"),

    /**
     * Given up until the game or the config changes.
     *
     * <p>Only reached through a rejection that retrying cannot clear — a mismatched secret, a
     * revoked approval, an unsupported protocol version. Everything else retries forever.
     */
    STOPPED("stopped"),

    /** {@code stop()} was called: game shutting down, or {@code /mcmcp stop}. */
    SHUT_DOWN("shut down");

    private final String description;

    LinkState(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public boolean isConnected() {
        return this == CONNECTED;
    }

    /** Whether the link still intends to reach an orchestrator, eventually. */
    public boolean isTrying() {
        return this == CONNECTING || this == RETRYING || this == WAITING_FOR_APPROVAL;
    }
}
