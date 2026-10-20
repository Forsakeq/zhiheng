package com.yqsoft.resource.sync;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SyncDtos {

    private SyncDtos() {}

    public record BootstrapBegin(
            @NotBlank String installationId,
            @NotBlank String datasetEpoch,
            @NotEmpty @Size(max = 12) List<@NotBlank String> entityTypes) {}

    public record Operation(
            @NotNull UUID clientOperationId,
            @NotBlank String operationCode,
            @NotBlank String datasetEpoch,
            String entityId,
            @NotNull JsonNode payload,
            @NotNull Instant clientOccurredAt,
            Boolean createdOffline) {}

    public record Batch(
            @NotBlank String installationId,
            @NotBlank String datasetEpoch,
            @NotEmpty @Size(max = 50) List<@Valid Operation> operations) {}

    public record Ack(
            @NotBlank String installationId,
            @NotBlank String datasetEpoch,
            @NotBlank String ackSeq,
            UUID bootstrapId,
            Boolean bootstrapCompleted) {}
}
