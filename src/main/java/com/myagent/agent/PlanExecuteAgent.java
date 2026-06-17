package com.myagent.agent;

import com.myagent.llm.LlmClient;
import com.myagent.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plan-and-Execute Agent - 先拆解任务为多步计划，再逐步执行
 *
 * 每一步内部复用 ReAct Agent
 */
public class PlanExecuteAgent {

    private static final String PLAN_PROMPT = """
            请将以下任务拆解为 3-5 个具体步骤，每步一行。
            要求：
            - 步骤要具体可执行
            - 按依赖顺序排列
            - 不要输出其他内容

            格式：
            1. [步骤描述]
            2. [步骤描述]
            3. [步骤描述]

            任务：%s
            """;

    private final LlmClient llmClient;
    private final Agent reactAgent;
    private final PrintStream out;

    public PlanExecuteAgent(LlmClient llmClient, Agent reactAgent) {
        this(llmClient, reactAgent, System.out);
    }

    public PlanExecuteAgent(LlmClient llmClient, Agent reactAgent, PrintStream out) {
        this.llmClient = llmClient;
        this.reactAgent = reactAgent;
        this.out = out;
    }

    public String run(String task) {
        // Step 1: 生成计划
        out.println("📋 正在生成执行计划...\n");
        List<String> steps;
        try {
            LlmClient.ChatResponse planResponse = llmClient.chat(
                    List.of(
                            LlmClient.Message.system("你是一个任务规划助手，只输出步骤列表。"),
                            LlmClient.Message.user(String.format(PLAN_PROMPT, task))
                    ),
                    null
            );
            steps = parseSteps(planResponse.content());
        } catch (IOException e) {
            return "❌ 生成计划失败: " + e.getMessage();
        }

        if (steps.isEmpty()) {
            return "❌ 未能生成有效计划";
        }

        // Step 2: 展示计划
        out.println("📋 执行计划：");
        for (int i = 0; i < steps.size(); i++) {
            out.println("  " + (i + 1) + ". " + steps.get(i));
        }
        out.println();

        // Step 3: 逐步执行
        StringBuilder allResults = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            out.println("▶ 步骤 " + (i + 1) + "/" + steps.size() + ": " + steps.get(i));
            out.println("─".repeat(40));

            String stepResult = reactAgent.run(steps.get(i));
            allResults.append("## 步骤 ").append(i + 1).append(": ").append(steps.get(i)).append("\n");
            allResults.append(stepResult).append("\n\n");

            out.println("✅ 步骤 " + (i + 1) + " 完成\n");
        }

        return "✅ 计划执行完成（" + steps.size() + " 步）\n\n" + allResults;
    }

    private List<String> parseSteps(String content) {
        if (content == null || content.isBlank()) return List.of();

        List<String> steps = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                steps.add(trimmed.replaceFirst("^\\d+\\.\\s+", ""));
            }
        }
        return steps;
    }
}
