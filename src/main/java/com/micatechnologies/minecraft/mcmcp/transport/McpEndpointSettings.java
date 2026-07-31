package com.micatechnologies.minecraft.mcmcp.transport;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The resolved network and safety settings for one MCMCP endpoint.
 *
 * <p>A plain immutable holder rather than reads against {@code McmcpConfig} from inside the
 * transport. Two reasons: the transport is then testable without Forge's config system loaded, and
 * an endpoint's settings are frozen for its lifetime, so a config edit mid-session cannot move the
 * bind address out from under a running server socket. Restarting the endpoint is what applies new
 * settings, and that is an explicit operation.
 */
public final class McpEndpointSettings {

    private final String bindAddress;
    private final int port;
    private final String path;
    private final boolean requireAuth;
    private final String authToken;
    private final Set<String> allowedOrigins;
    private final long sessionIdleTimeoutMillis;
    private final int maxSessions;
    private final int workerThreads;
    private final long gameThreadTimeoutMillis;

    private McpEndpointSettings(Builder builder) {
        this.bindAddress = builder.bindAddress;
        this.port = builder.port;
        this.path = builder.path;
        this.requireAuth = builder.requireAuth;
        this.authToken = builder.authToken;
        this.allowedOrigins = Collections.unmodifiableSet(new LinkedHashSet<>(builder.allowedOrigins));
        this.sessionIdleTimeoutMillis = builder.sessionIdleTimeoutMillis;
        this.maxSessions = builder.maxSessions;
        this.workerThreads = builder.workerThreads;
        this.gameThreadTimeoutMillis = builder.gameThreadTimeoutMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    public String getPath() {
        return path;
    }

    public boolean isRequireAuth() {
        return requireAuth;
    }

    public String getAuthToken() {
        return authToken;
    }

    public Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public long getSessionIdleTimeoutMillis() {
        return sessionIdleTimeoutMillis;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public long getGameThreadTimeoutMillis() {
        return gameThreadTimeoutMillis;
    }

    /**
     * Whether this endpoint is reachable from outside the machine.
     *
     * <p>Used to decide how loudly to warn at startup. Binding to a non-loopback address exposes a
     * remote-control interface for someone's game to the network, so it is worth being noisy about
     * even when the operator meant it.
     */
    public boolean isExposedBeyondLoopback() {
        return !("127.0.0.1".equals(bindAddress) || "localhost".equals(bindAddress) || "::1".equals(bindAddress));
    }

    public String describeUrl() {
        String host = bindAddress.contains(":") ? "[" + bindAddress + "]" : bindAddress;
        return "http://" + host + ":" + port + path;
    }

    public static final class Builder {

        /**
         * Loopback by default, and this default should survive any refactor.
         *
         * <p>MCP's own transport security guidance is explicit that a local HTTP server must bind
         * loopback, because binding {@code 0.0.0.0} exposes it to the whole network — here, an
         * interface that can move a player, run commands and read the screen.
         */
        private String bindAddress = "127.0.0.1";

        private int port = 25585;
        private String path = "/mcp";
        private boolean requireAuth = true;
        private String authToken = "";
        private Set<String> allowedOrigins = new LinkedHashSet<>(
            Arrays.asList("http://localhost", "http://127.0.0.1"));
        private long sessionIdleTimeoutMillis = 30L * 60L * 1000L;
        private int maxSessions = 8;
        private int workerThreads = 4;
        private long gameThreadTimeoutMillis = 5000L;

        public Builder bindAddress(String value) {
            this.bindAddress = value;
            return this;
        }

        public Builder port(int value) {
            this.port = value;
            return this;
        }

        public Builder path(String value) {
            // com.sun.net.httpserver contexts must start with '/', and a trailing slash makes the
            // context match differently than clients expect. Normalise rather than validate: the
            // value comes from a hand-edited config file.
            String normalised = value == null || value.isEmpty() ? "/mcp" : value.trim();
            if (!normalised.startsWith("/")) {
                normalised = "/" + normalised;
            }
            while (normalised.length() > 1 && normalised.endsWith("/")) {
                normalised = normalised.substring(0, normalised.length() - 1);
            }
            this.path = normalised;
            return this;
        }

        public Builder requireAuth(boolean value) {
            this.requireAuth = value;
            return this;
        }

        public Builder authToken(String value) {
            this.authToken = value == null ? "" : value.trim();
            return this;
        }

        public Builder allowedOrigins(Iterable<String> values) {
            Set<String> origins = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    origins.add(value.trim());
                }
            }
            this.allowedOrigins = origins;
            return this;
        }

        public Builder sessionIdleTimeoutMillis(long value) {
            this.sessionIdleTimeoutMillis = value;
            return this;
        }

        public Builder maxSessions(int value) {
            this.maxSessions = Math.max(1, value);
            return this;
        }

        public Builder workerThreads(int value) {
            this.workerThreads = Math.max(2, value);
            return this;
        }

        public Builder gameThreadTimeoutMillis(long value) {
            this.gameThreadTimeoutMillis = Math.max(100L, value);
            return this;
        }

        public McpEndpointSettings build() {
            return new McpEndpointSettings(this);
        }
    }
}
