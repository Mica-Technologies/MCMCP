package com.micatechnologies.minecraft.mcmcp.link;

/**
 * How long to wait before the next reconnect attempt.
 *
 * <p>Exponential from an initial delay to a ceiling, reset on every successful handshake. Its own
 * class rather than two fields in the transport so the progression can be asserted without opening
 * a socket — the behaviour that matters here is "does it stop growing, and does it actually reset",
 * and both are easy to get subtly wrong and impossible to notice at runtime.
 *
 * <h2>Why it climbs at all</h2>
 *
 * The resting state of most installs is a game with no orchestrator running. Dialling a closed port
 * once a second forever costs almost nothing in isolation and is genuinely wasteful across three
 * instances left open all day, so the gap grows. It stops growing at a ceiling rather than
 * continuing, because the event this is waiting for is a person starting an app, and half a minute
 * is about as long as anyone should sit looking at a roster wondering why their game has not
 * appeared.
 *
 * <p>Not thread-safe, and does not need to be: only the connector thread ever touches one.
 */
public final class LinkBackoff {

    private final long initialMillis;
    private final long maxMillis;

    private long currentMillis;

    public LinkBackoff(long initialMillis, long maxMillis) {
        this.initialMillis = Math.max(0L, initialMillis);
        // A ceiling below the floor would make the delay run backwards on the first doubling.
        this.maxMillis = Math.max(this.initialMillis, maxMillis);
        this.currentMillis = this.initialMillis;
    }

    /** The delay to use now, after which the next one is longer. */
    public long nextDelayMillis() {
        long delay = currentMillis;
        // Compared before doubling rather than after. Doubling first and clamping second overflows
        // for a large ceiling, and the wrapped value is negative — which a scheduler runs
        // immediately, quietly turning the backoff into a spin against a closed port.
        currentMillis = currentMillis > maxMillis / 2L ? maxMillis : currentMillis * 2L;
        return delay;
    }

    /** The delay {@link #nextDelayMillis()} would return, without advancing. */
    public long peekMillis() {
        return currentMillis;
    }

    /** Back to the initial delay. Called on every successful handshake. */
    public void reset() {
        currentMillis = initialMillis;
    }

    public long getInitialMillis() {
        return initialMillis;
    }

    public long getMaxMillis() {
        return maxMillis;
    }
}
