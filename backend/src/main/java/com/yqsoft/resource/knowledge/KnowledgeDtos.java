package com.yqsoft.resource.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public final class KnowledgeDtos {
    private KnowledgeDtos() {}

    public record SourceLink(
            @NotBlank String sourceId,
            @NotBlank @Size(max = 40) String citationLabel,
            @Size(max = 200) String locator,
            @Min(0) @Max(100) Integer sortOrder) {}

    public record Content(
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 400) String summary,
            @NotBlank @Size(max = 30000) String bodyMarkdown,
            @NotBlank @Size(max = 500) String applicablePopulation,
            @NotBlank @Size(max = 2000) String limitations,
            @Size(max = 10) List<@NotBlank @Size(max = 30) String> tags,
            @NotEmpty @Size(max = 20) List<@Valid SourceLink> sourceLinks,
            @NotNull LocalDate contentDate,
            @NotBlank @Size(max = 1000) String rightsNote) {}

    public record ArticleCreate(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,79}") String slug,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Pattern(regexp = "ROUTINE|ACTIVITY|MEAL_HABIT|RECORDING|APP_HELP")
                    String category,
            @NotNull @Valid Content initialVersion) {}

    public record VersionCreate(
            @NotBlank String expectedArticleRowVersion,
            String copyFromVersionId,
            @Valid Content content,
            @NotBlank @Size(max = 500) String changeReason) {}

    public record VersionReplace(
            @NotBlank String expectedRowVersion,
            @NotNull @Valid Content content,
            @NotBlank @Size(max = 500) String changeReason) {}

    public record VersionBody(@NotBlank String expectedRowVersion) {}

    public record Review(
            @NotBlank String expectedRowVersion,
            @NotBlank @Pattern(regexp = "PASS|REJECT") String decision,
            @NotBlank @Size(max = 1000) String comment) {}

    public record Publish(
            @NotBlank String expectedRowVersion,
            @NotBlank String expectedArticleRowVersion,
            String expectedActiveVersionId,
            @NotNull @AssertTrue Boolean confirmPublishedContent) {}

    public record Retire(
            @NotBlank String expectedRowVersion, @NotBlank @Size(max = 1000) String reason) {}

    public record SourceCommand(
            @NotBlank @Pattern(regexp = "CREATE|REPLACE") String action,
            String sourceId,
            String expectedRowVersion,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 200) String organization,
            @NotBlank @Size(max = 2048) String url,
            LocalDate publishedOn,
            @NotNull LocalDate accessedOn,
            @NotBlank @Pattern(regexp = "PENDING|VERIFIED|UNAVAILABLE|REJECTED")
                    String verificationStatus,
            @NotBlank @Size(max = 1000) String rightsNote,
            @Size(max = 1000) String verificationNote,
            @NotBlank @Pattern(regexp = "OFFICIAL_WEB|GUIDELINE|PAPER|APP_DOCUMENTATION")
                    String sourceKind,
            @NotBlank @Pattern(regexp = "LINK_ONLY|PERMITTED_EXCERPT|LICENSED|PUBLIC_DOMAIN")
                    String usageBasis) {}
}
