package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.micatechnologies.minecraft.mcmcp.chunkload.LoadBudget;
import org.junit.jupiter.api.Test;

class WritePacingTest {

    @Test
    void aWriteWithinTheBudgetFitsWhetherPacedOrNot() {
        assertTrue(WritePacing.fits(400_000L, 600_000L, 1_000_000L, false));
        assertTrue(WritePacing.fits(400_000L, 600_000L, 1_000_000L, true));
    }

    @Test
    void aWritePastTheBudgetDoesNotFitUntilRoomFreesUp() {
        assertFalse(WritePacing.fits(400_001L, 600_000L, 1_000_000L, false));
        assertFalse(WritePacing.fits(400_001L, 600_000L, 1_000_000L, true));
    }

    @Test
    void aPacedWriteLargerThanTheWholeBudgetGoesThroughOnceTheMinuteIsClear() {
        assertFalse(WritePacing.fits(1L, 1_500_000L, 1_000_000L, true), "it waits for an empty window");
        assertTrue(WritePacing.fits(0L, 1_500_000L, 1_000_000L, true), "rather than for room that never comes");
    }

    @Test
    void anInteractiveWriteLargerThanTheWholeBudgetIsAlwaysRefused() {
        assertFalse(WritePacing.fits(0L, 1_500_000L, 1_000_000L, false));
    }

    @Test
    void aPacedWaitLooksAgainAtLeastEverySecondSoAPauseOrCancelLandsQuickly() {
        assertEquals(1000L, WritePacing.waitStep(45_000L));
        assertEquals(250L, WritePacing.waitStep(250L));
        assertTrue(WritePacing.waitStep(0L) > 0L, "and never spins");
    }

    @Test
    void theWaitUntilAWriteFitsEndsWhenEnoughOfTheMinuteHasAgedOut() {
        LoadBudget budget = new LoadBudget();
        budget.record("job:a", 600_000, 0L);
        budget.record("job:a", 400_000, 20_000L);
        // 300,000 more need the first 600,000 gone: they leave at 60 s.
        assertEquals(30_000L, budget.millisUntilFits("job:a", 300_000, 30_000L, 1_000_000));
        // 700,000 more need both gone.
        assertEquals(50_000L, budget.millisUntilFits("job:a", 700_000, 30_000L, 1_000_000));
        // Over the whole budget: until the window is empty, which the paced rule then lets through.
        assertEquals(50_000L, budget.millisUntilFits("job:a", 2_000_000, 30_000L, 1_000_000));
    }

    @Test
    void aCallerWithNothingWrittenWaitsForNothing() {
        LoadBudget budget = new LoadBudget();
        assertEquals(0L, budget.millisUntilFits("job:b", 900_000, 0L, 1_000_000));
        budget.record("job:b", 100_000, 0L);
        assertEquals(0L, budget.millisUntilFits("job:b", 900_000, 1_000L, 1_000_000));
    }
}
