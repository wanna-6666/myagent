package com.myagent.mcp;

/**
 * MCP 工具描述 - 从 MCP Server 的 tools/list 响应中解析
 */
public record McpToolDescriptor(
        String serverName,    // 所属 server 名称
        String toolName,      // 工具原始名称
        String description,   // 工具描述
        Object parameters     // JSON Schema（ObjectNode）
) {
    /**
     * 注册到 ToolRegistry 时的全限定名：mcp__{server}__{tool}
     */
    public String qualifiedName() {
        return "mcp__" + serverName + "__" + toolName;
    }
}
