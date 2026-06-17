package com.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myagent.llm.LlmClient;
import com.myagent.tool.ToolOutput;
import com.myagent.tool.ToolRegistry;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client - 通过 JSON-RPC 2.0 与 MCP Server 通信
 *
 * 生命周期：
 * 1. 创建 McpTransport（启动子进程）
 * 2. initialize 握手
 * 3. initialized 通知
 * 4. tools/list 获取工具列表
 * 5. tools/call 调用工具
 * 6. close 关闭连接
 */
public class McpClient implements AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String serverName;
    private final McpTransport transport;
    private boolean initialized = false;

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    /**
     * MCP 握手：initialize → initialized 通知
     */
    public void initialize() throws IOException {
        // Step 1: initialize 请求
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");

        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "my-agent");
        clientInfo.put("version", "1.0.0");

        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("tools");  // 声明支持 tools

        JsonNode result = transport.send("initialize", params);

        // Step 2: initialized 通知（不需要响应）
        transport.notify("notifications/initialized", mapper.createObjectNode());

        initialized = true;
    }

    /**
     * 获取 Server 暴露的工具列表
     */
    public List<McpToolDescriptor> listTools() throws IOException {
        if (!initialized) throw new IOException("MCP Client 未初始化");

        JsonNode result = transport.send("tools/list", mapper.createObjectNode());
        JsonNode tools = result.path("tools");

        List<McpToolDescriptor> descriptors = new ArrayList<>();
        if (tools.isArray()) {
            for (JsonNode tool : tools) {
                String name = tool.path("name").asText("");
                String desc = tool.path("description").asText("");
                JsonNode inputSchema = tool.path("inputSchema");

                descriptors.add(new McpToolDescriptor(
                        serverName,
                        name,
                        desc,
                        inputSchema.isMissingNode() ? null : inputSchema
                ));
            }
        }
        return descriptors;
    }

    /**
     * 调用 MCP Server 的工具
     *
     * @param toolName  工具名
     * @param arguments 参数 JSON 字符串
     * @return 工具执行结果
     */
    public String callTool(String toolName, String arguments) throws IOException {
        if (!initialized) throw new IOException("MCP Client 未初始化");

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);

        JsonNode argsNode = mapper.readTree(arguments == null ? "{}" : arguments);
        params.set("arguments", argsNode);

        JsonNode result = transport.send("tools/call", params);

        // 提取结果文本
        JsonNode content = result.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    sb.append(item.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return result.toString();
    }

    public String getServerName() { return serverName; }
    public boolean isInitialized() { return initialized; }
    public boolean isAlive() { return transport.isAlive(); }

    @Override
    public void close() {
        transport.close();
        initialized = false;
    }
}
