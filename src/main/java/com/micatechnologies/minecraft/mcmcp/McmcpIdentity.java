package com.micatechnologies.minecraft.mcmcp;

import java.security.SecureRandom;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Who this game instance is, as far as an orchestrator is concerned.
 *
 * <p>MCMCP's original design needed no identity at all: one game, one port, and whoever configured
 * the port already knew which game was behind it. Running several instances at once breaks that —
 * "the client on 25587" is not something a person or a model can reason about, and it stops being
 * true the moment the ports are reassigned.
 *
 * <p>Three fields, and the distinction between them matters:
 *
 * <ul>
 *   <li>{@link #getInstanceId()} is the <b>key</b>. Generated once and never changed, it is what an
 *       orchestrator stores an approval against. It is deliberately <em>not</em> derived from the
 *       game directory: an instance that gets moved or renamed is still the same instance, and
 *       re-approving it because its folder moved would train a person to click through the one
 *       prompt that is supposed to mean something.</li>
 *   <li>{@link #getInstanceSecret()} is what makes trust-on-first-use mean anything. Without it,
 *       any process on the machine could open a link, claim an approved id and be believed. It is
 *       never logged, never printed by a command, and never sent anywhere but the orchestrator
 *       handshake.</li>
 *   <li>{@link #getInstanceName()} is the <b>label</b>, and is the only one a human should ever
 *       read. It defaults to the game directory's name because that is usually what the person
 *       calls this instance anyway, and it is theirs to change.</li>
 * </ul>
 *
 * <p>No Minecraft imports: this is constructed from the config file during {@code preInit} and is
 * consumed by the link layer, which has the same rule for the same reason — the handshake must be
 * unit-testable without a game.
 */
public final class McmcpIdentity {

    /** Bytes of entropy in a generated secret. 256 bits, because it authenticates a control channel. */
    private static final int SECRET_BYTES = 32;

    /** Hex characters of randomness appended to a generated id, to survive a copied game directory. */
    private static final int ID_SUFFIX_BYTES = 3;

    /**
     * Longest slug taken from a directory name.
     *
     * <p>Instance directories are routinely named things like
     * {@code ATM9 - Copy (2) - modB testing}. The id shows up in log lines, tool errors and a
     * roster, and a 60-character id helps nobody read any of them.
     */
    private static final int MAX_SLUG_LENGTH = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String instanceId;
    private final String instanceSecret;
    private final String instanceName;

    public McmcpIdentity(String instanceId, String instanceSecret, String instanceName) {
        this.instanceId = instanceId;
        this.instanceSecret = instanceSecret;
        this.instanceName = instanceName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    /** Never log this, never print it from a command, never put it in a tool result. */
    public String getInstanceSecret() {
        return instanceSecret;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public boolean hasSecret() {
        return instanceSecret != null && !instanceSecret.isEmpty();
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    /**
     * Builds an id from a game directory name plus a random suffix.
     *
     * <p>The suffix is not decoration. Copying a whole instance folder is how people make a test
     * variant of a pack, and two instances both calling themselves {@code atm9} would collide in an
     * orchestrator's approval store — where the consequence is one instance inheriting another's
     * approval. The generated id is written to the config on first run, so a copy made <em>after</em>
     * that point carries the same id in its copied config; that case is caught by the orchestrator
     * noticing a known id arriving from a new directory, which is exactly why the handshake sends
     * the directory too.
     */
    public static String generateInstanceId(@Nullable String gameDirectoryName) {
        String slug = slugify(gameDirectoryName);
        return slug + "-" + randomHex(ID_SUFFIX_BYTES);
    }

    public static String generateSecret() {
        return randomHex(SECRET_BYTES);
    }

    /**
     * Reduces a directory name to something usable as an identifier.
     *
     * <p>Lowercase, ASCII alphanumerics only, runs of anything else collapsed to a single hyphen.
     * A name with nothing usable in it — an all-punctuation folder, or a non-Latin script that
     * leaves no ASCII behind — falls back to {@code instance} rather than producing an empty or
     * hyphen-only id.
     */
    public static String slugify(@Nullable String raw) {
        if (raw == null) {
            return "instance";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder(lower.length());
        boolean pendingHyphen = false;
        for (int i = 0; i < lower.length() && slug.length() < MAX_SLUG_LENGTH; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingHyphen && slug.length() > 0) {
                    slug.append('-');
                }
                pendingHyphen = false;
                slug.append(c);
            }
            else {
                // Collapse a run of separators into at most one hyphen, and never emit a leading
                // one. The hyphen is only committed once a following alphanumeric proves it sits
                // between two segments, which also means no trailing hyphen can survive.
                pendingHyphen = true;
            }
        }
        return slug.length() == 0 ? "instance" : slug.toString();
    }

    private static String randomHex(int byteCount) {
        byte[] entropy = new byte[byteCount];
        RANDOM.nextBytes(entropy);
        StringBuilder hex = new StringBuilder(byteCount * 2);
        for (byte b : entropy) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Override
    public String toString() {
        // Secret deliberately absent: this object ends up in log lines and exception messages.
        return "McmcpIdentity{id=" + instanceId + ", name=" + instanceName + "}";
    }
}
