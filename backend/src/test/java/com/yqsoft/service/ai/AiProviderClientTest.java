package com.yqsoft.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yqsoft.config.AppProperties;
import com.yqsoft.exception.BusinessException;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

class AiProviderClientTest {
    @Test
    void unconfiguredProviderFailsExplicitlyInsteadOfReturningInventedSuccess() {
        var client = new AiProviderClient(new AppProperties(), new ObjectMapper());
        var error = assertThrows(BusinessException.class, client::assertAvailable);
        assertEquals(503, error.getStatus());
        assertEquals("AI_NOT_CONFIGURED", error.getCode());
    }

    @Test
    void compatibleProviderUsesRealHttpAndParsesStructuredCompletion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    assertEquals(
                            "Bearer fixture-key",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] response =
                            "{\"id\":\"fixture-response\",\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4},\"choices\":[{\"message\":{\"content\":\"{\\\"suggestedGoalCodes\\\":[]}\"}}]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var properties = configured(server.getAddress().getPort());
            var completion =
                    new AiProviderClient(properties, new ObjectMapper())
                            .complete(
                                    "WEEKLY_REVIEW",
                                    Map.of("availableGoalCodes", java.util.List.of()));
            assertTrue(completion.output().path("suggestedGoalCodes").isArray());
            assertEquals(7, completion.inputTokens());
            assertEquals("fixture-response", completion.providerRequestId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void productionCannotSendSecretsToPlainHttp() {
        var properties = configured(12345);
        properties.setEnvironment("PROD");
        var error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                new AiProviderClient(properties, new ObjectMapper())
                                        .assertAvailable());
        assertEquals("AI_CONFIGURATION_INVALID", error.getCode());
        assertFalse(error.getMessage().contains("fixture-key"));
    }

    private AppProperties configured(int port) {
        var properties = new AppProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://127.0.0.1:" + port + "/v1");
        properties.getAi().setApiKey("fixture-key");
        properties.getAi().setModel("fixture-model");
        return properties;
    }
}
