package com.myagent.tool;

import com.myagent.llm.LlmClient;

import java.util.List;

/**
 * 工具执行输出
 */
public record ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of());
    }

    public static ToolOutput empty() {
        return new ToolOutput("", List.of());
    }
}
