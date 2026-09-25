package com.micatechnologies.minecraft.mcmcp;

import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.link.LinkSettings;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpointSettings;
import java.io.File;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraftforge.common.config.Configuration;

/**
 * MCMCP's Forge configuration.
 *
 * <h2>Defaults are the security posture</h2>
 *
 * Almost nobody reads a mod's config file, so the defaults here <em>are</em> the security model for
 * the overwhelming majority of installs. They were chosen accordingly:
 *
 * <ul>
 *   <li>Bind to loopback. An MCP endpoint is a remote-control interface for someone's game; the
 *       default must not be reachable from the network.</li>
 *   <li>Require a bearer token, generated on first launch and written back to the config. Nothing
 *       shipped in a jar can be a shared secret, and an empty token would mean every install
 *       accepts every caller.</li>
 *   <li>The client endpoint is on and the server endpoint is off. The client endpoint only ever
 *       does what the player themselves could do; the server endpoint hands out authoritative
 *       control over everyone's world and needs an explicit decision from whoever runs it.</li>
 * </ul>
 *
 * <h2>Capability switches</h2>
 *
 * The {@code permissions} category gates whole families of tools. Turning one off does not hide the
 * tools — they stay listed and return an error naming the setting that disabled them. That is
 * deliberate: a tool that vanishes leaves a model guessing why its plan is impossible, while an
 * error that says "player control is disabled by permissions.allowPlayerControl" is something it
 * can report to the human and move on from.
 */
public class McmcpConfig {

    private static final String CATEGORY_ENDPOINTS = "endpoints";
    private static final String CATEGORY_PERMISSIONS = "permissions";
    private static final String CATEGORY_LIMITS = "limits";
    private static final String CATEGORY_IDENTITY = "identity";
    private static final String CATEGORY_ORCHESTRATOR = "orchestrator";

    /**
     * Dev-launch overrides injected by {@code addon.gradle}.
     *
     * <p>System properties rather than a committed config: {@code run/} is gitignored, so a dev
     * config is recreated from defaults on every fresh checkout, and hard-coding dev ports into the
     * shipped defaults would push them onto every player. They act as defaults only — a value the
     * developer typed into {@code config/mcmcp.cfg} always wins, so editing the config is never a
     * fight with the build script.
     */
    private static final String DEV_PORT_PROPERTY = "mcmcp.dev.port";
    private static final String DEV_AUTOSTART_PROPERTY = "mcmcp.dev.autostart";

    private static Configuration configuration;

    /**
     * The game installation root, remembered from the config file's location.
     *
     * <p>{@code McmcpPaths.gameDirectory()} goes through {@code Loader}, which is fine everywhere
     * else but would tie this class to Forge's load order during {@code preInit}. The config file
     * Forge suggests is always {@code <gameDir>/config/mcmcp.cfg}, so two parent hops get there
     * with no dependency at all — and it is needed here only to name a default.
     */
    @Nullable
    private static File gameDirectory;

    // Endpoints
    private static boolean clientEndpointEnabled = true;
    private static boolean serverEndpointEnabled = false;
    private static String bindAddress = "127.0.0.1";
    private static int clientPort = 25585;
    private static int serverPort = 25586;
    private static String endpointPath = "/mcp";
    private static boolean requireAuth = true;
    private static String authToken = "";
    private static List<String> allowedOrigins =
        Arrays.asList("http://localhost", "http://127.0.0.1");

    // Permissions
    private static boolean allowCommands = true;
    private static boolean allowPlayerControl = true;
    private static boolean allowInventoryChanges = true;
    private static boolean allowWorldEdits = false;
    private static boolean allowScreenshots = true;
    private static boolean allowLogAccess = true;
    private static boolean allowChat = true;
    private static boolean allowProcessControl = true;
    private static Set<String> blockedCommands = Collections.emptySet();

    // Identity
    private static String instanceId = "";
    private static String instanceSecret = "";
    private static String instanceName = "";

