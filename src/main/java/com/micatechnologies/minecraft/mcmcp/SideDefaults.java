package com.micatechnologies.minecraft.mcmcp;

/**
 * The defaults that differ between a dedicated server and everything else.
 *
 * <h2>Why a dedicated server is different</h2>
 *
 * Dropping MCMCP into a server's mods folder must never open anything. Other people connect to a
 * dedicated server, its operator may not have read a word about MCMCP, and a listener or a link
 * that comes up by default is a remote-control interface nobody asked for. So on a dedicated server
 * a freshly generated config has the companion on with an empty allowlist — usable by nobody until
 * an operator names someone — and the MCP endpoint, the orchestrator link and the endpoint's command
 * and process permissions off.
 *
 * <p>The dev launch ({@code -Dmcmcp.dev.autostart}, which {@code runServer} and the smoke test set)
 * keeps the old defaults, because the endpoint is how a dev server is driven at all.
 *
 * <p>These are defaults only: Forge's {@code Configuration} uses them for keys the file does not
 * have yet. An existing server config keeps every value it already has.
 *
 * <p>No Forge class is named here, so the rules are unit-tested.
 */
public final class SideDefaults {

    private final boolean dedicatedServer;
    private final boolean devAutostart;

    public SideDefaults(boolean dedicatedServer, boolean devAutostart) {
        this.dedicatedServer = dedicatedServer;
        this.devAutostart = devAutostart;
    }

    /** True when the locked-down server defaults apply. */
    public boolean secureServer() {
        return dedicatedServer && !devAutostart;
    }

    public boolean serverEndpoint() {
        return devAutostart;
    }

    public boolean orchestratorLink() {
        return !secureServer();
    }

    /** Commands through the server endpoint. The companion has its own permission classes. */
    public boolean endpointCommands() {
        return !secureServer();
    }

    /** {@code server_stop} and {@code client_quit} through an endpoint. */
    public boolean endpointProcessControl() {
        return !secureServer();
    }

    /**
     * The companion on a dedicated server. Harmless on by default: with an empty allowlist nobody
     * can use it. Meaningless elsewhere — an integrated server never offers it.
     */
    public boolean companion() {
        return dedicatedServer;
    }

    /** The request journal: the audit trail on a server, opt-in on a client. */
    public boolean journal() {
        return dedicatedServer;
    }
}
