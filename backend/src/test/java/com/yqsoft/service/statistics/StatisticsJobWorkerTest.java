package com.yqsoft.service.statistics;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class StatisticsJobWorkerTest {
    @Test
    void sparseMovedRecordDatesDoNotExpandToUnboundedRange() throws Exception {
        var ranges =
                StatisticsJobWorker.dateRanges(
                        new ObjectMapper()
                                .readTree(
                                        "{\"dates\":[\"2024-01-01\",\"2026-10-18\",\"2024-01-01\"]}"));
        assertEquals(2, ranges.size());
        assertEquals(LocalDate.of(2024, 1, 1), ranges.getFirst()[0]);
        assertEquals(ranges.getFirst()[0], ranges.getFirst()[1]);
        assertEquals(LocalDate.of(2026, 10, 18), ranges.getLast()[0]);
    }
}
