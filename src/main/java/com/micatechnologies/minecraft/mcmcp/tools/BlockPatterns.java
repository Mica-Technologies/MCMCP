package com.micatechnologies.minecraft.mcmcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matching block ids against a caller's list of patterns — {@code "minecraft:air"}, {@code "wool"},
 * {@code "csm:*alarm*"}.
 *
 * <p>Plain substring matching is the obvious rule and the wrong one: {@code "air"} is a substring of
 * {@code "minecraft:oak_stairs"}, so "everything except air" quietly dropped every staircase. So a
 * pattern matches whole ids only, and {@code *} is the way to ask for a substring:
 *
 * <ul>
 *   <li>{@code minecraft:wool:14} matches exactly that metadata;</li>
 *   <li>{@code minecraft:wool} matches every metadata of it;</li>
 *   <li>{@code wool}, with no namespace, matches that path in any namespace;</li>
 *   <li>{@code *alarm*} matches any id containing "alarm".</li>
 * </ul>
 *
 * <p>Case-insensitive. Imports no Minecraft class, so it is unit-tested directly.
 */
public final class BlockPatterns {

    private final List<Pattern> withNamespace = new ArrayList<>();
    private final List<Pattern> pathOnly = new ArrayList<>();

    private BlockPatterns(Collection<String> patterns) {
        for (String raw : patterns) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            (pattern.indexOf(':') >= 0 || pattern.indexOf('*') >= 0 ? withNamespace : pathOnly)
                .add(glob(pattern));
        }
    }

    public static BlockPatterns of(Collection<String> patterns) {
        return new BlockPatterns(patterns);
    }

    public boolean isEmpty() {
        return withNamespace.isEmpty() && pathOnly.isEmpty();
    }

    /**
     * @param id a block id as MCMCP reports one: {@code namespace:path}, with {@code :metadata}
     *           appended when it is not zero
     */
    public boolean matches(String id) {
        String full = id.toLowerCase(Locale.ROOT);
        int first = full.indexOf(':');
        int second = first < 0 ? -1 : full.indexOf(':', first + 1);
        String base = second < 0 ? full : full.substring(0, second);
        String path = first < 0 ? base : base.substring(first + 1);

        for (Pattern pattern : withNamespace) {
            if (pattern.matcher(full).matches() || pattern.matcher(base).matches()) {
                return true;
            }
        }
        for (Pattern pattern : pathOnly) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern glob(String pattern) {
        String[] pieces = pattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(pieces[i]));
        }
        return Pattern.compile(regex.toString());
    }
}
