package com.yqsoft.controller.profile;

import com.yqsoft.resource.common.*;
import com.yqsoft.resource.profile.HomeView;
import com.yqsoft.resource.profile.ProfileDtos.*;
import com.yqsoft.resource.profile.ProfileViews.*;
import com.yqsoft.service.profile.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ProfileController {
    private final ProfileService profiles;
    private final HomeService home;

    public ProfileController(ProfileService profiles, HomeService home) {
        this.profiles = profiles;
        this.home = home;
    }

    @GetMapping("/me")
    public ApiResponse<MeView> me() {
        return ApiResponse.ok(profiles.me());
    }

    @PatchMapping("/me/profile")
    public ApiResponse<MutationResult> profile(@Valid @RequestBody ProfilePatch request) {
        return ApiResponse.ok(profiles.updateProfile(request));
    }

    @PatchMapping("/me/preferences")
    public ApiResponse<MutationResult> preferences(@Valid @RequestBody PreferencePatch request) {
        return ApiResponse.ok(profiles.updatePreferences(request));
    }

    @GetMapping("/me/consents")
    public ApiResponse<ConsentListView> consents() {
        return ApiResponse.ok(profiles.consents());
    }

    @PutMapping("/me/consents/{purpose}")
    public ApiResponse<MutationResult> consent(
            @PathVariable String purpose, @Valid @RequestBody ConsentChange request) {
        return ApiResponse.ok(profiles.changeConsent(purpose, request));
    }

    @GetMapping("/goal-definitions")
    public ApiResponse<GoalDefinitionList> definitions(
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(profiles.definitions(category));
    }

    @GetMapping("/me/goals")
    public ApiResponse<PageResult<UserGoalView>> goals(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(profiles.goals(status, cursor, limit));
    }

    @PostMapping("/me/goals")
    public ResponseEntity<ApiResponse<MutationResult>> createGoal(
            @Valid @RequestBody GoalCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(profiles.createGoal(request)));
    }

    @PatchMapping("/me/goals/{goalId}")
    public ApiResponse<MutationResult> patchGoal(
            @PathVariable String goalId, @Valid @RequestBody GoalPatch request) {
        return ApiResponse.ok(profiles.updateGoal(Ids.parse(goalId), request));
    }

    @PostMapping("/me/goals/{goalId}/close")
    public ApiResponse<MutationResult> closeGoal(
            @PathVariable String goalId, @Valid @RequestBody GoalClose request) {
        return ApiResponse.ok(profiles.closeGoal(Ids.parse(goalId), request));
    }

    @GetMapping("/me/availability-rules")
    public ApiResponse<PageResult<AvailabilityRuleView>> rules(
            @RequestParam(required = false) LocalDate effectiveOn,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(profiles.availability(effectiveOn, kind, cursor, limit));
    }

    @PostMapping("/me/availability-rules")
    public ResponseEntity<ApiResponse<MutationResult>> createRule(
            @Valid @RequestBody AvailabilityRuleInput request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(profiles.createAvailability(request)));
    }

    @PutMapping("/me/availability-rules/{ruleId}")
    public ApiResponse<MutationResult> replaceRule(
            @PathVariable String ruleId, @Valid @RequestBody AvailabilityRuleReplace request) {
        return ApiResponse.ok(profiles.replaceAvailability(Ids.parse(ruleId), request));
    }

    @DeleteMapping("/me/availability-rules/{ruleId}")
    public ApiResponse<MutationResult> deleteRule(
            @PathVariable String ruleId, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(profiles.deleteAvailability(Ids.parse(ruleId), request));
    }

    @GetMapping("/me/availability-exceptions")
    public ApiResponse<ExceptionList> exceptions(
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return ApiResponse.ok(profiles.exceptions(from, to));
    }

    @PutMapping("/me/availability-exceptions/{date}")
    public ApiResponse<MutationResult> putException(
            @PathVariable LocalDate date, @Valid @RequestBody ExceptionPut request) {
        return ApiResponse.ok(profiles.putException(date, request));
    }

    @DeleteMapping("/me/availability-exceptions/{date}")
    public ApiResponse<MutationResult> deleteException(
            @PathVariable LocalDate date, @Valid @RequestBody VersionBody request) {
        return ApiResponse.ok(profiles.deleteException(date, request));
    }

    @GetMapping("/home")
    public ApiResponse<HomeView> home(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String timeZone) {
        return ApiResponse.ok(home.home(date, timeZone));
    }
}
