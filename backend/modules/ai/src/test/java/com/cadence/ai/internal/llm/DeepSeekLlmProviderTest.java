package com.cadence.ai.internal.llm;

import com.cadence.ai.internal.AiProperties;
import com.cadence.platform.error.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class DeepSeekLlmProviderTest {

    private static final String URL = "https://api.deepseek.com/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void failsValidationWhenApiKeyMissing() {
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(sample("", "disabled"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, provider::validate);
        assertTrue(ex.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void failsValidationWhenThinkingIsEnabledBecauseToolLoopDoesNotSupportIt() {
        DeepSeekLlmProvider provider = new DeepSeekLlmProvider(sample("test-key", "enabled"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, provider::validate);
        assertTrue(ex.getMessage().contains("thinking"), ex.getMessage());
        assertTrue(ex.getMessage().contains("tool loop"), ex.getMessage());
        assertFalse(ex.getMessage().contains("test-key"));
    }

    @Test
    void passesValidationWithKeyAndThinkingDisabled() {
        new DeepSeekLlmProvider(sample("test-key", "disabled")).validate();
    }

    @Test
    void serialisesAllRolesToolsToolChoiceAndMaxTokens() throws Exception {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-flash",
                          "stream": false,
                          "max_tokens": 512,
                          "tool_choice": "auto",
                          "thinking": {"type": "disabled"},
                          "messages": [
                            {"role": "system", "content": "sys"},
                            {"role": "user", "content": "show CLIENT_1"},
                            {"role": "assistant", "content": null, "tool_calls": [
                              {"id": "call_1", "type": "function",
                               "function": {"name": "get_client_profile", "arguments": "{\\"clientRef\\":\\"CLIENT_1\\"}"}},
                              {"id": "call_2", "type": "function",
                               "function": {"name": "get_metrics", "arguments": "{}"}}
                            ]},
                            {"role": "tool", "tool_call_id": "call_1", "content": "{\\"completedVisits\\":3}"},
                            {"role": "tool", "tool_call_id": "call_2", "content": "[]"},
                            {"role": "assistant", "content": "Earlier answer"}
                          ],
                          "tools": [
                            {"type": "function", "function": {
                              "name": "get_client_profile",
                              "description": "Client profile by ref",
                              "parameters": {"type": "object",
                                             "properties": {"clientRef": {"type": "string"}},
                                             "required": ["clientRef"],
                                             "additionalProperties": false}
                            }}
                          ]
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(textResponse("ok"), MediaType.APPLICATION_JSON));

        LlmRequest request = new LlmRequest(
                List.of(
                        new LlmMessage.System("sys"),
                        new LlmMessage.User("show CLIENT_1"),
                        new LlmMessage.Assistant(null, List.of(
                                new ToolCall("call_1", "get_client_profile", "{\"clientRef\":\"CLIENT_1\"}"),
                                new ToolCall("call_2", "get_metrics", "{}")
                        )),
                        new LlmMessage.ToolResult("call_1", "get_client_profile", "{\"completedVisits\":3}"),
                        new LlmMessage.ToolResult("call_2", "get_metrics", "[]"),
                        new LlmMessage.Assistant("Earlier answer", List.of())
                ),
                List.of(new ToolSpec("get_client_profile", "Client profile by ref", MAPPER.readTree("""
                        {"type":"object","properties":{"clientRef":{"type":"string"}},
                         "required":["clientRef"],"additionalProperties":false}
                        """))),
                ToolChoice.AUTO,
                512
        );

        assertEquals("ok", provider.chat(request).message().content());
        server.verify();
    }

    @Test
    void sendsToolChoiceNoneWhenRequested() throws Exception {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.tool_choice", is("none")))
                .andExpect(jsonPath("$.tools[0].function.name", is("get_metrics")))
                .andRespond(withSuccess(textResponse("final"), MediaType.APPLICATION_JSON));

        provider.chat(new LlmRequest(
                List.of(new LlmMessage.User("u")),
                List.of(new ToolSpec("get_metrics", "Metrics", MAPPER.readTree("{\"type\":\"object\"}"))),
                ToolChoice.NONE,
                100
        ));
        server.verify();
    }

    @Test
    void omitsToolsAndToolChoiceWhenNoToolsAreGiven() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(jsonPath("$.tool_choice").doesNotExist())
                .andExpect(jsonPath("$.max_tokens", is(64)))
                .andRespond(withSuccess(textResponse("ok"), MediaType.APPLICATION_JSON));

        provider.chat(simpleRequest(64));
        server.verify();
    }

    @Test
    void parsesTextReplyFinishReasonAndUsage() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"Load is 61% this week."}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":15,"total_tokens":135}}
                """, MediaType.APPLICATION_JSON));

        LlmResponse response = provider.chat(simpleRequest(256));

        assertEquals("deepseek", provider.provider());
        assertEquals("deepseek-flash", provider.model());
        assertEquals("Load is 61% this week.", response.message().content());
        assertTrue(response.message().toolCalls().isEmpty());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(new Usage(120, 15), response.usage());
        assertEquals(135, response.usage().total());
        server.verify();
    }

    @Test
    void parsesMultipleToolCalls() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"choices":[{"index":0,"finish_reason":"tool_calls",
                  "message":{"role":"assistant","content":null,"tool_calls":[
                    {"id":"call_a","type":"function","function":{"name":"get_schedule",
                       "arguments":"{\\"dateFrom\\":\\"2026-10-26\\",\\"dateTo\\":\\"2026-10-27\\"}"}},
                    {"id":"call_b","type":"function","function":{"name":"get_metrics","arguments":"{}"}}
                  ]}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":20}}
                """, MediaType.APPLICATION_JSON));

        LlmResponse response = provider.chat(simpleRequest(256));

        assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
        assertNull(response.message().content());
        assertEquals(List.of(
                new ToolCall("call_a", "get_schedule", "{\"dateFrom\":\"2026-10-26\",\"dateTo\":\"2026-10-27\"}"),
                new ToolCall("call_b", "get_metrics", "{}")
        ), response.message().toolCalls());
        assertEquals(new Usage(10, 20), response.usage());
        server.verify();
    }

    @Test
    void mapsLengthAndUnknownFinishReasonsAndDefaultsMissingUsageToZero() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"Truncat"}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"choices":[{"finish_reason":"content_filter","message":{"role":"assistant","content":"x"}}],
                 "usage":{"prompt_tokens":7}}
                """, MediaType.APPLICATION_JSON));

        LlmResponse length = provider.chat(simpleRequest(5));
        LlmResponse other = provider.chat(simpleRequest(5));

        assertEquals(FinishReason.LENGTH, length.finishReason());
        assertEquals(new Usage(0, 0), length.usage());
        assertEquals(0, length.usage().total());
        assertEquals(FinishReason.OTHER, other.finishReason());
        assertEquals(new Usage(7, 0), other.usage());
        server.verify();
    }

    @Test
    void emptyResponseWithoutTextOrToolCallsIsUnavailable() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"  ","tool_calls":[]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        DomainException blank = assertThrows(DomainException.class, () -> provider.chat(simpleRequest(5)));
        DomainException noChoices = assertThrows(DomainException.class, () -> provider.chat(simpleRequest(5)));

        assertEquals("LLM_UNAVAILABLE", blank.code());
        assertEquals(502, blank.status());
        assertEquals("LLM_UNAVAILABLE", noChoices.code());
        server.verify();
    }

    @Test
    void unreadableResponseIsUnavailable() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        DomainException ex = assertThrows(DomainException.class, () -> provider.chat(simpleRequest(5)));
        assertEquals("LLM_UNAVAILABLE", ex.code());
        server.verify();
    }

    @Test
    void mapsUnauthorizedToBadGatewayWithoutLeakingKey() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL)).andRespond(withUnauthorizedRequest());

        DomainException ex = assertThrows(DomainException.class, () -> provider.chat(simpleRequest(5)));
        assertEquals("LLM_UNAUTHORIZED", ex.code());
        assertEquals(502, ex.status());
        assertFalse(ex.getMessage().contains("test-key"));
        server.verify();
    }

    @Test
    void mapsServerErrorToUnavailable() {
        DeepSeekLlmProvider provider = provider("disabled");
        server.expect(requestTo(URL)).andRespond(withServerError());

        DomainException ex = assertThrows(DomainException.class, () -> provider.chat(simpleRequest(5)));
        assertEquals("LLM_UNAVAILABLE", ex.code());
        assertFalse(ex.getMessage().contains("test-key"));
        server.verify();
    }

    @Test
    void includesReasoningEffortWhenThinkingEnabled() {
        DeepSeekLlmProvider provider = provider("enabled");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.thinking.type", is("enabled")))
                .andExpect(jsonPath("$.thinking.reasoning_effort", is("high")))
                .andRespond(withSuccess(textResponse("ok"), MediaType.APPLICATION_JSON));

        assertEquals("ok", provider.chat(simpleRequest(5)).message().content());
        server.verify();
    }

    private DeepSeekLlmProvider provider(String thinking) {
        return new DeepSeekLlmProvider(sample("test-key", thinking), builder.build());
    }

    private static LlmRequest simpleRequest(int maxTokens) {
        return new LlmRequest(
                List.of(new LlmMessage.System("s"), new LlmMessage.User("u")),
                List.of(),
                ToolChoice.AUTO,
                maxTokens
        );
    }

    private static String textResponse(String text) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + text + "\"}}]}";
    }

    private static AiProperties sample(String apiKey, String thinking) {
        return AiProperties.withDefaultLimits(
                "deepseek",
                null,
                "deepseek-flash",
                "high",
                new AiProperties.DeepSeek(apiKey, "https://api.deepseek.com", thinking, Duration.ofSeconds(90))
        );
    }
}
