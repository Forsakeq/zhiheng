package com.yqsoft.controller.admin;

import com.yqsoft.resource.common.*;
import com.yqsoft.resource.feedback.FeedbackDtos;
import com.yqsoft.security.RequirePermission;
import com.yqsoft.service.feedback.FeedbackService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/feedback")
public class AdminFeedbackController {
    private final FeedbackService service;

    public AdminFeedbackController(FeedbackService service) {
        this.service = service;
    }

    @RequirePermission("feedback.read")
    @GetMapping
    public Object list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.adminList(status, category, assignedTo, cursor, limit));
    }

    @RequirePermission("feedback.manage")
    @PatchMapping("/{ticketId}")
    public Object manage(
            @PathVariable String ticketId, @Valid @RequestBody FeedbackDtos.ManagePatch request) {
        return ApiResponse.ok(service.manage(Ids.parse(ticketId), request));
    }
}
