package com.yqsoft.controller.statistics;

import com.yqsoft.resource.common.*;
import com.yqsoft.resource.statistics.StatisticsDtos;
import com.yqsoft.service.ai.AiService;
import com.yqsoft.service.statistics.StatisticsService;
import com.yqsoft.service.statistics.WeeklyReviewService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class StatisticsController {
    private final StatisticsService statistics;
    private final WeeklyReviewService weekly;
    private final AiService ai;

    public StatisticsController(
            StatisticsService statistics, WeeklyReviewService weekly, AiService ai) {
        this.statistics = statistics;
        this.weekly = weekly;
        this.ai = ai;
    }

    @GetMapping("/statistics/trends")
    public Object trends(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam List<String> metrics,
            @RequestParam(required = false) String timeZone) {
        return ApiResponse.ok(statistics.trends(from, to, metrics, timeZone));
    }

    @GetMapping("/statistics/days/{date}")
    public Object daily(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String metricDefinitionVersion) {
        return ApiResponse.ok(statistics.daily(date, metricDefinitionVersion));
    }

    @PostMapping("/statistics/rebuild")
    public Object rebuild(@Valid @RequestBody StatisticsDtos.Rebuild request) {
        return ResponseEntity.accepted().body(ApiResponse.ok(statistics.requestRebuild(request)));
    }

    @GetMapping("/weekly-reviews")
    public Object reviews(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromWeek,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toWeek,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(weekly.list(fromWeek, toWeek, cursor, limit));
    }

    @PostMapping("/weekly-reviews")
    public Object createReview(@Valid @RequestBody StatisticsDtos.WeeklyCreate request) {
        return ResponseEntity.accepted().body(ApiResponse.ok(ai.createWeekly(request)));
    }

    @GetMapping("/weekly-reviews/{reviewId}")
    public Object review(@PathVariable String reviewId) {
        return ApiResponse.ok(weekly.get(Ids.parse(reviewId)));
    }

    @GetMapping("/weekly-reviews/{reviewId}/evidence")
    public Object evidence(
            @PathVariable String reviewId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(weekly.evidence(Ids.parse(reviewId), cursor, limit));
    }
}
