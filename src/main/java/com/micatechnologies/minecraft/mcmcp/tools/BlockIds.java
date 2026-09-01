package com.micatechnologies.minecraft.mcmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Turns a namespaced block id from a tool argument into a {@link Block}, and says something useful
 * when it cannot.
 *
 * <h2>Why this exists</h2>
 *
 * Forge's block registry is a <em>defaulted</em> registry: it is built with {@code air} as its
 * default value, so {@code ForgeRegistries.BLOCKS.getValue(id)} does not return {@code null} for an
 * id nobody registered -- it returns {@code minecraft:air}. Every caller here had written the
 * obvious {@code if (block == null)} guard, and every one of those guards was dead code.
 *
 * <p>The failure that produced was silent and, worse, plausible. A search for a misspelled id came
 * back as a search for air: {@code blocksScanned} equal to {@code matches}, hundreds of contiguous
 * "hits" hanging in the sky, and a confident report that a block was somewhere it has never been.
 * On the writing side the same mistake is destructive -- a fill with a mistyped id resolves to air
 * and quietly erases the region it was meant to build.
 *
 * <p>So resolution goes through {@link #resolve}, which asks {@code containsKey} first. Air remains
 * perfectly addressable; what is no longer possible is <em>accidentally</em> addressing it.
 */
public final class BlockIds {

    /** How many near-miss ids to offer back when an id does not resolve. */
    private static final int MAX_SUGGESTIONS = 5;

    /** Ignore wild guesses: a suggestion has to be at least this similar to be worth printing. */
    private static final double MIN_SIMILARITY = 0.5D;

    private BlockIds() {
    }

    /**
     * Resolves a namespaced block id.
     *
     * @param id the id, e.g. {@code minecraft:stone}. An unqualified name is read as
     *     {@code minecraft:}, matching how the game parses ids everywhere else.
     *
     * @return the registered block, or {@code null} if nothing is registered under that id. Unlike
     *     the registry's own lookup, this never substitutes air for an id it does not know.
     */
    @Nullable
    public static Block resolve(String id) {
        if (id == null) {
            return null;
        }
        final String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        final ResourceLocation location = new ResourceLocation(trimmed);
        if (!ForgeRegistries.BLOCKS.containsKey(location)) {
            return null;
        }
        return ForgeRegistries.BLOCKS.getValue(location);
    }

    /**
     * Explains an id that did not resolve, in the terms most likely to get the next call right.
     *
     * <p>If the namespace is unknown the problem is almost always a mod that is not installed, so
     * it says that rather than offering block names. If the namespace is present the problem is
     * usually a guessed name, so it offers the closest ids actually registered under it -- ids
     * rarely read the way anyone expects, and guessing one is what made this worth writing.
     *
     * @param id the id that failed to resolve.
     * @param usage what the id was wanted for, folded into the message, e.g. {@code "search for"}.
     *
     * @return the message body for a {@code ToolResult.error}.
     */
    public static String describeUnknown(String id, String usage) {
        final String trimmed = id == null ? "" : id.trim();
        final StringBuilder message = new StringBuilder();
        message.append("No block is registered with the id '").append(trimmed)
            .append("', so there is nothing to ").append(usage).append(". ");

        final ResourceLocation location = new ResourceLocation(trimmed.isEmpty() ? "air" : trimmed);
        final String namespace = location.getNamespace();

        if (!namespaceExists(namespace)) {
            message.append("Nothing at all is registered under the '").append(namespace)
                .append("' namespace, so that mod is probably not loaded in this instance. ")
                .append("Loaded namespaces: ").append(joinNamespaces()).append(". ")
                .append("Use game_list_mods to see what is present.");
            return message.toString();
        }

        final List<String> suggestions = suggest(namespace, location.getPath());
        if (suggestions.isEmpty()) {
            message.append("The '").append(namespace)
                .append("' namespace is loaded but has nothing by that name, and nothing close to ")
                .append("it either. Ids are often not what the block is called in game.");
        }
        else {
            message.append("Did you mean: ").append(join(suggestions))
                .append("? Ids are often not what the block is called in game.");
        }
        return message.toString();
    }

    private static boolean namespaceExists(String namespace) {
        for (ResourceLocation key : ForgeRegistries.BLOCKS.getKeys()) {
            if (key.getNamespace().equals(namespace)) {
                return true;
            }
        }
        return false;
    }

    private static String joinNamespaces() {
        final Set<String> namespaces = new TreeSet<>();
        for (ResourceLocation key : ForgeRegistries.BLOCKS.getKeys()) {
            namespaces.add(key.getNamespace());
        }
        return join(namespaces);
    }

    private static String join(Iterable<String> parts) {
        final StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(part);
        }
        return joined.toString();
    }

    /** The closest ids registered within one namespace, best first. */
    private static List<String> suggest(String namespace, String path) {
        final List<String> candidates = new ArrayList<>();
        for (ResourceLocation key : ForgeRegistries.BLOCKS.getKeys()) {
            if (key.getNamespace().equals(namespace)) {
                candidates.add(key.getPath());
            }
        }
        return rank(namespace, path, candidates);
    }

    /**
     * Ranks candidate paths in one namespace against a query, best first.
     *
     * <p>Separated from the registry walk so it can be tested without a game. Suggesting nothing is
     * a valid answer and the common one for a genuinely wrong guess: an id invented from a block's
     * display name shares almost no characters with the real one, and offering the five
     * least-unlike ids in the pack would be worse than saying nothing.
     *
     * @param namespace the namespace to prefix onto each result.
     * @param path the path that failed to resolve.
     * @param candidatePaths every path registered under {@code namespace}.
     *
     * @return at most {@link #MAX_SUGGESTIONS} full ids, closest first, all above
     *     {@link #MIN_SIMILARITY}.
     */
    static List<String> rank(String namespace, String path, Iterable<String> candidatePaths) {
        final List<Scored> scored = new ArrayList<>();
        for (String candidate : candidatePaths) {
            final double similarity = similarity(path, candidate);
            if (similarity >= MIN_SIMILARITY) {
                scored.add(new Scored(namespace + ":" + candidate, similarity));
            }
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored left, Scored right) {
                return Double.compare(right.similarity, left.similarity);
            }
        });

        final List<String> best = new ArrayList<>();
        for (Scored candidate : scored) {
            if (best.size() >= MAX_SUGGESTIONS) {
                break;
            }
            best.add(candidate.id);
        }
        return best;
    }

    /** Edit distance folded to 0..1, where 1 is an exact match. */
    private static double similarity(String left, String right) {
        final int longest = Math.max(left.length(), right.length());
        if (longest == 0) {
            return 1.0D;
        }
        return 1.0D - ((double) editDistance(left, right) / longest);
    }

    /** Levenshtein distance, single-row so a few thousand candidates stay cheap. */
    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                final int substitution =
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static final class Scored {

        final String id;
        final double similarity;

        Scored(String id, double similarity) {
            this.id = id;
            this.similarity = similarity;
        }
    }
}
