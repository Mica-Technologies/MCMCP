package com.micatechnologies.minecraft.mcmcp.link;

/**
 * The resolved settings for the outbound orchestrator link.
 *
 * <p>Immutable and built once when the link starts, for the same reasons
 * {@code McpEndpointSettings} is: the link layer stays testable without Forge's config system
 * loaded, and a config edit mid-session cannot retarget a socket that is already connected.
 * {@code /mcmcp restart} is what applies new settings.
 *
 * <h2>Why there is a separate settings object at all</h2>
 *
 * The HTTP endpoint and the orchestrator link share a dispatcher and a registry but almost nothing
 * else. The endpoint's settings are about <em>being reached</em> — bind address, auth token,
 * allowed origins, worker pool. The link's are about <em>reaching out</em> — where to dial and how
 * patiently to retry. Folding them together would produce one object where half the fields are
 * meaningless in either direction.
 */
public final class LinkSettings {

    private final boolean enabled;
    private final String host;
    private final int port;
    private final long connectTimeoutMillis;
    private final long backoffInitialMillis;
    private final long backoffMaxMillis;
    private final int workerThreads;

    private LinkSettings(Builder builder) {
        this.enabled = builder.enabled;
        this.host = builder.host;
        this.port = builder.port;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.backoffInitialMillis = builder.backoffInitialMillis;
        this.backoffMaxMillis = builder.backoffMaxMillis;
        this.workerThreads = builder.workerThreads;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public long getBackoffInitialMillis() {
        return backoffInitialMillis;
    }

    public long getBackoffMaxMillis() {
        return backoffMaxMillis;
    }

    /**
     * Threads that run tool calls arriving over the link.
     *
     * <p>Bounded rather than a cached pool. The link's peer is authenticated, so this is not a flood
     * defence — it is backpressure. Tools like {@code client_wait} block for as long as their
     * condition takes, and an unbounded pool would answer a burst of those by making a thread each,
     * inside the game process. A queue behind a fixed pool is the honest version of the same
     * behaviour.
     */
    public int getWorkerThreads() {
        return workerThreads;
    }

    /**
     * Whether the link dials somewhere other than this machine.
     *
     * <p>Loopback is the only supported configuration today. This exists so the transport can warn
     * rather than silently sending an instance secret across a network, and so remote support later
     * is an addition rather than a discovery that nothing ever checked.
     */
    public boolean isRemote() {
        return !("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host));
    }

    public String describeTarget() {
        String shown = host.contains(":") ? "[" + host + "]" : host;
        return shown + ":" + port;
    }

    public static final class Builder {

        private boolean enabled = true;

        /**
         * Loopback, and this default should survive any refactor.
         *
         * <p>The link carries an instance secret and then everything an MCP session can do. Until
         * the handshake grows TLS, any non-loopback value sends both in clear text.
         */
        private String host = "127.0.0.1";

        private int port = 25580;
        private long connectTimeoutMillis = 3000L;
        private long backoffInitialMillis = 1000L;
        private long backoffMaxMillis = 30000L;
        private int workerThreads = 4;

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder host(String value) {
            this.host = value == null || value.trim().isEmpty() ? "127.0.0.1" : value.trim();
            return this;
        }

        public Builder port(int value) {
            this.port = value;
            return this;
        }

        public Builder connectTimeoutMillis(long value) {
            this.connectTimeoutMillis = Math.max(100L, value);
            return this;
        }

        public Builder backoffInitialMillis(long value) {
            this.backoffInitialMillis = Math.max(100L, value);
            return this;
        }

        public Builder backoffMaxMillis(long value) {
            this.backoffMaxMillis = Math.max(100L, value);
            return this;
        }

        public Builder workerThreads(int value) {
            this.workerThreads = Math.max(1, value);
            return this;
        }

        public LinkSettings build() {
            // A maximum below the initial delay would make the backoff run backwards. Clamping here
            // rather than validating: both values come from a hand-edited config file.
            if (backoffMaxMillis < backoffInitialMillis) {
                backoffMaxMillis = backoffInitialMillis;
            }
            return new LinkSettings(this);
        }
    }
}
