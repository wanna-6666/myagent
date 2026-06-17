package com.myagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP stdio 传输层 - 通过子进程的 stdin/stdout 进行 JSON-RPC 通信
 *
 * 协议：每行一个 JSON 对象（JSON-RPC 2.0），以 \n 分隔
 */
public class McpTransport implements AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requestId = new AtomicInteger(1);

    private final Process process;
    private final BufferedWriter writer;   // → server stdin
    private final BufferedReader reader;   // ← server stdout

    public McpTransport(String command, String[] args, String workDir) throws IOException {
        String[] cmdArray = new String[args.length + 1];
        cmdArray[0] = command;
        System.arraycopy(args, 0, cmdArray, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        if (workDir != null) pb.directory(new File(workDir));
        pb.redirectErrorStream(false);  // stderr 独立，不混入 JSON-RPC 流

        this.process = pb.start();
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * 发送 JSON-RPC 请求并等待响应
     *
     * @param method JSON-RPC 方法名（如 "initialize", "tools/list", "tools/call"）
     * @param params 参数对象
     * @return 响应中的 result 字段
     */
    public JsonNode send(String method, JsonNode params) throws IOException {
        int id = requestId.getAndIncrement();

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        // 写入 server stdin
        String json = mapper.writeValueAsString(request);
        synchronized (writer) {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }

        // 读取 server stdout，跳过通知（无 id 的消息），直到匹配当前 id
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("MCP Server 连接已断开");
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            JsonNode response = mapper.readTree(line);

            // 跳过通知（没有 id 字段的消息，如 notifications）
            if (!response.has("id")) continue;

            int responseId = response.path("id").asInt(-1);
            if (responseId != id) continue;  // 不是我们的响应，继续读

            // 检查错误
            JsonNode error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new IOException("MCP 错误: " + error.path("message").asText(error.toString()));
            }

            return response.path("result");
        }
    }

    /**
     * 发送通知（不需要响应）
     */
    public void notify(String method, JsonNode params) throws IOException {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }

        String json = mapper.writeValueAsString(notification);
        synchronized (writer) {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try { writer.close(); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
