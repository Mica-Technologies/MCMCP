package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoadBudgetRefundTest {

    @Test
    void whatWasChargedForWorkThatDidNotHappenIsGivenBack() {
        LoadBudget budget = new LoadBudget();
        budget.record("job:a", 4000, 1_000L);
        budget.record("job:a", 2500, 2_000L);
        budget.record("job:b", 2500, 2_000L);
        budget.unrecord("job:a", 2500);
        assertEquals(4000, budget.lastMinute("job:a", 3_000L));
        assertEquals(2500, budget.lastMinute("job:b", 3_000L), "another caller's record of the same size stays");
        assertEquals(6500, budget.lastMinute(3_000L));
    }

    @Test
    void aRefundOfSomethingNeverChargedChangesNothing() {
        LoadBudget budget = new LoadBudget();
        budget.record("job:a", 4000, 1_000L);
        budget.unrecord("job:a", 123);
        budget.unrecord("job:c", 4000);
        budget.unrecord("job:a", 0);
        assertEquals(4000, budget.lastMinute("job:a", 2_000L));
        assertEquals(4000, budget.lastMinute(2_000L));
    }
}
