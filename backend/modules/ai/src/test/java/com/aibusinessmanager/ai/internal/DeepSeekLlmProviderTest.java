package com.aibusinessmanager.ai.internal;

import com.aibusinessmanager.platform.error.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class DeepSeekLlmProviderTest {

    @Test
    void failsValidationWhenApiKeyMissing() {
        AiProperties properties = sample("", "deepseek-flash");
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(properties);
        IllegalStateException ex = assertThrows(IllegalStateException.class, provider::validate);
        assertTrue(ex.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void postsChatCompletionsAndReturnsAssistantContent() {
        AiProperties properties = sample("test-key", "deepseek-flash");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(properties, builder.build());

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model", org.hamcrest.Matchers.is("deepseek-flash")))
                .andExpect(jsonPath("$.thinking.type", org.hamcrest.Matchers.is("disabled")))
                .andRespond(withSuccess(
                        """
                        {"choices":[{"message":{"role":"assistant","content":"Load is 61% this week."}}]}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        assertEquals("deepseek", provider.provider());
        assertEquals("deepseek-flash", provider.model());
        assertEquals("Load is 61% this week.", provider.complete("system", "How is load?"));
        server.verify();
    }

    @Test
    void includesReasoningEffortWhenThinkingEnabled() {
        AiProperties properties = new AiProperties(
                "deepseek",
                null,
                "deepseek-flash",
                "high",
                new AiProperties.DeepSeek("test-key", "https://api.deepseek.com", "enabled", Duration.ofSeconds(90))
        );
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(properties, builder.build());

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.thinking.type", org.hamcrest.Matchers.is("enabled")))
                .andExpect(jsonPath("$.thinking.reasoning_effort", org.hamcrest.Matchers.is("high")))
                .andRespond(withSuccess(
                        """
                        {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        assertEquals("ok", provider.complete("s", "u"));
        server.verify();
    }

    @Test
    void mapsUnauthorizedToBadGatewayWithoutLeakingKey() {
        AiProperties properties = sample("test-key", "deepseek-flash");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(properties, builder.build());

        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withUnauthorizedRequest());

        DomainException ex = assertThrows(DomainException.class, () -> provider.complete("s", "u"));
        assertEquals("LLM_UNAUTHORIZED", ex.code());
        assertEquals(502, ex.status());
        assertTrue(!ex.getMessage().contains("test-key"));
        server.verify();
    }

    private static AiProperties sample(String apiKey, String model) {
        return new AiProperties(
                "deepseek",
                null,
                model,
                "high",
                new AiProperties.DeepSeek(
                        apiKey,
                        "https://api.deepseek.com",
                        "disabled",
                        Duration.ofSeconds(90)
                )
        );
    }
}
