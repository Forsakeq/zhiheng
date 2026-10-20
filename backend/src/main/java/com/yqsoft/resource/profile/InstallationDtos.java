package com.yqsoft.resource.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public final class InstallationDtos {
    private InstallationDtos() {}

    public record Capabilities(
            @NotBlank @Pattern(regexp = "UNVERIFIED|SUPPORTED|UNSUPPORTED") String localReminder,
            @NotBlank @Pattern(regexp = "NONE|REGISTER_ONLY|DELIVERY_CALLBACK")
                    String deliveryObservation,
            @NotBlank @Pattern(regexp = "NONE|UNVERIFIED|SUPPORTED") String watchTaskChannel,
            @NotBlank @Pattern(regexp = "UNVERIFIED|SUPPORTED|UNSUPPORTED")
                    String secureCredentialStorage,
            Instant verifiedAt) {}

    public record Register(
            @NotNull UUID installationKey,
            @NotBlank @Pattern(regexp = "PHONE|WEB") String installationKind,
            @NotBlank @Pattern(regexp = "ANDROID|HARMONYOS|IOS|WEB") String platform,
            @NotBlank @Size(max = 80) String deviceName,
            @NotBlank @Size(max = 32) String appVersion,
            @NotBlank @Size(max = 64) String osVersion,
            @NotNull @Valid Capabilities capabilities,
            @NotBlank @Pattern(regexp = "GRANTED|DENIED|UNKNOWN") String notificationPermission) {}

    public record Patch(
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedRowVersion,
            @Size(min = 1, max = 80) String deviceName,
            @Size(min = 1, max = 32) String appVersion,
            @Size(min = 1, max = 64) String osVersion,
            @Valid Capabilities capabilities,
            @Pattern(regexp = "GRANTED|DENIED|UNKNOWN") String notificationPermission) {}
}
