package com.yqsoft.resource.profile;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.resource.common.Rows;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

class ProfilePatchContractTest {
    private final ObjectMapper mapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    void distinguishesExplicitNullFromOmittedAndRejectsUnknownFields() throws Exception {
        var omitted =
                mapper.readValue(
                        mapper.writeValueAsString(
                                Rows.map("expectedRowVersion", "1", "nickname", "同学")),
                        ProfileDtos.ProfilePatch.class);
        var clear =
                mapper.readValue(
                        mapper.writeValueAsString(Rows.map("expectedRowVersion", "1", "bio", null)),
                        ProfileDtos.ProfilePatch.class);
        assertFalse(omitted.presentFields().contains("bio"));
        assertTrue(clear.presentFields().contains("bio"));
        assertNull(clear.bio);
        assertThrows(
                Exception.class,
                () ->
                        mapper.readValue(
                                mapper.writeValueAsString(
                                        Rows.map("expectedRowVersion", "1", "userId", "99")),
                                ProfileDtos.ProfilePatch.class));
    }

    @Test
    void validatesGoalAndTimeBoundariesWithoutCoercingOwnership() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var rule =
                    new ProfileDtos.AvailabilityRuleInput(
                            0,
                            "BUSY",
                            "25:00",
                            "04:00",
                            0,
                            null,
                            "Asia/Shanghai",
                            java.time.LocalDate.of(2026, 10, 18),
                            null);
            assertFalse(validator.validate(rule).isEmpty());
            var request =
                    mapper.readValue(
                            mapper.writeValueAsString(
                                    Rows.map("expectedRowVersion", "0", "dailyTaskLimit", 13)),
                            ProfileDtos.PreferencePatch.class);
            assertFalse(validator.validate(request).isEmpty());
        }
    }
}
