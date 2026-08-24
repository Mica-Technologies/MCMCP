package com.micatechnologies.minecraft.mcmcp.link;

/**
 * A frame that could not be understood, or a handshake that did not go the way it must.
 *
 * <p>Checked, deliberately. Every caller is inside the link's own read loop, and the correct
 * response is always the same: log it, drop the connection, and let the backoff decide when to try
 * again. Making it unchecked would let it escape into a thread that has no such recovery.
 *
 * <p>This is a <em>transport</em> failure, not a tool failure. It never becomes a
 * {@code ToolResult.error} and never reaches a model — by the time one of these is thrown there is
 * no session left to answer on.
 */
public class LinkProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    public LinkProtocolException(String message) {
        super(message);
    }

    public LinkProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
