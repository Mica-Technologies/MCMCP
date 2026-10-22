package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.micatechnologies.minecraft.mcmcp.tools.TwoPartRule.Verdict;
import org.junit.jupiter.api.Test;

class TwoPartRuleTest {

    @Test
    void aDoorsHalfPropertyIsAPairWithNoOtherEvidence() {
        assertEquals(Verdict.PAIR, TwoPartRule.decide(false, false, true, false, false, false));
    }

    @Test
    void aBedIsAPair() {
        assertEquals(Verdict.PAIR, TwoPartRule.decide(false, false, false, true, false, false));
    }

    @Test
    void aStoredTopFlagAloneIsOnlySuspectedLikeACableTrayHungHigh() {
        assertEquals(Verdict.SUSPECTED, TwoPartRule.decide(false, false, false, false, true, false));
    }

    @Test
    void aStoredFlagWhoseUpperHalfDropsNothingIsAPairLikeTallFurniture() {
        assertEquals(Verdict.PAIR, TwoPartRule.decide(false, false, false, false, true, true));
    }

    @Test
    void aStoredFlagOnTheAllowListIsAPairWhateverItDrops() {
        assertEquals(Verdict.PAIR, TwoPartRule.decide(false, true, false, false, true, false));
    }

    @Test
    void theAllowListCannotPairABlockWithNoHalfToDerive() {
        assertEquals(Verdict.SINGLE, TwoPartRule.decide(false, true, false, false, false, false));
    }

    @Test
    void theDenyListWinsOverEveryOtherSign() {
        assertEquals(Verdict.SINGLE, TwoPartRule.decide(true, true, false, false, true, true));
        assertEquals(Verdict.SINGLE, TwoPartRule.decide(true, false, true, false, false, false));
    }

    @Test
    void aBlockWithNoHalfNoBedAndNoStoredFlagIsOneCell() {
        assertEquals(Verdict.SINGLE, TwoPartRule.decide(false, false, false, false, false, true));
    }
}
