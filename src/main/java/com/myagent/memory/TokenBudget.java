package com.myagent.memory;

import com.myagent.llm.LlmClient;

import java.util.List;

/**
 * Token 预算管理 - 确保对话不超出模型上下文窗口
 */
public class TokenBudget {

    private final int contextWindow;
    private final int reservedForSystem;
    private final int reservedForTools;
    private final int reservedForResponse;

    private int totalInputTokens;
    private int totalOutputTokens;
    private int callCount;

    public TokenBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2000);
    }

    public TokenBudget(int contextWindow, int reservedForSystem, int reservedForTools, int reservedForResponse) {
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
    }

    /** 对话历史可用预算 */
    public int available() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    /** 是否需要压缩（占用率超过 80%） */
    public boolean needsCompression(List<LlmClient.Message> history) {
        return estimateTokens(history) > available() * 0.8;
    }

    /** 估算消息列表的 token 数 */
    public static int estimateTokens(List<LlmClient.Message> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (LlmClient.Message msg : messages) {
            total += estimateTokens(msg.content());
            if (msg.toolCalls() != null) {
                for (LlmClient.ToolCall tc : msg.toolCalls()) {
                    total += estimateTokens(tc.function().arguments());
                }
            }
            total += 4; // role/separator overhead
        }
        return total;
    }

    /** Token 估算：中英文混合经验值，字符数 / 3 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 3);
    }

    public void record(int inputTokens, int outputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        callCount++;
    }

    public String report() {
        return String.format("LLM 调用 %d 次 | 输入 %d | 输出 %d | 总计 %d / %d",
                callCount, totalInputTokens, totalOutputTokens,
                totalInputTokens + totalOutputTokens, contextWindow);
    }

    public int getContextWindow() { return contextWindow; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public int getCallCount() { return callCount; }
}
