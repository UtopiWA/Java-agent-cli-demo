package com.agent.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 使用智谱 Chat Completions HTTP API 的 LLMClient 实现。 */
public final class GlmClient implements LLMClient {
    static final String API_URL =
            "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    static final String MODEL = "glm-5.3-flash";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String apiKey;
    private final OkHttpClient httpClient;
    private final String apiUrl;
    private final ObjectMapper mapper;

    /** 创建用于真实 GLM 服务的客户端；API Key 只保存在内存中。 */
    public GlmClient(String apiKey) {
        this(apiKey, defaultHttpClient(), API_URL, new ObjectMapper());
    }

    /** 允许测试替换 HTTP 传输和地址，避免离线测试访问真实网络。 */
    GlmClient(
            String apiKey,
            OkHttpClient httpClient,
            String apiUrl,
            ObjectMapper mapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("GLM_API_KEY 不能为空");
        }
        this.apiKey = apiKey;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 构造请求、发送 Bearer 鉴权 POST，并解析文本与 tool_calls。 */
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDef> tools) throws IOException {
        ObjectNode requestJson = buildRequest(messages, tools);
        RequestBody body = RequestBody.create(requestJson.toString(), JSON);
        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new GlmHttpException(response.code(), responseBody);
            }
            return parseResponse(responseBody);
        }
    }

    /** 把四种历史消息和可选工具定义转换成 GLM 请求 JSON。 */
    private ObjectNode buildRequest(List<Message> messages, List<ToolDef> tools) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", MODEL);

        ArrayNode messagesArray = root.putArray("messages");
        for (Message message : messages) {
            ObjectNode node = messagesArray.addObject();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            appendToolCalls(node, message.toolCalls());
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDef tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.parameters());
            }
        }
        return root;
    }

    /** assistant 消息中的工具调用，原样保留 ID、名称和 JSON 参数。 */
    private void appendToolCalls(ObjectNode messageNode, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        ArrayNode calls = messageNode.putArray("tool_calls");
        for (ToolCall call : toolCalls) {
            ObjectNode callNode = calls.addObject();
            callNode.put("id", call.id());
            callNode.put("type", "function");
            ObjectNode function = callNode.putObject("function");
            function.put("name", call.name());
            function.put("arguments", call.arguments());
        }
    }

    /** 解析第一个 choice，允许 content 为空，但 tool_calls 非空。 */
    private LLMResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("响应中缺少 choices: " + responseBody);
        }

        JsonNode message = choices.get(0).path("message");
        JsonNode contentNode = message.path("content");
        String content = contentNode.isMissingNode() || contentNode.isNull()
                ? "" : contentNode.asText("");

        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        JsonNode usage = root.path("usage");
        return new LLMResponse(
                content,
                toolCalls,
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    private static List<ToolCall> parseToolCalls(JsonNode callsNode) {
        if (!callsNode.isArray() || callsNode.isEmpty()) {
            return null;
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode callNode : callsNode) {
            JsonNode function = callNode.path("function");
            calls.add(new ToolCall(
                    callNode.path("id").asText(),
                    new ToolCall.Function(
                            function.path("name").asText(),
                            function.path("arguments").asText("{}"))));
        }
        return List.copyOf(calls);
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }
}
