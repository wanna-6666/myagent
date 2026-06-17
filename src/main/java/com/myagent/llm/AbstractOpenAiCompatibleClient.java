package com.myagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容协议基类 - 模板方法模式
 *
 * 封装 SSE 流式解析、请求构建、Token 统计等通用逻辑，
 * 子类只需覆盖 getApiUrl()、getModel()、getApiKey()。
 */
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper mapper = new ObjectMapper();

    protected static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build();

    protected abstract String getApiUrl();
    protected abstract String getModel();
    protected abstract String getApiKey();

    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    protected OkHttpClient httpClient() {
        return HTTP_CLIENT;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;

        String bodyJson = buildRequestBody(messages, tools);
        RequestBody body = RequestBody.create(bodyJson, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient().newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBody != null ? responseBody.string() : "无响应体";
                throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBody == null) {
                throw new IOException("API返回空响应体");
            }

            return parseSseStream(responseBody.source(), streamListener);
        }
    }

    private ChatResponse parseSseStream(BufferedSource source, StreamListener listener) throws IOException {
        String role = "assistant";
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
        int inputTokens = 0, outputTokens = 0, cachedInputTokens = 0;

        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) break;

            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue;

            String payload = trimmed.substring(5).trim();
            if (payload.isEmpty()) continue;
            if ("[DONE]".equals(payload)) break;

            JsonNode root = mapper.readTree(payload);

            // Error check
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new IOException("API错误: " + error.path("message").asText(error.toString()));
            }

            // Usage
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                outputTokens = usage.path("completion_tokens").asInt(outputTokens);
                cachedInputTokens = usage.path("prompt_cache_hit_tokens").asInt(cachedInputTokens);
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) continue;

            JsonNode delta = choices.get(0).path("delta");
            if (delta.isMissingNode() || delta.isNull()) {
                delta = choices.get(0).path("message");
            }
            if (delta.isMissingNode() || delta.isNull()) continue;

            String deltaRole = delta.path("role").asText("");
            if (!deltaRole.isEmpty()) role = deltaRole;

            // Reasoning content
            String reasoningDelta = delta.path("reasoning_content").asText("");
            if (reasoningDelta.isEmpty()) {
                reasoningDelta = delta.path("reasoning").asText("");
            }
            if (!reasoningDelta.isEmpty()) {
                reasoning.append(reasoningDelta);
                listener.onReasoningDelta(reasoningDelta);
            }

            // Content
            String contentDelta = delta.path("content").asText("");
            if (!contentDelta.isEmpty()) {
                content.append(contentDelta);
                listener.onContentDelta(contentDelta);
            }

            // Tool calls
            mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
        }

        List<ToolCall> toolCalls = buildToolCalls(toolAccumulators);
        if (content.isEmpty() && reasoning.isEmpty() && (toolCalls == null || toolCalls.isEmpty())) {
            throw new IOException("API返回空内容");
        }

        return new ChatResponse(role, content.toString(), reasoning.toString(),
                toolCalls, inputTokens, outputTokens, cachedInputTokens);
    }

    protected String buildRequestBody(List<Message> messages, List<Tool> tools) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", getModel());
            body.put("stream", true);

            ArrayNode msgArray = body.putArray("messages");
            for (Message msg : messages) {
                ObjectNode msgNode = msgArray.addObject();
                msgNode.put("role", msg.role());

                if (msg.content() != null) {
                    msgNode.put("content", msg.content());
                }

                // reasoning_content in history
                if (shouldSendReasoningContentInRequestHistory()
                        && msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                    msgNode.put("reasoning_content", msg.reasoningContent());
                }

                // tool_calls
                if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                    ArrayNode tcArray = msgNode.putArray("tool_calls");
                    for (ToolCall tc : msg.toolCalls()) {
                        ObjectNode tcNode = tcArray.addObject();
                        tcNode.put("id", tc.id());
                        tcNode.put("type", "function");
                        ObjectNode funcNode = tcNode.putObject("function");
                        funcNode.put("name", tc.function().name());
                        funcNode.put("arguments", tc.function().arguments());
                    }
                }

                // tool_call_id for tool messages
                if (msg.toolCallId() != null) {
                    msgNode.put("tool_call_id", msg.toolCallId());
                }
            }

            // Tools
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsArray = body.putArray("tools");
                for (Tool tool : tools) {
                    ObjectNode toolNode = toolsArray.addObject();
                    toolNode.put("type", "function");
                    ObjectNode funcNode = toolNode.putObject("function");
                    funcNode.put("name", tool.name());
                    funcNode.put("description", tool.description());
                    if (tool.parameters() != null) {
                        funcNode.set("parameters", tool.parameters());
                    }
                }
            }

            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("构建请求体失败", e);
        }
    }

    // ===== Tool call 累加器 =====

    private static class ToolCallAccumulator {
        int index;
        String id = "";
        String name = "";
        StringBuilder arguments = new StringBuilder();
    }

    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode.isMissingNode() || !toolCallsNode.isArray()) return;

        for (JsonNode tcDelta : toolCallsNode) {
            int index = tcDelta.path("index").asInt(0);

            while (accumulators.size() <= index) {
                ToolCallAccumulator acc = new ToolCallAccumulator();
                acc.index = accumulators.size();
                accumulators.add(acc);
            }

            ToolCallAccumulator acc = accumulators.get(index);

            JsonNode idNode = tcDelta.path("id");
            if (!idNode.isMissingNode() && !idNode.isNull()) {
                acc.id = idNode.asText();
            }

            JsonNode funcNode = tcDelta.path("function");
            if (!funcNode.isMissingNode()) {
                JsonNode nameNode = funcNode.path("name");
                if (!nameNode.isMissingNode() && !nameNode.isNull()) {
                    acc.name += nameNode.asText();
                }
                JsonNode argsNode = funcNode.path("arguments");
                if (!argsNode.isMissingNode() && !argsNode.isNull()) {
                    acc.arguments.append(argsNode.asText());
                }
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) return null;
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id.isEmpty() && acc.name.isEmpty()) continue;
            calls.add(new ToolCall(acc.id, new ToolCall.Function(acc.name, acc.arguments.toString())));
        }
        return calls.isEmpty() ? null : calls;
    }
}
