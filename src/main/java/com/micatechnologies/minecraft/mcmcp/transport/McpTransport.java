package com.micatechnologies.minecraft.mcmcp.transport;

import java.io.IOException;

/**
 * One way of carrying MCP traffic into and out of this game instance.
 *
 * <p>The abstraction exists because {@code protocol/} was built to allow it: {@code McpDispatcher}
 * takes a session and a {@code JsonObject} and returns a {@code JsonObject}, knowing nothing about
 * HTTP, headers or sockets. Two implementations use that today, and they differ in the direction
 * the connection is opened, which turns out to be the only difference that matters:
 *
 * <ul>
 *   <li>{@link HttpMcpTransport} <b>listens</b>. It needs a free port, and whoever connects has to
 *       know which port this instance took and hold its bearer token.</li>
 *   <li>{@code ReverseTransport} <b>dials</b>. It needs no port of its own, which is what makes
 *       running several instances at once stop being a configuration exercise.</li>
 * </ul>
 *
 * <p>Both feed the same dispatcher over the same registry, so a tool behaves identically whichever
 * way the call arrived.
 *
 * <h2>Failure is per-transport</h2>
 *
 * {@link #start()} throwing must not take the endpoint down. The motivating case is exactly the one
 * that prompted this work: a second game client fails to bind 25585 because the first one has it,
 * and the right outcome is an instance that is still reachable through its orchestrator link rather
 * than an instance with no MCP at all. {@code McpEndpoint} treats a transport that fails to start as
 * absent and carries on with whatever else came up.
 */
public interface McpTransport {

    /** A short name for logs and {@code /mcmcp status} — "http", "orchestrator link". */
    String describeKind();

    /** Where this transport listens or dials, for the same audience. */
    String describeTarget();

    /**
     * Begins carrying traffic.
     *
     * @throws IOException if this transport cannot start. The endpoint logs it and continues with
     *                     its other transports; it is never fatal on its own.
     */
    void start() throws IOException;

    /** Stops carrying traffic and releases whatever was held. Must be safe to call when not running. */
    void stop();

    boolean isRunning();
}
