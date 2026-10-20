package com.yqsoft.service.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.plan.PlanDtos.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.UUID;

class PlanTimeValidationTest {
    @Test
    void nonexistentDaylightSavingTimeIsNotSilentlyShifted() {
        assertThrows(
                BusinessException.class,
                () ->
                        PlanService.resolveLocal(
                                LocalDate.of(2026, 3, 8),
                                LocalTime.of(2, 30),
                                ZoneId.of("America/New_York")));
    }

    @Test
    void ambiguousDaylightSavingTimeRequiresExplicitChoice() {
        assertThrows(
                BusinessException.class,
                () ->
                        PlanService.resolveLocal(
                                LocalDate.of(2026, 11, 1),
                                LocalTime.of(1, 30),
                                ZoneId.of("America/New_York")));
    }

    @Test
    void normalLocalTimeResolvesAgainstTheIanaZone() {
        assertEquals(
                Instant.parse("2026-10-18T01:00:00Z"),
                PlanService.resolveLocal(
                        LocalDate.of(2026, 10, 18),
                        LocalTime.of(9, 0),
                        ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void plannedDurationCannotExceedItsSchedulingWindow() {
        TaskSnapshotInput input =
                new TaskSnapshotInput(
                        UUID.randomUUID(),
                        null,
                        null,
                        "散步",
                        TaskType.ACTIVITY,
                        Instant.parse("2026-10-18T01:00:00Z"),
                        Instant.parse("2026-10-18T01:10:00Z"),
                        20,
                        "Asia/Shanghai",
                        false,
                        3,
                        0,
                        null,
                        null,
                        null,
                        null);
        assertThrows(BusinessException.class, () -> PlanService.validateTaskTime(input));
    }
}
