package com.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 离线验证 GLM 请求协议和响应解析，不访问真实服务。 */
class GlmClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldSendConfiguredModelAndParseToolCalls() throws Exception {
        AtomicReference<Request> capturedRequest = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String responseJson = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "read_file",
                          "arguments": "{\\\"path\\\":\\\"pom.xml\\\"}"
                        }
                      }]
                    }
                  }],
                  "usage": {"prompt_tokens": 18, "completion_tokens": 7}
                }
                """;

        OkHttpClient httpClient = fakeHttpClient(
                200, responseJson, capturedRequest, capturedBody);
        GlmClient client = new GlmClient(
                "test-key", httpClient, "https://example.invalid/chat", mapper);

        LLMResponse response = client.chat(
                List.of(Message.user("读取 pom.xml")), List.of());

        JsonNode sentJson = mapper.readTree(capturedBody.get());
        assertEquals("glm-5.3-flash", sentJson.path("model").asText());
        assertEquals("Bearer test-key", capturedRequest.get().header("Authorization"));
        assertTrue(response.hasToolCalls());
        assertEquals("", response.content());
        assertEquals("call_1", response.toolCalls().get(0).id());
        assertEquals("read_file", response.toolCalls().get(0).name());
        assertEquals(18, response.promptTokens());
        assertEquals(7, response.completionTokens());
    }

    @Test
    void shouldPreserveAssistantCallAndToolObservationInRequest() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        OkHttpClient httpClient = fakeHttpClient(
                200,
                "{\"choices\":[{\"message\":{\"content\":\"完成\"}}]}",
                new AtomicReference<>(),
                capturedBody);
        GlmClient client = new GlmClient(
                "test-key", httpClient, "https://example.invalid/chat", mapper);
        ToolCall call = new ToolCall(
                "call_9", new ToolCall.Function("read_file", "{\"path\":\"README.md\"}"));

        client.chat(List.of(
                Message.user("读取 README"),
                Message.assistant("", List.of(call)),
                Message.tool("call_9", "文件内容")), List.of());

        JsonNode messages = mapper.readTree(capturedBody.get()).path("messages");
        assertEquals("call_9", messages.get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("call_9", messages.get(2).path("tool_call_id").asText());
    }

    @Test
    void shouldRejectBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new GlmClient(""));
    }

    @Test
    void shouldPreserveStatusAndBodyForHttpErrors() {
        OkHttpClient httpClient = fakeHttpClient(
                429,
                "{\"error\":\"rate limited\"}",
                new AtomicReference<>(),
                new AtomicReference<>());
        GlmClient client = new GlmClient(
                "test-key", httpClient, "https://example.invalid/chat", mapper);

        Exception error = assertThrows(Exception.class,
                () -> client.chat(List.of(Message.user("hello")), List.of()));

        assertTrue(error.getMessage().contains("GLM HTTP 429"));
        assertTrue(error.getMessage().contains("rate limited"));
    }

    private static OkHttpClient fakeHttpClient(
            int status,
            String responseJson,
            AtomicReference<Request> capturedRequest,
            AtomicReference<String> capturedBody) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    capturedRequest.set(request);
                    Buffer buffer = new Buffer();
                    request.body().writeTo(buffer);
                    capturedBody.set(buffer.readUtf8());
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(status)
                            .message(status == 200 ? "OK" : "Error")
                            .body(ResponseBody.create(
                                    responseJson,
                                    MediaType.parse("application/json")))
                            .build();
                })
                .build();
    }
}
