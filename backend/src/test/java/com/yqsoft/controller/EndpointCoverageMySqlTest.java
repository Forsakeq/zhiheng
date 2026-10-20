package com.yqsoft.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.yqsoft.support.MySqlFlowSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "ZH_TEST_DB_URL", matches = ".+")
class EndpointCoverageMySqlTest extends MySqlFlowSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void allPlannedRoutesAreRegistered() throws Exception {
        Set<String> actual = new TreeSet<>();
        mappings.getHandlerMethods()
                .forEach(
                        (mapping, handler) -> {
                            if (!handler.getBeanType()
                                    .getPackageName()
                                    .startsWith("com.yqsoft.controller")) return;
                            for (String path : mapping.getPatternValues()) {
                                mapping.getMethodsCondition()
                                        .getMethods()
                                        .forEach(
                                                method ->
                                                        actual.add(
                                                                method.name()
                                                                        + " "
                                                                        + normalize(path)));
                            }
                        });
        List<String> missing = new ArrayList<>();
        try (var input = getClass().getResourceAsStream("/contracts/planned-endpoints.json")) {
            JsonNode planned = mapper.readTree(input);
            assertEquals(160, planned.size());
            for (JsonNode route : planned) {
                String relative = route.path("path").asText();
                String path =
                        relative.startsWith("/admin/")
                                ? "/api/admin/v1/" + relative.substring("/admin/".length())
                                : "/api/v1" + relative;
                String key = route.path("method").asText() + " " + normalize(path);
                if (!actual.contains(key)) missing.add(route.path("number").asText() + " " + key);
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(
                Path.of("target/endpoint-inventory.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
        assertEquals(List.of(), missing, "The implementation must register every planned route");
    }

    @Test
    void missingQueryAndUnknownRouteUseExplicitClientErrors() throws Exception {
        var invalid = request(get("/api/v1/configuration"), null, null, 422);
        assertEquals("VALIDATION_FAILED", invalid.path("code").asText());
        var missing = request(get("/api/v1/route-that-does-not-exist"), null, null, 404);
        assertEquals("NOT_FOUND", missing.path("code").asText());
    }

    private String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }
}
