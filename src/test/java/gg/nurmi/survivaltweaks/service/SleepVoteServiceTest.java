package gg.nurmi.survivaltweaks.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleepVoteServiceTest {

    @Test
    void nobodyAwakeMeansNobodyIsRequired() {
        assertEquals(0, SleepVoteService.requiredSleepers(0, 50));
        assertEquals(0, SleepVoteService.requiredSleepers(0, 100));
    }

    @Test
    void aSinglePlayerAlwaysNeedsToSleepThemselves() {
        assertEquals(1, SleepVoteService.requiredSleepers(1, 1));
        assertEquals(1, SleepVoteService.requiredSleepers(1, 50));
        assertEquals(1, SleepVoteService.requiredSleepers(1, 100));
    }

    @Test
    void theThresholdRoundsUpSoAMajorityIsNeverUndercounted() {
        // 50% of 5 is 2.5 — three players must sleep, not two.
        assertEquals(3, SleepVoteService.requiredSleepers(5, 50));
        assertEquals(2, SleepVoteService.requiredSleepers(4, 50));
        assertEquals(2, SleepVoteService.requiredSleepers(3, 50));
        assertEquals(4, SleepVoteService.requiredSleepers(10, 35));
    }

    @Test
    void exactDivisionsDoNotGainAnExtraSleeper() {
        assertEquals(5, SleepVoteService.requiredSleepers(10, 50));
        assertEquals(1, SleepVoteService.requiredSleepers(10, 10));
        assertEquals(10, SleepVoteService.requiredSleepers(10, 100));
    }

    @Test
    void aVeryLowPercentageStillRequiresOneSleeper() {
        assertEquals(1, SleepVoteService.requiredSleepers(20, 1));
        assertEquals(1, SleepVoteService.requiredSleepers(99, 1));
        assertEquals(2, SleepVoteService.requiredSleepers(101, 1));
    }

    @Test
    void largePopulationsDoNotOverflowTheIntermediateMultiplication() {
        assertEquals(Integer.MAX_VALUE / 100 + 1, SleepVoteService.requiredSleepers(Integer.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, SleepVoteService.requiredSleepers(Integer.MAX_VALUE, 100));
    }

    @Test
    void impossibleInputsAreRejectedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> SleepVoteService.requiredSleepers(-1, 50));
        assertThrows(IllegalArgumentException.class, () -> SleepVoteService.requiredSleepers(5, 0));
        assertThrows(IllegalArgumentException.class, () -> SleepVoteService.requiredSleepers(5, 101));
    }
}
