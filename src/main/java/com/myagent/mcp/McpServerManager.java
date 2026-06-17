package com.myagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.tool.ToolRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Server 管理器 - 加载配置、启动 Server、注册工具到 ToolRegistry
 *
 * 配置文件格式（~/.myagent/mcp.json 或项目 .myagent/mcp.json）：
 * {
 *   "mcpServers": {
 *     "filesystem": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
 *     },
 *     "fetch": {
 *       "command": "uvx",
 *       "args": ["mcp-server-fetch"]
 *     }
 *   }
 * }
 */
public class McpServerManager {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;

    public McpServerManager(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 加载配置并启动所有 MCP Server
     */
    public String startAll() {
        Map<String, McpServerConfig> configs = loadConfigs();
        if (configs.isEmpty()) {
            return "未找到 MCP 配置文件";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();
            try {
                startServer(name, config);
                sb.append("✅ ").append(name).append(" (").append(listToolsCount(name)).append(" tools)\n");
            } catch (Exception e) {
                sb.append("❌ ").append(name).append(": ").append(e.getMessage()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 启动单个 MCP Server
     */
    public void startServer(String name, McpServerConfig config) throws IOException {
        // 关闭已有的
        if (clients.containsKey(name)) {
            clients.get(name).close();
        }

        // 创建传输层（启动子进程）
        McpTransport transport = new McpTransport(
                config.command(),
                config.args().toArray(new String[0]),
                config.workDir()
        );

        // 创建 Client 并握手
        McpClient client = new McpClient(name, transport);
        client.initialize();
        clients.put(name, client);

        // 获取工具列表并注册到 ToolRegistry
        registerTools(name, client);
    }

    /**
     * 将 MCP Server 的工具注册到 ToolRegistry
     */
    private void registerTools(String serverName, McpClient client) throws IOException {
        List<McpToolDescriptor> tools = client.listTools();
        for (McpToolDescriptor tool : tools) {
            String qualifiedName = tool.qualifiedName();  // mcp__server__tool

            // 注册到 ToolRegistry，执行时转发给 MCP Client
            toolRegistry.registerMcpTool(qualifiedName, tool.description(),
                    tool.parameters(),
                    args -> {
                        try {
                            return client.callTool(tool.toolName(), args);
                        } catch (IOException e) {
                            return "MCP 工具调用失败: " + e.getMessage();
                        }
                    });
        }
    }

    /**
     * 关闭所有 MCP Server
     */
    public void closeAll() {
        clients.values().forEach(McpClient::close);
        clients.clear();
    }

    /**
     * 获取所有 Server 状态
     */
    public String formatStatus() {
        if (clients.isEmpty()) return "没有运行中的 MCP Server";

        StringBuilder sb = new StringBuilder("MCP Servers:\n");
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            String status = client.isAlive() ? "🟢 running" : "🔴 stopped";
            sb.append("  ").append(status).append(" ").append(entry.getKey()).append("\n");
        }
        return sb.toString().trim();
    }

    private String listToolsCount(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null) return "0";
        try {
            return String.valueOf(client.listTools().size());
        } catch (Exception e) {
            return "?";
        }
    }

    // ===== 配置加载 =====

    private Map<String, McpServerConfig> loadConfigs() {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();

        // 用户级配置
        loadConfigFile(Path.of(System.getProperty("user.home"), ".myagent", "mcp.json"), configs);
        // 项目级配置
        loadConfigFile(Path.of(".myagent", "mcp.json"), configs);

        return configs;
    }

    @SuppressWarnings("unchecked")
    private void loadConfigFile(Path path, Map<String, McpServerConfig> configs) {
        if (!Files.exists(path)) return;
        try {
            var root = mapper.readTree(path.toFile());
            var servers = root.path("mcpServers");
            if (servers.isMissingNode()) return;

            servers.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                var node = entry.getValue();
                String command = node.path("command").asText("");
                List<String> args = new ArrayList<>();
                node.path("args").forEach(a -> args.add(a.asText()));
                String workDir = node.path("workDir").asText(null);

                if (!command.isEmpty()) {
                    configs.put(name, new McpServerConfig(command, args, workDir));
                }
            });
        } catch (IOException ignored) {}
    }

    public record McpServerConfig(String command, List<String> args, String workDir) {}
}
