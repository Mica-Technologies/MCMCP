package com.micatechnologies.minecraft.mcmcp.companion;

/**
 * Constants of the companion wire format: the game-connection channel a client's MCMCP uses to reach
 * the MCMCP on the server it is playing on.
 *
 * <h2>What travels on it</h2>
 *
 * MCP itself. The server runs its ordinary server-side dispatcher and the client forwards JSON-RPC
 * to it unchanged, so every server tool, its schema and its description come from the server's own
 * build. This file only frames those messages for a channel whose packets have a hard size limit.
 *
 * <h2>Limits that shaped it</h2>
 *
 * A client-to-server custom payload over 32,767 bytes is refused in its constructor, on the netty
 * thread, and that disconnects the player. Forge does not split client-to-server packets. So every
 * message is split here into frames of at most {@link #MAX_C2S_FRAME} bytes going up, and
 * {@link #S2C_FRAME} coming down, where Forge could split but where small frames interleave better
 * with the game's own traffic.
 *
 * <p>No Minecraft or Forge class is named in this package: the framing, the reassembly limits and
 * the handshake are tested as plain bytes.
 */
public final class CompanionProtocol {

    /** The Forge channel name. At most 20 characters, which vanilla enforces. */
    public static final String CHANNEL = "mcmcp:companion";

    /**
     * The framing and handshake version. Not MCP's version, which the forwarded {@code initialize}
     * negotiates by itself, and not the mod's: two MCMCP builds that frame the same way interoperate.
     */
    public static final int VERSION = 1;

    /** Client to server: protocol version and mod version. Sent once per connection. */
    public static final byte TYPE_HELLO = 1;

    /** Server to client: the answer to hello, granting or refusing. */
    public static final byte TYPE_WELCOME = 2;

    /** Either way: one MCP JSON-RPC message, wrapped with the client session it belongs to. */
    public static final byte TYPE_MCP = 3;

    /** Either way: the sender is closing a session (with a session key) or the whole link (without). */
    public static final byte TYPE_BYE = 4;

    /** Set on a message's last frame. */
    public static final int FLAG_FIN = 1;

    /** Set on every frame of a message whose payload was deflated before it was split. */
    public static final int FLAG_DEFLATE = 2;

    /**
     * Largest client-to-server frame, header included. Vanilla's limit is 32,767; the margin is for
     * the custom-payload wrapper and for never meeting the limit by an off-by-one.
     */
    public static final int MAX_C2S_FRAME = 32_000;

    /** Server-to-client frame size, header included. */
    public static final int S2C_FRAME = 64 * 1024;

    /** Messages smaller than this are sent as they are; compressing them gains nothing. */
    public static final int DEFLATE_THRESHOLD = 1024;

    /** Envelope field: the client session a message belongs to. */
    public static final String FIELD_SESSION = "s";

    /** Envelope field: the MCP message itself. */
    public static final String FIELD_MESSAGE = "m";

    private CompanionProtocol() {
    }
}
