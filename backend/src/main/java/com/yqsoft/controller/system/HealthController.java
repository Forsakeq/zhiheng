package com.yqsoft.controller.system;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.security.PublicEndpoint;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final SqlStore sql;

    public HealthController(SqlStore sql) {
        this.sql = sql;
    }

    @PublicEndpoint
    @GetMapping("/api/v1/health")
    public ApiResponse<?> health() {
        sql.count("SELECT 1");
        return ApiResponse.ok(Rows.map("status", "UP", "service", "zhiheng"));
    }
}
