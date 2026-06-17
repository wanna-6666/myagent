package com.myagent.cli;

import com.myagent.agent.Agent;
import com.myagent.agent.PlanExecuteAgent;
import com.myagent.llm.DeepSeekClient;
import com.myagent.llm.LlmClient;
import com.myagent.llm.LlmClientFactory;
import com.myagent.mcp.McpServerManager;
import com.myagent.rag.CodeIndex;
import com.myagent.tool.ToolRegistry;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * My Agent - 终端 AI 编程助手入口
 */
public class Main {

    private static final String VERSION = "1.0.0";

    // ANSI 颜色
    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String RED    = "\033[31m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String BLUE   = "\033[34m";
    private static final String MAGENTA = "\033[35m";
    private static final String CYAN   = "\033[36m";
    private static final String GRAY   = "\033[90m";
    private static final String BG_BLUE = "\033[44m";
    private static final String WHITE  = "\033[97m";

    public static void main(String[] args) {
        // 加载 .env 配置
        Properties env = loadEnv();

        // 创建 LLM 客户端（通过工厂模式，支持多 provider）
        LlmClient llmClient = LlmClientFactory.createDefault(env);
        if (llmClient == null) {
            System.err.println(RED + "❌ 未找到可用的 API Key，请在 .env 中配置至少一个：" + RESET);
            System.err.println("   DEEPSEEK_API_KEY / GLM_API_KEY / KIMI_API_KEY / QWEN_API_KEY");
            System.exit(1);
        }
        ToolRegistry toolRegistry = new ToolRegistry(System.getProperty("user.dir"));
        Agent agent = new Agent(llmClient, toolRegistry);

        // MCP
        McpServerManager mcpManager = new McpServerManager(toolRegistry);
        Runtime.getRuntime().addShutdownHook(new Thread(mcpManager::closeAll, "mcp-shutdown"));

        // RAG
        String embeddingUrl = env.getProperty("EMBEDDING_API_URL", "");
        String embeddingKey = env.getProperty("EMBEDDING_API_KEY",
                env.getProperty("DEEPSEEK_API_KEY", ""));
        String embeddingModel = env.getProperty("EMBEDDING_MODEL", "text-embedding-3-small");
        CodeIndex codeIndex = new CodeIndex(
                System.getProperty("user.dir"), embeddingUrl, embeddingKey, embeddingModel);

        // 终端
        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            // 启动 MCP Servers
            String mcpStatus = mcpManager.startAll();

            // 启动画面
            printStartup(llmClient, toolRegistry, mcpManager);

            // 流式输出 + 思考指示器
            final boolean[] thinking = {false};
            agent.setStreamListener(new LlmClient.StreamListener() {
                @Override
                public void onContentDelta(String delta) {
                    if (thinking[0]) {
                        // 清除思考指示器
                        System.out.print("\r\033[2K"); // 清除当前行
                        thinking[0] = false;
                    }
                    System.out.print(delta);
                    System.out.flush();
                }
                @Override
                public void onReasoningDelta(String delta) {
                    if (!thinking[0]) {
                        System.out.print(GRAY + "  ● 思考中..." + RESET);
                        thinking[0] = true;
                    }
                }
            });

            boolean usePlanMode = false;

            while (true) {
                String input;
                try {
                    System.out.println();
                    String prompt = usePlanMode
                            ? CYAN + "📋 ❯ " + RESET
                            : BOLD + CYAN + "❯ " + RESET;
                    input = reader.readLine(prompt).trim();
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (input.isEmpty()) continue;

                // 斜杠命令
                if (input.startsWith("/")) {
                    String result = handleCommand(input, agent, codeIndex, llmClient, env, mcpManager);
                    if (result == null) break;  // /exit
                    if (result.equals("__PLAN_MODE__")) {
                        usePlanMode = true;
                        System.out.println(CYAN + "📋 下一条任务将使用 Plan-and-Execute 模式" + RESET);
                        continue;
                    }
                    System.out.println(result);
                    continue;
                }

                // 用户输入回显
                System.out.println(DIM + "─".repeat(Math.min(terminal.getWidth(), 60)) + RESET);

                // 执行 Agent
                String response;
                if (usePlanMode) {
                    PlanExecuteAgent planAgent = new PlanExecuteAgent(agent.getLlmClient(), agent);
                    response = planAgent.run(input);
                    usePlanMode = false;
                } else {
                    response = agent.run(input);
                }
                System.out.println();
                System.out.println(DIM + "─".repeat(Math.min(terminal.getWidth(), 60)) + RESET);
            }

            System.out.println("\n" + DIM + "👋 再见!" + RESET);
        } catch (IOException e) {
            System.err.println(RED + "❌ 终端初始化失败: " + e.getMessage() + RESET);
            System.exit(1);
        }
    }

    // ===== 启动画面 =====

    private static void printStartup(LlmClient llmClient, ToolRegistry toolRegistry, McpServerManager mcpManager) {
        System.out.println();

        // ASCII Banner
        System.out.println(CYAN + BOLD +
                "  ███╗   ███╗██╗   ██╗     █████╗  ██████╗ ███████╗███╗   ██╗████████╗" + RESET);
        System.out.println(CYAN + BOLD +
                "  ████╗ ████║╚██╗ ██╔╝    ██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝" + RESET);
        System.out.println(CYAN + BOLD +
                "  ██╔████╔██║ ╚████╔╝     ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║   " + RESET);
        System.out.println(CYAN + BOLD +
                "  ██║╚██╔╝██║  ╚██╔╝      ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║   " + RESET);
        System.out.println(CYAN + BOLD +
                "  ██║ ╚═╝ ██║   ██║       ██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║   " + RESET);
        System.out.println(CYAN + BOLD +
                "  ╚═╝     ╚═╝   ╚═╝       ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝   " + RESET);
        System.out.println();

        // 版本和模型
        System.out.println("  " + DIM + "v" + VERSION + RESET + "  " +
                BG_BLUE + WHITE + " " + llmClient.getModelName() + " " + RESET + "  " +
                DIM + llmClient.getProviderName() + RESET);
        System.out.println();

        // 工具列表
        System.out.println("  " + BOLD + "工具" + RESET + "  " +
                GREEN + "●" + RESET + " read_file  " +
                GREEN + "●" + RESET + " write_file  " +
                GREEN + "●" + RESET + " list_dir  " +
                GREEN + "●" + RESET + " grep_code  " +
                GREEN + "●" + RESET + " execute_command");
        System.out.println();

        // 使用提示
        System.out.println("  " + DIM + "提示" + RESET);
        System.out.println("  " + DIM + "├─" + RESET + " 输入你的问题，Agent 会自动调用工具完成任务");
        System.out.println("  " + DIM + "├─" + RESET + " /plan 切换到计划模式（复杂任务先拆解再执行）");
        System.out.println("  " + DIM + "├─" + RESET + " /index 索引代码库，/search 语义搜索");
        System.out.println("  " + DIM + "├─" + RESET + " /mcp 管理 MCP Server，/model 切换模型");
        System.out.println("  " + DIM + "└─" + RESET + " /save 保存长期记忆，/exit 退出");
        System.out.println();

        // MCP 状态
        String mcpStatus = mcpManager.formatStatus();
        if (!mcpStatus.equals("没有运行中的 MCP Server")) {
            System.out.println("  " + BOLD + "MCP" + RESET);
            for (String line : mcpStatus.split("\n")) {
                System.out.println("  " + DIM + line + RESET);
            }
            System.out.println();
        }
    }

    // ===== 斜杠命令处理 =====

    private static String handleCommand(String input, Agent agent, CodeIndex codeIndex,
                                        LlmClient llmClient, Properties env, McpServerManager mcpManager) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String payload = parts.length > 1 ? parts[1] : "";

        return switch (cmd) {
            case "/exit", "/quit" -> null;
            case "/clear" -> {
                agent.clearHistory();
                yield GREEN + "🗑️ 对话历史已清空" + RESET;
            }
            case "/plan" -> {
                if (payload.isEmpty()) {
                    yield "__PLAN_MODE__";
                }
                PlanExecuteAgent planAgent = new PlanExecuteAgent(agent.getLlmClient(), agent);
                yield planAgent.run(payload);
            }
            case "/model" -> {
                if (payload.isEmpty()) {
                    // 显示当前模型和已配置的 provider
                    yield CYAN + "🤖 当前模型: " + RESET + BOLD + agent.getLlmClient().getModelName()
                            + RESET + " (" + agent.getLlmClient().getProviderName() + ")\n\n"
                            + CYAN + "已配置的 Provider:" + RESET + "\n"
                            + LlmClientFactory.listConfigured(env) + "\n\n"
                            + DIM + "切换: /model deepseek | /model glm | /model kimi | /model qwen" + RESET;
                }
                LlmClient newClient = LlmClientFactory.create(payload, env);
                if (newClient == null) {
                    yield RED + "❌ 切换失败: 未配置 " + payload + " 的 API Key" + RESET
                            + "\n请在 .env 中添加 " + payload.toUpperCase() + "_API_KEY";
                }
                agent.setLlmClient(newClient);
                yield GREEN + "✅ 已切换到: " + RESET + BOLD + newClient.getModelName()
                        + RESET + " (" + newClient.getProviderName() + ")\n"
                        + DIM + "上下文窗口: " + String.format("%,d", newClient.maxContextWindow()) + " tokens" + RESET
                        + "\n" + DIM + "对话历史已保留，使用 /clear 可清空" + RESET;
            }
            case "/context" -> CYAN + "📋 上下文状态" + RESET + ":\n"
                    + "模型: " + agent.getLlmClient().getModelName() + "\n"
                    + agent.getMemoryManager().getStatus();
            case "/save" -> {
                if (payload.isEmpty()) {
                    yield YELLOW + "❌ 用法: /save <事实> 或 /save --global <事实>" + RESET;
                }
                String scope = "project";
                String fact = payload;
                if (payload.startsWith("--global ")) {
                    scope = "global";
                    fact = payload.substring(9);
                }
                agent.getMemoryManager().storeFact(fact, scope);
                yield GREEN + "💾 已保存到长期记忆(" + scope + "): " + RESET + fact;
            }
            case "/memory" -> {
                var entries = agent.getMemoryManager().getLongTermMemory().getAll();
                if (entries.isEmpty()) yield DIM + "📭 长期记忆为空" + RESET;
                StringBuilder sb = new StringBuilder(CYAN + "📋 长期记忆 " + RESET + "(" + entries.size() + " 条):\n");
                for (var e : entries) {
                    sb.append("  ").append(DIM).append("•").append(RESET).append(" ")
                            .append(BLUE).append("[").append(e.metadata().getOrDefault("scope", "?")).append("]").append(RESET).append(" ")
                            .append(e.content()).append("\n");
                }
                yield sb.toString().trim();
            }
            case "/index" -> {
                yield codeIndex.indexProject(System.out);
            }
            case "/search" -> {
                if (payload.isEmpty()) yield YELLOW + "❌ 用法: /search <查询>" + RESET;
                else yield codeIndex.hybridSearch(payload, 5);
            }
            case "/mcp" -> {
                if (payload.equals("start")) {
                    yield CYAN + "🔄 启动 MCP Servers..." + RESET + "\n" + mcpManager.startAll();
                } else if (payload.equals("stop")) {
                    mcpManager.closeAll();
                    yield GREEN + "🔴 所有 MCP Server 已关闭" + RESET;
                } else {
                    yield CYAN + "🔌 MCP Server 状态" + RESET + "\n" + mcpManager.formatStatus()
                            + "\n\n" + DIM + "命令: /mcp start | /mcp stop" + RESET
                            + "\n" + DIM + "配置: ~/.myagent/mcp.json 或 .myagent/mcp.json" + RESET;
                }
            }
            default -> RED + "❌ 未知命令: " + cmd + RESET +
                    "\n可用: /plan /model /mcp /clear /context /save /memory /index /search /exit";
        };
    }

    // ===== .env 加载 =====

    private static Properties loadEnv() {
        Properties props = new Properties();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try {
                for (String line : Files.readAllLines(envFile)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        props.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            } catch (IOException ignored) {}
        }
        return props;
    }
}
