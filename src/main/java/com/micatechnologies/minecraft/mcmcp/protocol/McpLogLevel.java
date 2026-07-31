package com.micatechnologies.minecraft.mcmcp.protocol;

import javax.annotation.Nullable;

/**
 * The syslog severity ladder MCP uses for {@code logging/setLevel} and {@code notifications/message}.
 *
 * <p>Ordinal order is severity order, least severe first, which is what makes
 * {@link #isAtLeast} a simple comparison. The names are the exact wire strings — MCP spells them
 * lowercase and includes {@code notice}, {@code critical}, {@code alert} and {@code emergency},
 * which have no Log4j equivalent, so {@link #toLog4jLevelName} folds them onto the nearest level
 * the game's logger actually has.
 */
public enum McpLogLevel {

    DEBUG("debug"),
    INFO("info"),
    NOTICE("notice"),
    WARNING("warning"),
    ERROR("error"),
    CRITICAL("critical"),
    ALERT("alert"),
    EMERGENCY("emergency");

    private final String wireName;

    McpLogLevel(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * Parses a wire level name, falling back to {@code fallback} for anything unrecognised.
     *
     * <p>Lenient rather than throwing: an unknown level in {@code logging/setLevel} should leave the
     * session logging at its current verbosity, not fail the request and leave the client unsure
     * whether the level changed.
     */
    public static McpLogLevel fromWireName(@Nullable String name, McpLogLevel fallback) {
        if (name == null) {
            return fallback;
        }
        String normalised = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (McpLogLevel level : values()) {
            if (level.wireName.equals(normalised)) {
                return level;
            }
        }
        return fallback;
    }

    /** True when this level is at least as severe as {@code threshold} and should therefore be sent. */
    public boolean isAtLeast(McpLogLevel threshold) {
        return ordinal() >= threshold.ordinal();
    }

    /** Nearest Log4j level name, for mirroring MCP log records into the game log. */
    public String toLog4jLevelName() {
        switch (this) {
            case DEBUG:
                return "DEBUG";
            case INFO:
            case NOTICE:
                return "INFO";
            case WARNING:
                return "WARN";
            default:
                return "ERROR";
        }
    }
}
