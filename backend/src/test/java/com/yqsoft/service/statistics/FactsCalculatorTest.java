package com.yqsoft.service.statistics;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class FactsCalculatorTest {
    @Test
    void cancelledTasksRemainVisibleAndOutsideExecutionDenominator() {
        var counts = new FactsCalculator.TaskCounts(1, 2, 1, 5);
        assertEquals(4, counts.denominator());
        assertEquals(5, counts.cancelled());
    }

    private final FactsCalculator.TaskCounts noTasks = new FactsCalculator.TaskCounts(0, 0, 0);

    @Test
    void missingMeasurementsRemainNullAndSkippedTasksStayInDenominator() {
        var result = FactsCalculator.calculate(List.of(), new FactsCalculator.TaskCounts(1, 2, 1));
        assertNull(result.mainSleepMinutes());
        assertNull(result.activityMinutes());
        assertNull(result.energyMedian());
        assertNull(result.energySum());
        assertEquals(4, result.tasks().denominator());
        assertEquals(2, result.tasks().completed());
    }

    @Test
    void overlappingActivityIsExcludedRatherThanDoubleCountedOrReplacedByPlannedDuration() {
        var result =
                FactsCalculator.calculate(
                        List.of(
                                sample(1, "ACTIVITY", "10:00", "10:30", 20, null, null),
                                sample(2, "ACTIVITY", "10:20", "10:50", 15, null, null),
                                sample(3, "ACTIVITY", "11:00", "11:45", 25, null, null)),
                        noTasks);
        assertEquals(25, result.activityMinutes());
        assertEquals(List.of(1L, 2L), result.excludedRecordIds());
        assertTrue(result.qualityFlags().contains("ACTIVITY_OVERLAP_EXCLUDED"));
    }

    @Test
    void ambiguousMainSleepIsNotSummed() {
        var result =
                FactsCalculator.calculate(
                        List.of(
                                sample(1, "SLEEP", "00:00", "08:00", null, "MAIN", null),
                                sample(2, "SLEEP", "01:00", "07:00", null, "MAIN", null)),
                        noTasks);
        assertNull(result.mainSleepMinutes());
        assertTrue(result.qualityFlags().contains("MAIN_SLEEP_CONFLICT"));
    }

    @Test
    void napsUseUnionAndSubtractAnyMainSleepOverlap() {
        var result =
                FactsCalculator.calculate(
                        List.of(
                                sample(1, "SLEEP", "00:00", "08:00", null, "MAIN", null),
                                sample(2, "SLEEP", "07:30", "08:30", null, "NAP", null),
                                sample(3, "SLEEP", "08:15", "09:00", null, "NAP", null)),
                        noTasks);
        assertEquals(480, result.mainSleepMinutes());
        assertEquals(60, result.napMinutes());
    }

    @Test
    void subjectiveEnergyMedianRetainsItsSampleCount() {
        var result =
                FactsCalculator.calculate(
                        List.of(
                                sample(1, "ENERGY", "08:00", null, null, null, 1),
                                sample(2, "ENERGY", "18:00", null, null, null, 4)),
                        noTasks);
        assertEquals(2.5, result.energyMedian());
        assertEquals(2, result.energySamples());
        assertEquals(5, result.energySum());
    }

    private FactsCalculator.Sample sample(
            long id,
            String type,
            String start,
            String end,
            Integer actualMinutes,
            String sleepKind,
            Integer energy) {
        return new FactsCalculator.Sample(
                id,
                type,
                instant(start),
                end == null ? null : instant(end),
                actualMinutes,
                sleepKind,
                energy,
                "UTC");
    }

    private Instant instant(String time) {
        return Instant.parse("2026-10-18T" + time + ":00.000Z");
    }
}
