package com.yqsoft.controller.ai;

import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.ai.AiDtos;
import com.yqsoft.resource.common.*;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.ai.AiDraftService;
import com.yqsoft.service.ai.AiService;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiService service;
    private final AiDraftService drafts;
    private final Semaphore streams = new Semaphore(128);

    public AiController(AiService service, AiDraftService drafts) {
        this.service = service;
        this.drafts = drafts;
    }

    @GetMapping("/conversations")
    public Object conversations(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.conversations(cursor, limit));
    }

    @PostMapping("/conversations")
    public Object createConversation(@Valid @RequestBody AiDtos.ConversationCreate request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.createConversation(request)));
    }

    @GetMapping("/conversations/{conversationId}")
    public Object conversation(@PathVariable String conversationId) {
        return ApiResponse.ok(service.conversation(Ids.parse(conversationId)));
    }

    @PostMapping("/conversations/{conversationId}/archive")
    public Object archive(
            @PathVariable String conversationId, @Valid @RequestBody AiDtos.VersionBody request) {
        return ApiResponse.ok(service.archive(Ids.parse(conversationId), request));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public Object messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) String beforeMessageId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(
                service.messages(Ids.parse(conversationId), beforeMessageId, cursor, limit));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public Object message(
            @PathVariable String conversationId, @Valid @RequestBody AiDtos.MessageCreate request) {
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(service.sendMessage(Ids.parse(conversationId), request)));
    }

    @PostMapping("/runs")
    public Object createRun(@Valid @RequestBody AiDtos.RunCreate request) {
        return ResponseEntity.accepted().body(ApiResponse.ok(service.createRun(request)));
    }

    @GetMapping("/runs/{runId}")
    public Object run(@PathVariable String runId) {
        return ApiResponse.ok(service.run(Ids.parse(runId)));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String runId,
            @RequestParam(required = false) String afterEventId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (afterEventId != null && lastEventId != null && !afterEventId.equals(lastEventId))
            throw BusinessException.badRequest("EVENT_CURSOR_CONFLICT", "事件游标不一致");
        long id = Ids.parse(runId);
        var user = AuthContext.current();
        service.run(id);
        String cursor = afterEventId == null ? lastEventId : afterEventId;
        if (cursor != null
                && (!cursor.matches("[1-9][0-9]*:[1-9][0-9]*") || !cursor.startsWith(id + ":")))
            throw BusinessException.badRequest("EVENT_CURSOR_INVALID", "事件游标无效");
        if (!streams.tryAcquire()) throw new BusinessException(429, "STREAM_LIMIT", "连接较多，请使用轮询");
        SseEmitter emitter = new SseEmitter(30000L);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(ex -> closed.set(true));
        Thread.startVirtualThread(
                () -> {
                    String sent = cursor;
                    try {
                        Instant deadline = Instant.now().plusSeconds(28);
                        while (!closed.get() && Instant.now().isBefore(deadline)) {
                            Map<String, Object> event =
                                    service.event(user.userId(), user.sessionId(), id);
                            String eventId = String.valueOf(event.get("eventId"));
                            if (!eventId.equals(sent)) {
                                emitter.send(
                                        SseEmitter.event().id(eventId).name("run").data(event));
                                sent = eventId;
                            }
                            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED")
                                    .contains(event.get("status"))) break;
                            Thread.sleep(500);
                        }
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    } finally {
                        streams.release();
                    }
                });
        return emitter;
    }

    @PostMapping("/runs/{runId}/cancel")
    public Object cancelRun(
            @PathVariable String runId, @Valid @RequestBody AiDtos.RunCancel request) {
        return ApiResponse.ok(service.cancelRun(Ids.parse(runId), request));
    }

    @PostMapping("/runs/{runId}/retry")
    public Object retryRun(
            @PathVariable String runId, @Valid @RequestBody AiDtos.RunRetry request) {
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(service.retryRun(Ids.parse(runId), request)));
    }

    @GetMapping("/drafts/{draftId}")
    public Object draft(@PathVariable String draftId) {
        return ApiResponse.ok(drafts.get(Ids.parse(draftId)));
    }

    @PutMapping("/drafts/{draftId}/revisions")
    public Object revise(
            @PathVariable String draftId, @Valid @RequestBody AiDtos.DraftRevision request) {
        return ApiResponse.ok(drafts.revise(Ids.parse(draftId), request));
    }

    @PostMapping("/drafts/{draftId}/validate")
    public Object validate(
            @PathVariable String draftId, @Valid @RequestBody AiDtos.DraftValidate request) {
        return ApiResponse.ok(drafts.validate(Ids.parse(draftId), request));
    }

    @PostMapping("/drafts/{draftId}/apply")
    public Object apply(
            @PathVariable String draftId, @Valid @RequestBody AiDtos.DraftApply request) {
        return ApiResponse.ok(drafts.apply(Ids.parse(draftId), request));
    }

    @PostMapping("/drafts/{draftId}/cancel")
    public Object cancel(
            @PathVariable String draftId, @Valid @RequestBody AiDtos.DraftCancel request) {
        return ApiResponse.ok(drafts.cancel(Ids.parse(draftId), request));
    }
}
