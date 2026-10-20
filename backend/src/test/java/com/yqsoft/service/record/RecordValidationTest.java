package com.yqsoft.service.record;

import static org.junit.jupiter.api.Assertions.*;

import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.record.RecordDtos.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

class RecordValidationTest {
    @Test
    void sleepBelongsToWakeDayAcrossMidnight() {
        RecordCreate record = sleep(LocalDate.of(2026, 10, 18), 480);
        assertDoesNotThrow(() -> RecordService.validateRecord(record));
        assertThrows(
                BusinessException.class,
                () -> RecordService.validateRecord(sleep(LocalDate.of(2026, 10, 17), 480)));
    }

    @Test
    void offsetMustMatchTheInstantAndIanaZone() {
        assertThrows(
                BusinessException.class,
                () -> RecordService.validateRecord(sleep(LocalDate.of(2026, 10, 18), 0)));
    }

    @Test
    void actualActivityCannotBorrowMoreMinutesThanItsInterval() {
        RecordCreate record =
                new RecordCreate(
                        UUID.randomUUID(),
                        RecordType.ACTIVITY,
                        Instant.parse("2026-10-17T04:00:00Z"),
                        Instant.parse("2026-10-17T04:20:00Z"),
                        "Asia/Shanghai",
                        LocalDate.of(2026, 10, 17),
                        480,
                        null,
                        List.of(),
                        new ActivityDetailInput(
                                "ACTIVITY", ActivityType.WALK, 30, null, null, null, null));
        assertThrows(BusinessException.class, () -> RecordService.validateRecord(record));
    }

    @Test
    void recordHeaderCannotHideADifferentDetailType() {
        RecordCreate record =
                new RecordCreate(
                        UUID.randomUUID(),
                        RecordType.MEAL,
                        Instant.parse("2026-10-17T04:00:00Z"),
                        null,
                        "Asia/Shanghai",
                        LocalDate.of(2026, 10, 17),
                        480,
                        null,
                        List.of(),
                        new EnergyDetailInput("ENERGY", 3, null, null));
        assertThrows(BusinessException.class, () -> RecordService.validateRecord(record));
    }

    @Test
    void subjectiveEnergyIsAnInstantRatherThanAnInterval() {
        RecordCreate record =
                new RecordCreate(
                        UUID.randomUUID(),
                        RecordType.ENERGY,
                        Instant.parse("2026-10-17T04:00:00Z"),
                        Instant.parse("2026-10-17T04:10:00Z"),
                        "Asia/Shanghai",
                        LocalDate.of(2026, 10, 17),
                        480,
                        null,
                        List.of(),
                        new EnergyDetailInput("ENERGY", 3, null, null));
        assertThrows(BusinessException.class, () -> RecordService.validateRecord(record));
    }

    private RecordCreate sleep(LocalDate date, int offset) {
        return new RecordCreate(
                UUID.randomUUID(),
                RecordType.SLEEP,
                Instant.parse("2026-10-17T15:00:00Z"),
                Instant.parse("2026-10-17T23:00:00Z"),
                "Asia/Shanghai",
                date,
                offset,
                null,
                List.of(),
                new SleepDetailInput("SLEEP", null, "MAIN", null));
    }
}
