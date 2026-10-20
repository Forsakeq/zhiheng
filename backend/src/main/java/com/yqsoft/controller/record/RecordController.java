package com.yqsoft.controller.record;

import com.yqsoft.resource.common.*;
import com.yqsoft.resource.record.RecordDtos.*;
import com.yqsoft.service.record.RecordService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RecordController {
    private final RecordService records;

    public RecordController(RecordService records) {
        this.records = records;
    }

    @GetMapping("/records")
    public ApiResponse<PageResult<RecordView>> listRecords(
            @Valid @ModelAttribute RecordQuery query) {
        return ApiResponse.ok(records.list(query));
    }

    @GetMapping("/records/coverage")
    public ApiResponse<CoverageView> getRecordCoverage(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) List<RecordType> recordTypes) {
        return ApiResponse.ok(records.coverage(from, to, recordTypes));
    }

    @GetMapping("/records/{recordId}")
    public ApiResponse<RecordView> getRecord(@PathVariable String recordId) {
        return ApiResponse.ok(records.get(Ids.parse(recordId)));
    }

    @PostMapping("/records")
    public ResponseEntity<ApiResponse<MutationResult>> createRecord(
            @Valid @RequestBody RecordCreate request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(records.create(request)));
    }

    @PutMapping("/records/{recordId}")
    public ApiResponse<MutationResult> replaceRecord(
            @PathVariable String recordId, @Valid @RequestBody RecordReplace request) {
        return ApiResponse.ok(records.replace(Ids.parse(recordId), request));
    }

    @DeleteMapping("/records/{recordId}")
    public ApiResponse<MutationResult> deleteRecord(
            @PathVariable String recordId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(records.delete(Ids.parse(recordId), request));
    }

    @PostMapping("/records/{recordId}/restore")
    public ApiResponse<MutationResult> restoreRecord(
            @PathVariable String recordId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(records.restore(Ids.parse(recordId), request));
    }

    @GetMapping("/record-tags")
    public ApiResponse<PageResult<RecordTagView>> listRecordTags(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") Integer limit) {
        return ApiResponse.ok(records.listTags(cursor, limit));
    }

    @PostMapping("/record-tags")
    public ResponseEntity<ApiResponse<MutationResult>> createRecordTag(
            @Valid @RequestBody RecordTagCreate request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(records.createTag(request)));
    }

    @PatchMapping("/record-tags/{tagId}")
    public ApiResponse<MutationResult> updateRecordTag(
            @PathVariable String tagId, @Valid @RequestBody RecordTagPatch request) {
        return ApiResponse.ok(records.updateTag(Ids.parse(tagId), request));
    }

    @DeleteMapping("/record-tags/{tagId}")
    public ApiResponse<MutationResult> deleteRecordTag(
            @PathVariable String tagId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(records.deleteTag(Ids.parse(tagId), request));
    }

    @PutMapping("/records/{recordId}/tags")
    public ApiResponse<MutationResult> replaceRecordTags(
            @PathVariable String recordId, @Valid @RequestBody RecordTagsPut request) {
        return ApiResponse.ok(records.replaceTags(Ids.parse(recordId), request));
    }

    @PostMapping("/records/{recordId}/attachments")
    public ResponseEntity<ApiResponse<MutationResult>> attachRecordFile(
            @PathVariable String recordId, @Valid @RequestBody AttachmentCreate request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.ok(records.attach(Ids.parse(recordId), request)));
    }

    @DeleteMapping("/records/{recordId}/attachments/{attachmentId}")
    public ApiResponse<MutationResult> removeRecordAttachment(
            @PathVariable String recordId,
            @PathVariable String attachmentId,
            @Valid @RequestBody AttachmentRemove request) {
        return ApiResponse.ok(
                records.removeAttachment(Ids.parse(recordId), Ids.parse(attachmentId), request));
    }
}
