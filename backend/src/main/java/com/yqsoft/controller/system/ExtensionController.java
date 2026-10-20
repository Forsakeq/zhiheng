package com.yqsoft.controller.system;

import com.yqsoft.exception.BusinessException;
import com.yqsoft.security.PublicEndpoint;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备与外部健康服务的准入边界。 未选择并验证设备、SDK和提供方授权之前，不签发设备凭据或返回虚假接入结果。 */
@RestController
@RequestMapping("/api/v1")
public class ExtensionController {

    @GetMapping("/devices")
    public Object devices() {
        throw disabled();
    }

    @PostMapping("/devices/pairing")
    public Object pairing() {
        throw disabled();
    }

    @PostMapping("/devices/pairing/confirm")
    public Object confirmPairing() {
        throw disabled();
    }

    @DeleteMapping("/devices/{deviceId}")
    public Object unpair() {
        throw disabled();
    }

    @PublicEndpoint
    @GetMapping("/device/tasks")
    public Object deviceTasks() {
        throw disabled();
    }

    @PublicEndpoint
    @PostMapping("/device/task-receipts")
    public Object deviceReceipt() {
        throw disabled();
    }

    @GetMapping("/external-connections")
    public Object connections() {
        throw disabled();
    }

    @PostMapping("/external-connections/authorization")
    public Object authorize() {
        throw disabled();
    }

    @PostMapping("/external-connections/authorization/complete")
    public Object finishAuthorization() {
        throw disabled();
    }

    @DeleteMapping("/external-connections/{connectionId}")
    public Object revokeAuthorization() {
        throw disabled();
    }

    @PostMapping("/external-connections/{connectionId}/imports")
    public Object importData() {
        throw disabled();
    }

    @GetMapping("/external-imports/{batchId}")
    public Object importStatus() {
        throw disabled();
    }

    private BusinessException disabled() {
        return BusinessException.unavailable("FEATURE_NOT_ENABLED", "该扩展尚未完成设备与服务准入验证");
    }
}
