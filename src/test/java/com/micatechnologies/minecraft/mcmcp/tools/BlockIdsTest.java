package com.micatechnologies.minecraft.mcmcp.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The suggestion ranking behind an unresolvable block id.
 *
 * <p>Only the ranking is covered here. Resolution itself asks the live Forge registry, which does
 * not exist outside a game, and the behaviour that matters there -- an unregistered id returning
 * {@code null} rather than the registry's air default -- is a one-line {@code containsKey} guard
 * verified in game.
 */
class BlockIdsTest {

    /** A realistic slice of a pack's ids: short, similar to each other, and unlike their names. */
    private static final List<String> CANDIDATES = Arrays.asList(
        "tlitevertwiremount",
        "tlitehorzwiremount",
        "tlborderblackblack",
        "spanwireanchor",
        "stone");

    @Test
    @DisplayName("offers the near miss for a typo")
    void offersTheNearMissForATypo() {
        List<String> ranked = BlockIds.rank("csm", "tlitevertwiremont", CANDIDATES);

        assertThat(ranked.get(0), is("csm:tlitevertwiremount"));
    }

    @Test
    @DisplayName("ranks the closer of two similar ids first")
    void ranksTheCloserOfTwoSimilarIdsFirst() {
        List<String> ranked = BlockIds.rank("csm", "tlitehorzwiremont", CANDIDATES);

        assertThat(ranked, contains("csm:tlitehorzwiremount", "csm:tlitevertwiremount"));
    }

    @Test
    @DisplayName("points at the family when the id was invented from a block's name")
    void pointsAtTheFamilyWhenTheIdWasInventedFromABlocksName() {
        // The id that started all this: a plausible-sounding name for a block actually registered
        // as 'tlitevertwiremount'. No amount of string distance recovers that, but ids in a pack
        // are prefixed by family, and the shared 'spanwire' prefix is enough to hand back a real
        // block from the same system -- which is where someone would look next anyway.
        List<String> ranked = BlockIds.rank("csm", "spanwirehangermount", CANDIDATES);

        assertThat(ranked, contains("csm:spanwireanchor"));
    }

    @Test
    @DisplayName("says nothing rather than guessing at an unrelated id")
    void saysNothingRatherThanGuessingAtAnUnrelatedId() {
        // Nothing here shares anything with the query. Five least-unlike ids would be worse than
        // an honest silence, so the similarity floor keeps them out.
        List<String> ranked = BlockIds.rank("csm", "diamondchandelier", CANDIDATES);

        assertThat(ranked, is(empty()));
    }

    @Test
    @DisplayName("returns the exact id when one matches")
    void returnsTheExactIdWhenOneMatches() {
        List<String> ranked = BlockIds.rank("csm", "spanwireanchor", CANDIDATES);

        assertThat(ranked.get(0), is("csm:spanwireanchor"));
    }

    @Test
    @DisplayName("caps how many suggestions come back")
    void capsHowManySuggestionsComeBack() {
        List<String> manyNearMisses = Arrays.asList(
            "signalbackplate1", "signalbackplate2", "signalbackplate3",
            "signalbackplate4", "signalbackplate5", "signalbackplate6");

        List<String> ranked = BlockIds.rank("csm", "signalbackplate0", manyNearMisses);

        assertThat(ranked, hasSize(5));
    }

    @Test
    @DisplayName("survives an empty registry namespace")
    void survivesAnEmptyRegistryNamespace() {
        List<String> ranked = BlockIds.rank("csm", "anything", Collections.<String>emptyList());

        assertThat(ranked, is(empty()));
    }
}
