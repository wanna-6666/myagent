package com.myagent.agent;

import com.myagent.llm.LlmClient;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先拆解任务为 DAG 计划，再按依赖关系调度执行
 *
 * 支持两种执行模式：
 * 1. 顺序模式：步骤按顺序逐个执行（无依赖信息时降级）
 * 2. DAG 模式：按依赖关系拓扑排序，无依赖的步骤并行执行
 *
 * 每一步内部复用 ReAct Agent
 */
public class PlanExecuteAgent {

    private static final String PLAN_PROMPT = """
            请将以下任务拆解为 3-5 个具体步骤，每个步骤需要声明依赖关系。

            要求：
            - 步骤要具体可执行
            - 没有依赖的步骤标记 dependsOn 为空，可以并行执行
            - 有依赖的步骤必须等依赖完成才能执行
            - 只输出 JSON，不要输出其他内容

            格式（JSON 数组）：
            [
              {"id": "t1", "description": "查看项目目录结构", "dependsOn": []},
              {"id": "t2", "description": "搜索所有Java源文件", "dependsOn": ["t1"]},
              {"id": "t3", "description": "读取pom.xml分析依赖", "dependsOn": []},
              {"id": "t4", "description": "生成架构文档", "dependsOn": ["t2", "t3"]}
            ]

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
        // Step 1: 让 LLM 生成 DAG 计划
        out.println("📋 正在生成执行计划...\n");
        List<DagTask> dagTasks;
        try {
            LlmClient.ChatResponse planResponse = llmClient.chat(
                    List.of(
                            LlmClient.Message.system("你是一个任务规划助手，只输出 JSON 格式的步骤列表。"),
                            LlmClient.Message.user(String.format(PLAN_PROMPT, task))
                    ),
                    null
            );
            dagTasks = parseDagTasks(planResponse.content());
        } catch (IOException e) {
            return "❌ 生成计划失败: " + e.getMessage();
        }

        if (dagTasks.isEmpty()) {
            return "❌ 未能生成有效计划";
        }

        // Step 2: 展示计划
        out.println("📋 执行计划（DAG）：");
        for (DagTask t : dagTasks) {
            String deps = t.dependsOn().isEmpty() ? "无依赖（可并行）" : "依赖 " + t.dependsOn();
            out.println("  " + t.id() + ". " + t.description() + "  [" + deps + "]");
        }
        out.println();

        // Step 3: DAG 调度执行
        return executeDag(dagTasks);
    }

    /**
     * DAG 调度器 - 按拓扑序执行，无依赖的步骤并行
     */
    private String executeDag(List<DagTask> tasks) {
        Set<String> completed = new HashSet<>();
        Map<String, String> results = new LinkedHashMap<>();
        int totalSteps = tasks.size();
        int doneSteps = 0;

        while (completed.size() < tasks.size()) {
            // 找出所有依赖已完成的就绪任务
            List<DagTask> ready = tasks.stream()
                    .filter(t -> !completed.contains(t.id()))
                    .filter(t -> completed.containsAll(t.dependsOn()))
                    .toList();

            if (ready.isEmpty()) {
                // 没有就绪任务但有未完成的 → 循环依赖
                return "❌ 检测到循环依赖，无法继续执行";
            }

            if (ready.size() == 1) {
                // 单个任务：直接同步执行
                DagTask task = ready.get(0);
                doneSteps++;
                out.println("▶ 步骤 " + doneSteps + "/" + totalSteps + " [" + task.id() + "]: " + task.description());
                out.println("─".repeat(40));

                String result = reactAgent.run(task.description());
                results.put(task.id(), result);
                completed.add(task.id());

                out.println("✅ [" + task.id() + "] 完成\n");
            } else {
                // 多个就绪任务：并行执行
                out.println("▶ 并行执行 " + ready.size() + " 个步骤: " +
                        ready.stream().map(DagTask::id).collect(Collectors.joining(", ")));
                out.println("─".repeat(40));

                ExecutorService executor = Executors.newFixedThreadPool(
                        Math.min(ready.size(), 4),
                        r -> { Thread t = new Thread(r, "dag-executor"); t.setDaemon(true); return t; }
                );

                try {
                    List<Future<Map.Entry<String, String>>> futures = new ArrayList<>();
                    for (DagTask task : ready) {
                        futures.add(executor.submit(() -> {
                            String result = reactAgent.run(task.description());
                            return Map.entry(task.id(), result);
                        }));
                    }

                    for (int i = 0; i < futures.size(); i++) {
                        try {
                            Map.Entry<String, String> entry = futures.get(i).get(120, TimeUnit.SECONDS);
                            results.put(entry.getKey(), entry.getValue());
                            completed.add(entry.getKey());
                            doneSteps++;
                            out.println("  ✅ [" + entry.getKey() + "] 完成");
                        } catch (Exception e) {
                            String failedId = ready.get(i).id();
                            results.put(failedId, "❌ 执行失败: " + e.getMessage());
                            completed.add(failedId);
                            doneSteps++;
                            out.println("  ❌ [" + failedId + "] 失败: " + e.getMessage());
                        }
                    }
                } finally {
                    executor.shutdownNow();
                }
                out.println();
            }
        }

        // 汇总结果
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 计划执行完成（").append(totalSteps).append(" 步）\n\n");
        for (Map.Entry<String, String> entry : results.entrySet()) {
            DagTask task = tasks.stream().filter(t -> t.id().equals(entry.getKey())).findFirst().orElse(null);
            String desc = task != null ? task.description() : entry.getKey();
            sb.append("## ").append(entry.getKey()).append(": ").append(desc).append("\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 为 DagTask 列表
     */
    private List<DagTask> parseDagTasks(String content) {
        if (content == null || content.isBlank()) return List.of();

        try {
            // 提取 JSON 数组（LLM 可能在 JSON 前后加了多余文字）
            String json = content;
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = content.substring(start, end + 1);
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var array = mapper.readTree(json);

            List<DagTask> tasks = new ArrayList<>();
            for (var node : array) {
                String id = node.path("id").asText("t" + (tasks.size() + 1));
                String desc = node.path("description").asText("");
                List<String> deps = new ArrayList<>();
                node.path("dependsOn").forEach(d -> deps.add(d.asText()));
                if (!desc.isBlank()) {
                    tasks.add(new DagTask(id, desc, deps));
                }
            }
            return tasks;
        } catch (Exception e) {
            // JSON 解析失败，降级为顺序模式
            out.println("⚠️ DAG 解析失败，降级为顺序模式: " + e.getMessage());
            return parseFallback(content);
        }
    }

    /**
     * 降级：LLM 没返回有效 JSON 时，按 "1. xxx" 格式解析为顺序任务
     */
    private List<DagTask> parseFallback(String content) {
        List<DagTask> tasks = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                String desc = trimmed.replaceFirst("^\\d+\\.\\s+", "");
                String id = "t" + (tasks.size() + 1);
                // 顺序模式：每步依赖上一步
                List<String> deps = tasks.isEmpty() ? List.of() : List.of(tasks.get(tasks.size() - 1).id());
                tasks.add(new DagTask(id, desc, deps));
            }
        }
        return tasks;
    }
}
