package com.myagent.agent;

import com.myagent.llm.LlmClient;
import com.myagent.memory.ConversationHistoryCompactor;
import com.myagent.memory.MemoryManager;
import com.myagent.memory.TokenBudget;
import com.myagent.tool.ToolRegistry;
import com.myagent.tool.ToolRegistry.ToolExecutionResult;
import com.myagent.tool.ToolRegistry.ToolInvocation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent - Think → Act → Observe 循环
 */
public class Agent {

    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private LlmClient.StreamListener streamListener = LlmClient.StreamListener.NO_OP;

    private static final String SYSTEM_PROMPT = """
            你是一个智能编程助手，运行在终端环境中。你可以通过调用工具来完成用户的任务。

            可用工具会自动注册并在每轮对话中提供给你。请根据用户需求选择合适的工具。

            工作原则：
            1. 先理解用户需求，再动手操作
            2. 操作前先读取相关文件了解上下文
            3. 每次操作后检查结果，有问题及时修正
            4. 任务完成后给出清晰的总结

            当前工作目录：%s
            """;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        conversationHistory.add(LlmClient.Message.system(
                String.format(SYSTEM_PROMPT, toolRegistry.getProjectPath())));
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        memoryManager.setLlmClient(llmClient);
    }

    public void setStreamListener(LlmClient.StreamListener listener) {
        this.streamListener = listener;
    }

    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public MemoryManager getMemoryManager() { return memoryManager; }
    public List<LlmClient.Message> getConversationHistory() { return conversationHistory; }
    public LlmClient getLlmClient() { return llmClient; }

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(
                String.format(SYSTEM_PROMPT, toolRegistry.getProjectPath())));
        memoryManager.clearShortTerm();
    }

    /**
     * ReAct 主循环
     */
    public String run(String userInput) {
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);

        // 检索长期记忆，注入 system prompt
        String memoryContext = memoryManager.buildContextForQuery(userInput, 500);
        if (!memoryContext.isBlank()) {
            updateSystemPrompt(memoryContext);
        }

        // 添加用户消息
        conversationHistory.add(LlmClient.Message.user(userInput));

        AgentGuard guard = new AgentGuard();
        TokenBudget tokenBudget = memoryManager.getTokenBudget();

        while (true) {
            // ① 检查是否该停
            AgentGuard.StopReason reason = guard.shouldStop();
            if (reason != AgentGuard.StopReason.CONTINUE) {
                return "⏹️ " + guard.describe(reason);
            }
            guard.nextIteration();

            // ② 检查上下文是否需要压缩
            memoryManager.compactIfNeeded(conversationHistory);

            // ③ 调 LLM
            LlmClient.ChatResponse response;
            try {
                List<LlmClient.Tool> tools = llmClient.supportsTools()
                        ? toolRegistry.getToolDefinitions()
                        : null;
                response = llmClient.chat(conversationHistory, tools, streamListener);
            } catch (IOException e) {
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }

            // 记录 token
            tokenBudget.record(response.inputTokens(), response.outputTokens());
            guard.addTokens(response.inputTokens() + response.outputTokens());

            // ④ 有工具调用 → 执行并继续
            if (response.hasToolCalls()) {
                guard.recordToolCalls(response.toolCalls());

                // 添加助手消息（包含工具调用）
                conversationHistory.add(LlmClient.Message.assistant(
                        response.reasoningContent(), response.content(), response.toolCalls()));

                // 执行工具
                List<ToolInvocation> invocations = response.toolCalls().stream()
                        .map(tc -> new ToolInvocation(tc.id(), tc.function().name(), tc.function().arguments()))
                        .toList();
                List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);

                // 工具结果回灌
                for (ToolExecutionResult r : results) {
                    memoryManager.addToolResult(r.name(), r.result());
                    conversationHistory.add(LlmClient.Message.tool(r.id(), r.result()));
                }
                continue;
            }

            // ⑤ 没有工具调用 → 返回最终答案
            guard.recordToolCalls(null);
            String content = response.content() == null ? "" : response.content();
            conversationHistory.add(LlmClient.Message.assistant(content));
            memoryManager.addAssistantMessage(content);
            return content;
        }
    }

    private void updateSystemPrompt(String memoryContext) {
        String basePrompt = String.format(SYSTEM_PROMPT, toolRegistry.getProjectPath());
        String fullPrompt = memoryContext.isBlank() ? basePrompt : basePrompt + "\n\n" + memoryContext;
        conversationHistory.set(0, LlmClient.Message.system(fullPrompt));
    }
}
