package com.yqsoft.controller.feedback;

import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.feedback.FeedbackDtos;
import com.yqsoft.service.feedback.FeedbackService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public Object create(@Valid @RequestBody FeedbackDtos.Create request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.create(request)));
    }

    @GetMapping
    public Object list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.list(status, cursor, limit));
    }
}
