package com.micatechnologies.minecraft.mcmcp.companion;

/**
 * A peer sent something that is not the companion protocol, or broke one of its limits.
 *
 * <p>The receiver's answer is always the same: drop that peer's companion state and log it. Never
 * kick the player — the companion is an add-on to their connection, not a reason to end it.
 */
public class CompanionProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    public CompanionProtocolException(String message) {
        super(message);
    }
}
