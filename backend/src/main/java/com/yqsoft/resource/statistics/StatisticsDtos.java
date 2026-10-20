package com.yqsoft.resource.statistics;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public final class StatisticsDtos {
    private StatisticsDtos() {}

    public record Rebuild(
            @NotNull LocalDate from, @NotNull LocalDate to, @Size(max = 200) String reason) {}

    public record WeeklyCreate(
            @NotNull LocalDate weekStart,
            @NotBlank String expectedDataSeq,
            @Size(max = 3) List<@Size(max = 80) String> focus,
            Boolean forceNewRevision) {}
}
