package com.yqsoft.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.service.auth.VerificationSender;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Real MySQL fixtures; the mail sender alone is replaced, never a business service. */
@SpringBootTest(
        properties = {
            "zhiheng.environment=DEV",
            "zhiheng.auth.registration-enabled=true",
            "zhiheng.auth.service-policy-version=integration-service-v1",
            "zhiheng.auth.consent-policy-version=integration-consent-v1",
            "zhiheng.jobs-enabled=false"
        })
@AutoConfigureMockMvc
public abstract class MySqlFlowSupport {

    @Autowired protected MockMvc mvc;
    @Autowired protected SqlStore sql;
    @Autowired protected ObjectMapper mapper;
    @MockBean protected VerificationSender sender;
    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("ZH_TEST_DB_URL"));
        registry.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("ZH_TEST_DB_USERNAME", "root"));
        registry.add(
                "spring.datasource.password",
                () -> System.getenv().getOrDefault("ZH_TEST_DB_PASSWORD", ""));
    }

    @BeforeEach
    void prepareRegistration() {
        sql.execute(
                "UPDATE app_configuration SET value_json=JSON_OBJECT('booleanValue',true) "
                        + "WHERE environment='DEV' AND config_key='auth.registration_enabled'");
        doAnswer(
                        invocation -> {
                            codes.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(sender)
                .send(anyString(), anyString(), anyString());
    }

    protected JsonNode registerUser() throws Exception {
        String email = "flow-" + UUID.randomUUID() + "@example.invalid";
        JsonNode verification =
                request(
                        post("/api/v1/auth/verifications"),
                        null,
                        Rows.map(
                                "destination",
                                email,
                                "channel",
                                "EMAIL",
                                "purpose",
                                "REGISTER",
                                "clientAudience",
                                "MOBILE"),
                        201);
        return request(
                post("/api/v1/auth/register"),
                null,
                Rows.map(
                        "account",
                        email,
                        "password",
                        "Integration-only-passphrase-2026",
                        "verificationId",
                        verification.path("verificationId").asText(),
                        "verificationCode",
                        codes.get(email),
                        "adultConfirmed",
                        true,
                        "termsAccepted",
                        true,
                        "policyVersion",
                        "integration-service-v1",
                        "installationKey",
                        UUID.randomUUID().toString(),
                        "timeZone",
                        "Asia/Shanghai",
                        "platform",
                        "ANDROID"),
                201);
    }

    protected JsonNode request(
            MockHttpServletRequestBuilder builder, JsonNode user, Object body, int expectedStatus)
            throws Exception {
        if (user != null) {
            builder.header("Authorization", "Bearer " + user.path("accessToken").asText())
                    .header("X-Data-Epoch", user.path("datasetEpoch").asText());
        }
        if (body != null) {
            builder.contentType("application/json")
                    .content(mapper.writeValueAsBytes(body))
                    .header("Idempotency-Key", UUID.randomUUID().toString());
        }
        var response = mvc.perform(builder).andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(expectedStatus, response.getStatus(), content);
        JsonNode envelope = mapper.readTree(content);
        return expectedStatus < 400 ? envelope.path("data") : envelope;
    }
}
