package com.micatechnologies.minecraft.mcmcp.logging;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Raises one log4j logger's level for a while and keeps what it writes in a {@link LogRing} (#136).
 * Mods trace at DEBUG (RCMC's {@code rcmc-tts} records every announcement it speaks), and the game's
 * log configuration keeps DEBUG out of latest.log, so without this the lines exist and nothing can
 * read them.
 *
 * <p>JVM-wide: a singleplayer client and its integrated server share one log4j, so they share these
 * captures. Imports no Minecraft class. Written against the log4j 2.8.1 that vanilla 1.12.2 ships as
 * well as the newer one the build compiles against: every call here exists in both.
 *
 * <p>The capture is a {@link LoggerConfig} for the logger's name, additive, so its lines still reach
 * the game's own appenders as before. If the configuration already had one, its level is raised and
 * put back; if not, MCMCP adds one and removes it again.
 */
public final class LogCapture {

    /** Loggers captured at once. Each holds a ring; a finished capture's ring is dropped first. */
    public static final int MAX_CAPTURES = 8;
    /** Lines kept per logger. */
    public static final int RING_LINES = 2000;
    /** Characters kept of one message, the throwable's description included. */
    public static final int MAX_MESSAGE_CHARS = 4000;
    public static final int MIN_SECONDS = 10;
    public static final int DEFAULT_SECONDS = 600;
    public static final int MAX_SECONDS = 3600;

    /** The levels a capture takes, quietest last. */
    public static final String[] LEVELS = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"};

    /** One logger's capture: the ring, and while it is active, what to put back. */
    private static final class Capture {
        final String logger;
        final LogRing ring = new LogRing(RING_LINES);
        final RingAppender appender;
        String level;
        /** The level the configuration had for this exact name, or null if MCMCP added the config. */
        @Nullable
        Level previous;
        boolean active;
        long expiresAtMillis;
        @Nullable
        ScheduledFuture<?> expiry;

        Capture(String logger) {
            this.logger = logger;
            this.appender = new RingAppender("MCMCP-capture-" + logger, ring);
            appender.start();
        }
    }

    /** Copies each event's text out at once: a garbage-free log4j reuses the event object. */
    private static final class RingAppender extends AbstractAppender {
        private final LogRing ring;

        @SuppressWarnings("deprecation")
        RingAppender(String name, LogRing ring) {
            super(name, null, null, true);
            this.ring = ring;
        }

        @Override
        public void append(LogEvent event) {
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            if (event.getThrown() != null) {
                message = message + " | " + event.getThrown();
            }
            if (message.length() > MAX_MESSAGE_CHARS) {
                message = message.substring(0, MAX_MESSAGE_CHARS) + "…";
            }
            ring.add(event.getTimeMillis(), event.getLevel().name(), event.getLoggerName(), event.getThreadName(),
                message);
        }
    }

    private static final Map<String, Capture> CAPTURES = new LinkedHashMap<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "MCMCP-log-capture");
        thread.setDaemon(true);
        return thread;
    });

    private LogCapture() {
    }

    /** The upper-cased level, or null if it is not one of {@link #LEVELS}. */
    @Nullable
    public static String parseLevel(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String level = text.trim().toUpperCase(Locale.ROOT);
        for (String known : LEVELS) {
            if (known.equals(level)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Sets {@code logger} to {@code level} for {@code seconds} and captures its lines, or extends a
     * capture already running. Returns an error message, or null on success.
     */
    @Nullable
    public static synchronized String start(String logger, String level, int seconds) {
        LoggerContext context = context();
        if (context == null) {
            return "This game's log4j is not log4j-core, so MCMCP cannot change a logger's level.";
        }
        Capture capture = CAPTURES.get(logger);
        if (capture == null) {
            if (CAPTURES.size() >= MAX_CAPTURES && !dropOneFinished()) {
                return "Already capturing " + MAX_CAPTURES + " loggers: " + String.join(", ", CAPTURES.keySet())
                    + ". Put one back with level 'default' first.";
            }
            capture = new Capture(logger);
            CAPTURES.put(logger, capture);
        }
        Configuration configuration = context.getConfiguration();
        Level target = Level.toLevel(level, Level.DEBUG);
        if (!capture.active) {
            LoggerConfig existing = configuration.getLoggers().get(logger);
            if (existing != null) {
                capture.previous = existing.getLevel();
                existing.addAppender(capture.appender, null, null);
                existing.setLevel(target);
            }
            else {
                capture.previous = null;
                LoggerConfig added = new LoggerConfig(logger, target, true);
                added.addAppender(capture.appender, null, null);
                configuration.addLogger(logger, added);
            }
            capture.active = true;
        }
        else {
            LoggerConfig own = configuration.getLoggers().get(logger);
            if (own != null) {
                own.setLevel(target);
            }
            if (capture.expiry != null) {
                capture.expiry.cancel(false);
            }
        }
        context.updateLoggers();
        capture.level = target.name();
        capture.expiresAtMillis = System.currentTimeMillis() + seconds * 1000L;
        final Capture scheduled = capture;
        capture.expiry = TIMER.schedule(() -> {
            synchronized (LogCapture.class) {
                if (scheduled.active && System.currentTimeMillis() >= scheduled.expiresAtMillis - 50) {
                    restore(scheduled);
                }
            }
        }, seconds, TimeUnit.SECONDS);
        return null;
    }

    /** Puts a logger's level back now. Its ring stays readable. False if it was not being captured. */
    public static synchronized boolean stop(String logger) {
        Capture capture = CAPTURES.get(logger);
        if (capture == null || !capture.active) {
            return false;
        }
        if (capture.expiry != null) {
            capture.expiry.cancel(false);
        }
        restore(capture);
        return true;
    }

    /** The ring for a logger captured at any point since the game started, or null. */
    @Nullable
    public static synchronized LogRing ring(String logger) {
        Capture capture = CAPTURES.get(logger);
        return capture == null ? null : capture.ring;
    }

    /** Whether a logger's level is raised right now. */
    public static synchronized boolean isActive(String logger) {
        Capture capture = CAPTURES.get(logger);
        return capture != null && capture.active;
    }

    /** Every capture, as {logger, level, active, secondsLeft, previousLevel, lines}, oldest first. */
    public static synchronized JsonArray describe() {
        JsonArray out = new JsonArray();
        long now = System.currentTimeMillis();
        for (Capture capture : CAPTURES.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("logger", capture.logger);
            row.addProperty("active", capture.active);
            if (capture.active) {
                row.addProperty("level", capture.level);
                row.addProperty("secondsLeft", Math.max(0, (capture.expiresAtMillis - now + 999) / 1000));
                row.addProperty("restoresTo", capture.previous == null ? "inherited" : capture.previous.name());
            }
            row.addProperty("lines", capture.ring.written());
            out.add(row);
        }
        return out;
    }

    private static void restore(Capture capture) {
        capture.active = false;
        capture.expiry = null;
        LoggerContext context = context();
        if (context == null) {
            return;
        }
        Configuration configuration = context.getConfiguration();
        LoggerConfig own = configuration.getLoggers().get(capture.logger);
        if (own != null) {
            if (capture.previous == null) {
                configuration.removeLogger(capture.logger);
            }
            else {
                own.removeAppender(capture.appender.getName());
                own.setLevel(capture.previous);
            }
        }
        context.updateLoggers();
    }

    private static boolean dropOneFinished() {
        for (Iterator<Capture> it = CAPTURES.values().iterator(); it.hasNext(); ) {
            if (!it.next().active) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static LoggerContext context() {
        Object context = LogManager.getContext(false);
        return context instanceof LoggerContext ? (LoggerContext) context : null;
    }
}
