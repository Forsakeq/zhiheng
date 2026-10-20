package com.yqsoft.controller.knowledge;

import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.common.Ids;
import com.yqsoft.resource.knowledge.KnowledgeDtos;
import com.yqsoft.security.RequirePermission;
import com.yqsoft.service.knowledge.KnowledgeService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class KnowledgeController {
    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/knowledge/articles")
    public Object articles(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.articles(q, category, tags, cursor, limit, false, null));
    }

    @GetMapping("/api/v1/knowledge/articles/{articleId}")
    public Object article(
            @PathVariable String articleId, @RequestParam(required = false) String versionId) {
        return ApiResponse.ok(service.article(Ids.parse(articleId), Ids.optional(versionId)));
    }

    @GetMapping("/api/v1/knowledge/sources/{sourceId}")
    public Object source(@PathVariable String sourceId) {
        return ApiResponse.ok(service.source(Ids.parse(sourceId)));
    }

    @RequirePermission("content.read")
    @GetMapping("/api/admin/v1/knowledge/articles")
    public Object adminArticles(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String editorId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(
                service.articles(q, null, null, cursor, limit, true, status, editorId));
    }

    @RequirePermission("content.edit")
    @PostMapping("/api/admin/v1/knowledge/articles")
    public Object create(@Valid @RequestBody KnowledgeDtos.ArticleCreate request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(service.createArticle(request)));
    }

    @RequirePermission("content.edit")
    @PostMapping("/api/admin/v1/knowledge/articles/{articleId}/versions")
    public Object createVersion(
            @PathVariable String articleId,
            @Valid @RequestBody KnowledgeDtos.VersionCreate request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.ok(service.createVersion(Ids.parse(articleId), request)));
    }

    @RequirePermission("content.edit")
    @PutMapping("/api/admin/v1/knowledge/versions/{versionId}")
    public Object replace(
            @PathVariable String versionId,
            @Valid @RequestBody KnowledgeDtos.VersionReplace request) {
        return ApiResponse.ok(service.replaceVersion(Ids.parse(versionId), request));
    }

    @RequirePermission("content.edit")
    @PostMapping("/api/admin/v1/knowledge/versions/{versionId}/submit")
    public Object submit(
            @PathVariable String versionId, @Valid @RequestBody KnowledgeDtos.VersionBody request) {
        return ApiResponse.ok(service.submit(Ids.parse(versionId), request));
    }

    @RequirePermission("content.review")
    @PostMapping("/api/admin/v1/knowledge/versions/{versionId}/review")
    public Object review(
            @PathVariable String versionId, @Valid @RequestBody KnowledgeDtos.Review request) {
        return ApiResponse.ok(service.review(Ids.parse(versionId), request));
    }

    @RequirePermission("content.publish")
    @PostMapping("/api/admin/v1/knowledge/versions/{versionId}/publish")
    public Object publish(
            @PathVariable String versionId, @Valid @RequestBody KnowledgeDtos.Publish request) {
        return ApiResponse.ok(service.publish(Ids.parse(versionId), request));
    }

    @RequirePermission("content.publish")
    @PostMapping("/api/admin/v1/knowledge/articles/{articleId}/retire")
    public Object retire(
            @PathVariable String articleId, @Valid @RequestBody KnowledgeDtos.Retire request) {
        return ApiResponse.ok(service.retire(Ids.parse(articleId), request));
    }

    @RequirePermission("content.edit")
    @PostMapping("/api/admin/v1/knowledge/source-commands")
    public Object sourceCommand(@Valid @RequestBody KnowledgeDtos.SourceCommand request) {
        return ApiResponse.ok(service.saveSource(request));
    }

    @RequirePermission("content.read")
    @GetMapping("/api/admin/v1/knowledge/sources")
    public Object sourceList(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String verificationStatus,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.sources(q, verificationStatus, cursor, limit));
    }
}
