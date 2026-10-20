package com.yqsoft.controller.reminder;

import com.yqsoft.resource.common.*;
import com.yqsoft.resource.reminder.ReminderDtos;
import com.yqsoft.service.reminder.ReminderService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class ReminderController {
    private final ReminderService service;

    public ReminderController(ReminderService service) {
        this.service = service;
    }

    @GetMapping("/reminder-rules")
    public Object rules(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.rules(cursor, limit));
    }

    @PostMapping("/reminder-rules")
    public Object create(@Valid @RequestBody ReminderDtos.Create request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.create(request)));
    }

    @PatchMapping("/reminder-rules/{ruleId}")
    public Object update(
            @PathVariable String ruleId, @Valid @RequestBody ReminderDtos.Patch request) {
        return ApiResponse.ok(service.update(Ids.parse(ruleId), request));
    }

    @DeleteMapping("/reminder-rules/{ruleId}")
    public Object delete(
            @PathVariable String ruleId, @Valid @RequestBody ReminderDtos.VersionBody request) {
        return ApiResponse.ok(service.deleteRule(Ids.parse(ruleId), request));
    }

    @GetMapping("/reminder-deliveries")
    public Object deliveries(
            @RequestParam String installationId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(
                service.deliveries(Ids.parse(installationId), state, from, to, cursor, limit));
    }

    @PostMapping("/reminder-deliveries/{deliveryId}/receipts")
    public Object receipt(
            @PathVariable String deliveryId, @Valid @RequestBody ReminderDtos.Receipt request) {
        return ApiResponse.ok(service.receipt(Ids.parse(deliveryId), request));
    }

    @GetMapping("/notifications")
    public Object notifications(
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.notifications(read, category, cursor, limit));
    }

    @PutMapping("/notifications/read-state")
    public Object read(@Valid @RequestBody ReminderDtos.ReadState request) {
        MutationResult result = service.read(request);
        return ApiResponse.ok(
                Rows.map(
                        "results", result.extra().get("results"), "changeSeq", result.changeSeq()));
    }

    @DeleteMapping("/notifications/{notificationId}")
    public Object deleteNotification(
            @PathVariable String notificationId,
            @Valid @RequestBody ReminderDtos.VersionBody request) {
        return ApiResponse.ok(service.deleteNotification(Ids.parse(notificationId), request));
    }
}