    // Orchestrator link
    private static boolean orchestratorEnabled = true;
    private static String orchestratorHost = "127.0.0.1";
    private static int orchestratorPort = 25580;
    private static int orchestratorBackoffInitialMillis = 1000;
    private static int orchestratorBackoffMaxMillis = 30000;

    // Limits
    private static int maxSessions = 8;
    private static int sessionIdleTimeoutSeconds = 1800;
    private static int workerThreads = 4;
    private static int gameThreadTimeoutMillis = 5000;
    private static int maxScanRadius = 32;
    private static int maxBlockVolume = 32768;
    private static int maxInputTicks = 200;
    private static int maxInputLockSeconds = 1800;
    private static int clientStallSeconds = 15;
    private static int maxLogLines = 500;

    private McmcpConfig() {
    }

    /**
     * Loads (and creates, on first run) the config file.
     *
     * <p>Called from {@code preInit} with Forge's suggested path. Generating a token here rather
     * than lazily means it exists before either endpoint starts, and is in the file the player can
     * read before they go looking for it.
     */
    public static void init(File configFile) {
        // <gameDir>/config/mcmcp.cfg -> <gameDir>. Only ever used to name a default instance label.
        File configDir = configFile == null ? null : configFile.getParentFile();
        gameDirectory = configDir == null ? null : configDir.getParentFile();

        configuration = new Configuration(configFile);
        configuration.load();
        read();
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /** Re-reads the file from disk. Backs {@code /mcmcp reload}. */
    public static void reload() {
        if (configuration == null) {
            return;
        }
        configuration.load();
        read();
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    private static void read() {
        int devBasePort = Integer.getInteger(DEV_PORT_PROPERTY, 25585);
        boolean devAutostart = Boolean.getBoolean(DEV_AUTOSTART_PROPERTY);

        clientEndpointEnabled = configuration.getBoolean("enableClientEndpoint", CATEGORY_ENDPOINTS, true,
            "Run an MCP endpoint inside the game client.\n"
                + "This is the endpoint that works everywhere: it controls the local player on whatever "
                + "server they are connected to, needs no server-side install, no operator rights and no "
                + "inbound port on the server. It can do exactly what the player could do by hand.");

        serverEndpointEnabled = configuration.getBoolean("enableServerEndpoint", CATEGORY_ENDPOINTS,
            devAutostart,
            "Run an MCP endpoint inside the server (dedicated, or the integrated server behind a "
                + "singleplayer world).\n"
                + "This endpoint sees authoritative world state for every player and runs commands with "
                + "server authority. Off by default: it is a much larger grant than the client endpoint "
                + "and should be an explicit decision by whoever operates the server.");

        bindAddress = configuration.getString("bindAddress", CATEGORY_ENDPOINTS, "127.0.0.1",
            "Network interface both endpoints bind to.\n"
                + "Leave this as 127.0.0.1 unless you know why you are changing it. Any other value makes "
                + "a remote-control interface for this game reachable from the network. To reach it from "
                + "another machine, forward the port over SSH rather than binding a public address.");

        clientPort = configuration.getInt("clientPort", CATEGORY_ENDPOINTS, devBasePort, 1024, 65535,
            "TCP port for the client endpoint.");

        serverPort = configuration.getInt("serverPort", CATEGORY_ENDPOINTS, devBasePort + 1, 1024, 65535,
            "TCP port for the server endpoint.\n"
                + "Must differ from clientPort: a singleplayer world runs both endpoints in one process.");

        endpointPath = configuration.getString("endpointPath", CATEGORY_ENDPOINTS, "/mcp",
            "URL path the MCP endpoint is served from. A liveness probe is served at this path + "
                + "'/health' and needs no authentication.");

        requireAuth = configuration.getBoolean("requireAuth", CATEGORY_ENDPOINTS, true,
            "Require an 'Authorization: Bearer <token>' header on every request.\n"
                + "Turning this off means any process on this machine — including any web page you visit, "
                + "if it can guess the port — can control the game. Only ever appropriate on an isolated "
                + "test instance.");

        authToken = configuration.getString("authToken", CATEGORY_ENDPOINTS, "",
            "Bearer token clients must present. Generated automatically on first launch if left empty.\n"
                + "Treat this like a password: anyone holding it can act as you in-game. Rotate it by "
                + "clearing this value and restarting.");

        allowedOrigins = Arrays.asList(configuration.getStringList("allowedOrigins", CATEGORY_ENDPOINTS,
            new String[] { "http://localhost", "http://127.0.0.1" },
            "Origins accepted on cross-origin requests, matched ignoring port.\n"
                + "This is the DNS-rebinding defence: without it, a web page you visit could have your "
                + "browser POST to this endpoint and drive your game. Requests with no Origin header — "
                + "which is every non-browser MCP client — are unaffected. '*' disables the check."));

        // Identity
        instanceId = configuration.getString("instanceId", CATEGORY_IDENTITY, "",
            "Stable id for this game instance, generated on first launch if left empty.\n"
                + "An orchestrator stores its approval of this instance against this id, so changing "
                + "it means being asked to approve the instance again. It survives moving or renaming "
                + "the instance folder; that is the point of it.");

        instanceSecret = configuration.getString("instanceSecret", CATEGORY_IDENTITY, "",
            "Secret this instance proves its identity with when connecting to an orchestrator. "
                + "Generated automatically on first launch.\n"
                + "Treat this like a password. Without it, any process on this machine could claim to "
                + "be this instance and inherit whatever access you have granted it. Rotate it by "
                + "clearing this value and restarting — you will be asked to approve the instance "
                + "again.");

        instanceName = configuration.getString("instanceName", CATEGORY_IDENTITY, "",
            "Human-readable name for this instance, shown in an orchestrator's roster and in tool "
                + "results. Defaults to this instance folder's name.\n"
                + "This is the one field here meant to be edited. Name it after what you are doing in "
                + "it — 'mymod dev', 'vanilla control' — because it is how you and a model will tell "
                + "several running games apart.");

        // Orchestrator link
        orchestratorEnabled = configuration.getBoolean("enableOrchestratorLink", CATEGORY_ORCHESTRATOR, true,
            "Connect out to an MCMCP orchestrator, so several running game instances can be driven "
                + "through one MCP endpoint.\n"
                + "Harmless when no orchestrator is running: the link retries quietly in the "
                + "background and the game is unaffected. This does not replace the HTTP endpoint "
                + "above — both run, and either can be used on its own.");

        orchestratorHost = configuration.getString("orchestratorHost", CATEGORY_ORCHESTRATOR, "127.0.0.1",
            "Host the orchestrator is listening on.\n"
                + "Only loopback is supported today. The link carries this instance's secret and then "
                + "full control of this game, and it is not encrypted yet — do not point it across a "
                + "network.");

        orchestratorPort = configuration.getInt("orchestratorPort", CATEGORY_ORCHESTRATOR, 25580, 1024, 65535,
            "TCP port the orchestrator is listening on.");

        orchestratorBackoffInitialMillis = configuration.getInt("reconnectBackoffMillis",
            CATEGORY_ORCHESTRATOR, 1000, 100, 60000,
            "How long to wait before the first reconnect attempt, in milliseconds. The delay doubles "
                + "after each failure up to reconnectBackoffMaxMillis.");

        orchestratorBackoffMaxMillis = configuration.getInt("reconnectBackoffMaxMillis",
            CATEGORY_ORCHESTRATOR, 30000, 1000, 600000,
            "Longest gap between reconnect attempts, in milliseconds.\n"
                + "The common case is no orchestrator installed at all, so this wants to be long "
                + "enough that retrying costs nothing and short enough that starting the app is "
                + "noticed within a few seconds of it being ready.");

        // Permissions
        allowCommands = configuration.getBoolean("allowCommands", CATEGORY_PERMISSIONS, true,
            "Allow tools that run chat commands. On the client endpoint these execute with the player's "
                + "own permission level, exactly as if typed.");

        blockedCommands = new HashSet<>(Arrays.asList(configuration.getStringList("blockedCommands",
            CATEGORY_PERMISSIONS, new String[] { "stop", "op", "deop", "ban", "ban-ip", "whitelist" },
            "Command names refused by the command tools, without the leading slash.\n"
                + "A blocklist is a backstop, not a security boundary — commands can be spelled in ways "
                + "this does not catch. The real boundary is the permission level the command runs at.")));

        allowPlayerControl = configuration.getBoolean("allowPlayerControl", CATEGORY_PERMISSIONS, true,
            "Allow tools that move the player, turn the camera, and press keys or mouse buttons. "
                + "Client endpoint only.");

        allowInventoryChanges = configuration.getBoolean("allowInventoryChanges", CATEGORY_PERMISSIONS, true,
            "Allow tools that change inventory: selecting a hotbar slot, swapping, dropping items.");

        allowWorldEdits = configuration.getBoolean("allowWorldEdits", CATEGORY_PERMISSIONS, false,
            "Allow tools that write world state directly (setting blocks, spawning or removing entities) "
                + "rather than through player actions.\n"
                + "Off by default. Direct world writes bypass protections, claims and event handlers that "
                + "other mods rely on, so they are a fundamentally different grant from 'act as the "
                + "player'.");

        allowScreenshots = configuration.getBoolean("allowScreenshots", CATEGORY_PERMISSIONS, true,
            "Allow the screenshot tools. Client endpoint only. Screenshots capture whatever is on screen, "
                + "including any other window content composited into the game's framebuffer.");

        allowLogAccess = configuration.getBoolean("allowLogAccess", CATEGORY_PERMISSIONS, true,
            "Allow reading the tail of the game log. Useful for debugging; note that logs can contain "
                + "server addresses, player names and mod diagnostics.");

        allowChat = configuration.getBoolean("allowChat", CATEGORY_PERMISSIONS, true,
            "Allow sending chat messages as the player. Client endpoint only.");

        allowProcessControl = configuration.getBoolean("allowProcessControl", CATEGORY_PERMISSIONS, true,
            "Allow tools that end the game process: client_quit, and server_stop on the server "
                + "endpoint.\n"
                + "On by default, and a switch of its own rather than a corner of allowPlayerControl, "
                + "because it is the one capability whose exercise also ends the endpoint that has "
                + "it. On a dev instance that is the point -- stop cleanly, relaunch, re-measure -- "
                + "and gating it by default would defeat it. Turn it off on anything somebody else "
                + "is playing on.\n"
                + "Worlds are saved on the way out either way, the same as the Quit Game button and "
                + "the /stop command.");

        // Limits
        maxSessions = configuration.getInt("maxSessions", CATEGORY_LIMITS, 8, 1, 64,
            "Concurrent MCP sessions per endpoint. Each permitted session reserves an HTTP worker "
                + "thread so its event stream cannot starve request handling.");

        sessionIdleTimeoutSeconds = configuration.getInt("sessionIdleTimeoutSeconds", CATEGORY_LIMITS,
            1800, 30, 86400,
            "Drop a session after this many seconds without traffic. Clients crash without saying "
                + "goodbye; without this, their sessions accumulate until the game restarts.");

        workerThreads = configuration.getInt("workerThreads", CATEGORY_LIMITS, 4, 2, 32,
            "HTTP worker threads for handling requests, on top of the one reserved per session.");

        gameThreadTimeoutMillis = configuration.getInt("gameThreadTimeoutMillis", CATEGORY_LIMITS, 5000,
            100, 60000,
            "How long a tool waits for the game thread to run its work before failing.\n"
                + "This is a timeout on the game thread getting around to the task, not on the task "
                + "itself. A loaded server can take several ticks; 5 seconds is generous for that and "
                + "short enough that a stuck endpoint is noticed.");

        maxScanRadius = configuration.getInt("maxScanRadius", CATEGORY_LIMITS, 32, 1, 128,
            "Largest radius, in blocks, that world- and entity-scanning tools will search.\n"
                + "Scans run on the game thread, and cost grows with the cube of this number. 32 is "
                + "already 260,000 blocks per call.");

        maxBlockVolume = configuration.getInt("maxBlockVolume", CATEGORY_LIMITS, 32768, 64, 262144,
            "Most blocks a single bulk read or write may touch, counted as the volume of the region.\n"
                + "The default is a 32x32x32 cube. Bulk operations run in one game-thread task, so this "
                + "is directly a bound on how long one MCP call can stall the tick loop — a 64x64x64 "
                + "region is 262,144 block writes and will visibly freeze the server.");

        maxInputTicks = configuration.getInt("maxInputTicks", CATEGORY_LIMITS, 200, 1, 1200,
            "Longest a single input tool may hold a key or button down, in ticks (20 ticks = 1 second). "
                + "Bounds how far one call can move the player before the model gets to look again.");

        maxInputLockSeconds = configuration.getInt("maxInputLockSeconds", CATEGORY_LIMITS, 1800, 5, 7200,
            "Longest client_input_lock may hold the keyboard and mouse away from the player, in "
                + "seconds. The lock always expires on its own, so a model that stops answering "
                + "cannot leave the machine locked; this is the ceiling on how long that takes. "
                + "Pressing Escape twice releases it immediately whatever this is set to.");

        clientStallSeconds = configuration.getInt("clientStallSeconds", CATEGORY_LIMITS, 15, 5, 600,
            "How long the client thread may go without finishing a frame or tick before MCMCP treats it "
                + "as stalled: it releases the mouse cursor if the game had captured it and reports the stall "
                + "through game_health and the orchestrator. A stall that lasts a minute also logs the "
                + "thread's stack.\n"
                + "A world load stops frames too, which is why this is not shorter. Releasing the cursor "
                + "during a load is harmless; the game has not captured it under a loading screen.");

        maxLogLines = configuration.getInt("maxLogLines", CATEGORY_LIMITS, 500, 10, 5000,
            "Most log lines returnable in one call.");

        ensureAuthToken();
        ensureIdentity();
    }

    /**
     * Generates and persists the instance id, secret and default label when they are missing.
     *
     * <p>Runs on every load, not just the first: clearing any one of the three in the file is the
     * documented way to rotate it, and each is regenerated independently so clearing the secret does
     * not silently change the id and cost the user their orchestrator approval as well.
     *
     * <p>Unlike {@link #ensureAuthToken()} this is unconditional — it does not check whether the
     * orchestrator link is enabled. The identity is also what {@code /mcmcp status} and
     * {@code mcmcp_endpoint_info} report, and an instance that can be told apart from its neighbours
     * is useful whether or not it is currently linked to anything.
     */
    private static void ensureIdentity() {
        String directoryName = gameDirectory == null ? null : gameDirectory.getName();
        boolean changed = false;

        if (instanceId.trim().isEmpty()) {
            instanceId = McmcpIdentity.generateInstanceId(directoryName);
            configuration.get(CATEGORY_IDENTITY, "instanceId", "").set(instanceId);
            changed = true;
        }
        if (instanceSecret.trim().isEmpty()) {
            instanceSecret = McmcpIdentity.generateSecret();
            configuration.get(CATEGORY_IDENTITY, "instanceSecret", "").set(instanceSecret);
            changed = true;
        }
        if (instanceName.trim().isEmpty()) {
            instanceName = directoryName == null || directoryName.isEmpty() ? instanceId : directoryName;
            configuration.get(CATEGORY_IDENTITY, "instanceName", "").set(instanceName);
            changed = true;
        }

        if (changed) {
            configuration.save();
            Mcmcp.LOGGER.info("MCMCP instance identity: " + instanceId + " (\"" + instanceName + "\")");
        }
    }

    /**
     * Generates and persists a token when authentication is on and none is set.
     *
     * <p>128 bits from {@link SecureRandom}. The alternative — a token derived from something in the
     * install, or a fixed default — would be identical across every copy of the mod, which is the
     * same as having no token at all.
     */
    private static void ensureAuthToken() {
        if (!requireAuth || !authToken.trim().isEmpty()) {
            return;
        }

        byte[] entropy = new byte[16];
        new SecureRandom().nextBytes(entropy);
        StringBuilder token = new StringBuilder(32);
        for (byte b : entropy) {
            token.append(Character.forDigit((b >> 4) & 0xF, 16));
            token.append(Character.forDigit(b & 0xF, 16));
        }
        authToken = token.toString();

        configuration.get(CATEGORY_ENDPOINTS, "authToken", "").set(authToken);
        configuration.save();
        Mcmcp.LOGGER.info("MCMCP generated a new API token and saved it to the config file. "
            + "Retrieve it with '/mcmcp token' in-game, or read config/mcmcp.cfg.");
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public static boolean isClientEndpointEnabled() {
        return clientEndpointEnabled;
    }

    public static boolean isServerEndpointEnabled() {
        return serverEndpointEnabled;
    }

    public static String getAuthToken() {
        return authToken;
    }

    public static boolean isAllowCommands() {
        return allowCommands;
    }

    public static boolean isCommandBlocked(String command) {
        if (command == null) {
            return true;
        }
        String name = command.trim();
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        int space = name.indexOf(' ');
        if (space > 0) {
            name = name.substring(0, space);
        }
        return blockedCommands.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isAllowPlayerControl() {
        return allowPlayerControl;
    }

    public static boolean isAllowInventoryChanges() {
        return allowInventoryChanges;
    }

    public static boolean isAllowWorldEdits() {
        return allowWorldEdits;
    }

    public static boolean isAllowScreenshots() {
        return allowScreenshots;
    }

    public static boolean isAllowLogAccess() {
        return allowLogAccess;
    }

    public static boolean isAllowChat() {
        return allowChat;
    }

    public static boolean isAllowProcessControl() {
        return allowProcessControl;
    }

    public static int getMaxScanRadius() {
        return maxScanRadius;
    }

    public static int getMaxBlockVolume() {
        return maxBlockVolume;
    }

    public static int getMaxInputTicks() {
        return maxInputTicks;
    }

    public static int getMaxInputLockSeconds() {
        return maxInputLockSeconds;
    }

    public static int getClientStallSeconds() {
        return clientStallSeconds;
    }

    public static int getMaxLogLines() {
        return maxLogLines;
    }

    public static boolean isOrchestratorLinkEnabled() {
        return orchestratorEnabled;
    }

    /** This instance's identity. Never put {@code getInstanceSecret()} anywhere a human can read it. */
    public static McmcpIdentity identity() {
        return new McmcpIdentity(instanceId, instanceSecret, instanceName);
    }

    /** Builds the immutable settings snapshot the orchestrator link runs with. */
    public static LinkSettings linkSettings() {
        return LinkSettings.builder()
            .enabled(orchestratorEnabled)
            .host(orchestratorHost)
            .port(orchestratorPort)
            .backoffInitialMillis(orchestratorBackoffInitialMillis)
            .backoffMaxMillis(orchestratorBackoffMaxMillis)
            .workerThreads(workerThreads)
            .build();
    }

    /** Builds the immutable settings snapshot one endpoint runs with. */
    public static McpEndpointSettings settingsFor(McmcpSide side) {
        return McpEndpointSettings.builder()
            .bindAddress(bindAddress)
            .port(side.isClient() ? clientPort : serverPort)
            .path(endpointPath)
            .requireAuth(requireAuth)
            .authToken(authToken)
            .allowedOrigins(allowedOrigins)
            .maxSessions(maxSessions)
            .sessionIdleTimeoutMillis(sessionIdleTimeoutSeconds * 1000L)
            .workerThreads(workerThreads)
            .gameThreadTimeoutMillis(gameThreadTimeoutMillis)
            .build();
    }
}
