package com.myagent.memory;

import com.myagent.llm.LlmClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 对话历史压缩器 - 当 conversationHistory 接近窗口上限时自动压缩
 *
 * 算法：保留最近 N 轮 user message，早期消息调 LLM 生成摘要
 * 关键约束：分割点必须在 user message 边界，避免切断 tool_call/tool_result 对
 */
public class ConversationHistoryCompactor {

    private static final int RETAIN_RECENT_ROUNDS = 3;

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户的关键诉求与目标
            2. Agent 已完成的关键操作和结果
            3. 已达成的结论
            4. 仍未解决的问题
            输出 1-3 段中文，不要复述原文。

            === 待压缩的对话 ===
            %s
            """;

    private LlmClient llmClient;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 按需压缩 conversationHistory，原地修改
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateTokens(history);
        if (currentTokens < triggerTokens) return false;
        return compact(history);
    }

    public boolean compactNow(List<LlmClient.Message> history) {
        if (history == null || history.size() <= 2) return false;
        return compact(history);
    }

    private boolean compact(List<LlmClient.Message> history) {
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;

        // 找所有 user message 的索引
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                userIndices.add(i);
            }
        }
        if (userIndices.size() <= RETAIN_RECENT_ROUNDS) return false;

        int splitIdx = userIndices.get(userIndices.size() - RETAIN_RECENT_ROUNDS);
        if (splitIdx <= systemEnd) return false;

        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (IOException e) {
            return false;
        }
        if (summary == null || summary.isBlank()) return false;

        // 重建历史
        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        history.clear();
        history.addAll(rebuilt);
        return true;
    }

    private String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) throw new IOException("LLM client not configured");

        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) sb.append(m.content());
            sb.append("\n\n");
            if (sb.length() > 60_000) {
                sb.append("...(已截断)\n");
                break;
            }
        }

        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个对话摘要助手，只输出摘要本身。"),
                LlmClient.Message.user(String.format(SUMMARY_PROMPT, sb))
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }
}
