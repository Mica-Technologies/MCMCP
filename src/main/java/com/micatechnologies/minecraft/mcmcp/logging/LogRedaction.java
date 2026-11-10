package com.micatechnologies.minecraft.mcmcp.logging;

import java.util.regex.Pattern;

/**
 * Takes network addresses out of a log line before it is served to a companion caller (#195).
 *
 * <p>A server's log names every player's address when they join and when they are dropped
 * ({@code Name[/203.0.113.7:51234] logged in}). The reason a connection was lost is the reason to read
 * the log through the companion; where the player connected from is not, and an operator on the
 * companion allowlist was never given it by the game.
 *
 * <p>A line is masked before a filter is applied to it, so a filter cannot be used to test for an
 * address either. No Minecraft class is named here.
 */
public final class LogRedaction {

    /** What an address is replaced with. */
    public static final String MASK = "<address>";

    /**
     * Four dotted numbers of up to three digits, standing alone: not part of a longer dotted number,
     * which is how a version (14.23.5.2860, 1.12.2.14.23) is told from an address.
     */
    private static final Pattern IPV4 = Pattern.compile(
        "(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9]|\\.[0-9])(?::[0-9]{1,5})?");

    /**
     * An IPv6 address as Java prints a socket's: after a slash, bracketed or not, with its port. Only
     * after a slash, because a bare run of hex and colons is also every timestamp in the file.
     */
    private static final Pattern IPV6 = Pattern.compile(
        "(?<=/)(?:\\[[0-9A-Fa-f:]*:[0-9A-Fa-f:]*(?:%[0-9A-Za-z]+)?\\](?::[0-9]{1,5})?"
            + "|[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,}[0-9]?(?:%[0-9A-Za-z]+)?)");

    private LogRedaction() {
    }

    /** {@code line} with every address in it replaced by {@link #MASK}. */
    public static String maskAddresses(String line) {
        if (line.indexOf('.') < 0 && line.indexOf('/') < 0) {
            return line;
        }
        return IPV6.matcher(IPV4.matcher(line).replaceAll(MASK)).replaceAll(MASK);
    }
}
