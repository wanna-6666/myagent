package com.myagent.memory;

import com.myagent.llm.LlmClient;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆管理器 - 统一管理短期记忆、长期记忆、上下文压缩
 */
public class MemoryManager {

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ConversationHistoryCompactor compactor;
    private final TokenBudget tokenBudget;
    private String currentProject;

    private static final int MAX_TOOL_RESULT_CHARS = 500;

    public MemoryManager(LlmClient llmClient) {
        int window = llmClient.maxContextWindow();
        this.shortTermMemory = new ConversationMemory(window / 4);
        this.longTermMemory = new LongTermMemory();
        this.compactor = new ConversationHistoryCompactor(llmClient);
        this.tokenBudget = new TokenBudget(window);
        this.currentProject = normalizeProject(System.getProperty("user.dir"));
    }

    public void setLlmClient(LlmClient llmClient) {
        compactor.setLlmClient(llmClient);
    }

    public void setProjectPath(String projectPath) {
        this.currentProject = normalizeProject(projectPath);
    }

    public void addUserMessage(String content) {
        shortTermMemory.store(new MemoryEntry(
                "user-" + shortId(), content,
                MemoryEntry.MemoryType.CONVERSATION, Map.of("source", "user")));
    }

    public void addAssistantMessage(String content) {
        shortTermMemory.store(new MemoryEntry(
                "assistant-" + shortId(), content,
                MemoryEntry.MemoryType.CONVERSATION, Map.of("source", "assistant")));
    }

    public void addToolResult(String toolName, String result) {
        String truncated = result.length() > MAX_TOOL_RESULT_CHARS
                ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "...(已截断)"
                : result;
        shortTermMemory.store(new MemoryEntry(
                "tool-" + shortId(), "[" + toolName + "] " + truncated,
                MemoryEntry.MemoryType.TOOL_RESULT, Map.of("source", "tool")));
    }

    public void storeFact(String fact, String scope) {
        longTermMemory.store(fact, scope, currentProject);
    }

    public String buildContextForQuery(String query, int maxTokens) {
        return longTermMemory.buildContextForQuery(query, maxTokens, currentProject);
    }

    public boolean compactIfNeeded(java.util.List<LlmClient.Message> history) {
        return compactor.compactIfNeeded(history, tokenBudget.available());
    }

    public boolean compactNow(java.util.List<LlmClient.Message> history) {
        return compactor.compactNow(history);
    }

    public void clearShortTerm() { shortTermMemory.clear(); }
    public void clearLongTerm() { longTermMemory.clear(); }

    public ConversationMemory getShortTermMemory() { return shortTermMemory; }
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }

    public String getStatus() {
        return shortTermMemory.getTokenCount() + " / " + shortTermMemory.getMaxTokens() + " tokens (短期)\n"
                + longTermMemory.getStatusSummary();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String normalizeProject(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path;
        }
    }
}
