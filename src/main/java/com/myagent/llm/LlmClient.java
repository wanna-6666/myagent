package com.myagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 客户端接口 - 定义与大模型交互的标准协议
 */
public interface LlmClient {

    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;

    String getModelName();

    String getProviderName();

    default int maxContextWindow() {
        return 128_000;
    }

    default boolean supportsPromptCaching() {
        return false;
    }

    default boolean supportsTools() {
        return true;
    }

    // ===== 消息模型 =====

    record ContentPart(String type, String text, String imageBase64, String imageUrl, String mimeType) {
        public static ContentPart text(String text) {
            return new ContentPart("text", text, null, null, null);
        }

        public boolean isText() { return "text".equals(type); }
    }

    record Message(String role, String content, String reasoningContent,
                   List<ToolCall> toolCalls, String toolCallId,
                   List<ContentPart> contentParts) {

        public Message(String role, String content, String reasoningContent,
                       List<ToolCall> toolCalls, String toolCallId) {
            this(role, content, reasoningContent, toolCalls, toolCallId, null);
        }

        public Message(String role, String content) {
            this(role, content, null, null, null, null);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message assistant(String reasoningContent, String content) {
            return new Message("assistant", content, reasoningContent, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls, null);
        }

        public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, reasoningContent, toolCalls, null);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, null, null, toolCallId);
        }

        public boolean hasContentParts() {
            return contentParts != null && !contentParts.isEmpty();
        }
    }

    record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {}
    }

    record Tool(String name, String description, JsonNode parameters) {}

    interface StreamListener {
        StreamListener NO_OP = new StreamListener() {};
        default void onReasoningDelta(String delta) {}
        default void onContentDelta(String delta) {}
    }

    record ChatResponse(String role, String content, String reasoningContent,
                        List<ToolCall> toolCalls,
                        int inputTokens, int outputTokens, int cachedInputTokens) {

        public ChatResponse(String role, String content, List<ToolCall> toolCalls,
                            int inputTokens, int outputTokens) {
            this(role, content, null, toolCalls, inputTokens, outputTokens, 0);
        }

        public ChatResponse(String role, String content, String reasoningContent,
                            List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
            this(role, content, reasoningContent, toolCalls, inputTokens, outputTokens, 0);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}
