package com.myagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myagent.llm.LlmClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 工具注册表 - 管理所有可用工具，支持并行执行
 */
public class ToolRegistry {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_PARALLEL = 4;
    private static final int COMMAND_TIMEOUT_SECONDS = 60;
    private static final int BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_COMMAND_OUTPUT = 8_000;
    private static final int MAX_WRITE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_GREP_RESULTS = 200;

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}
    public record ToolInvocation(String id, String name, String argumentsJson) {}
    public record ToolExecutionResult(String id, String name, String result, long elapsedMillis) {}

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private String projectPath;
    private PathGuard pathGuard;

    public ToolRegistry() {
        this(System.getProperty("user.dir"));
    }

    public ToolRegistry(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        registerFileTools();
        registerShellTools();
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
    }

    public String getProjectPath() { return projectPath; }

    // ===== 工具定义（发给 LLM） =====

    public List<LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    // ===== 执行工具 =====

    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) return List.of();

        if (invocations.size() == 1) {
            long start = System.nanoTime();
            ToolInvocation inv = invocations.get(0);
            String result = executeOne(inv.name(), inv.argumentsJson());
            return List.of(new ToolExecutionResult(inv.id(), inv.name(), result, elapsedMs(start)));
        }

        // 并行执行
        int parallelism = Math.min(invocations.size(), MAX_PARALLEL);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "tool-executor");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .map(inv -> (Callable<ToolExecutionResult>) () -> {
                        long start = System.nanoTime();
                        String result = executeOne(inv.name(), inv.argumentsJson());
                        return new ToolExecutionResult(inv.id(), inv.name(), result, elapsedMs(start));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation inv = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                try {
                    if (future.isCancelled()) {
                        results.add(new ToolExecutionResult(inv.id(), inv.name(),
                                "工具执行超时（" + BATCH_TIMEOUT_SECONDS + "秒）", BATCH_TIMEOUT_SECONDS * 1000L));
                    } else {
                        results.add(future.get());
                    }
                } catch (Exception e) {
                    results.add(new ToolExecutionResult(inv.id(), inv.name(),
                            "工具执行失败: " + e.getMessage(), 0));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(inv -> new ToolExecutionResult(inv.id(), inv.name(), "执行被中断", 0))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private String executeOne(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name;
        }
        try {
            JsonNode args = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(e -> argMap.put(e.getKey(), e.getValue().asText()));
            return tool.executor().execute(argMap);
        } catch (PolicyException e) {
            return "🛡️ 策略拒绝: " + e.getMessage();
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    // ===== 注册内置工具 =====

    private void registerFileTools() {
        tools.put("read_file", new Tool("read_file",
                "读取文件内容（仅限项目目录内），支持 offset/limit 按行读取",
                createParams(
                        param("path", "string", "文件路径", true),
                        param("offset", "integer", "起始行号（从1开始）", false),
                        param("limit", "integer", "最多读取行数", false)),
                args -> {
                    try {
                        Path safe = pathGuard.resolveSafe(args.get("path"));
                        if (!Files.isRegularFile(safe)) return "不是普通文件: " + args.get("path");
                        String content = Files.readString(safe, StandardCharsets.UTF_8);
                        if (!args.containsKey("offset") && !args.containsKey("limit")) {
                            return "文件内容:\n" + content;
                        }
                        List<String> lines = content.lines().toList();
                        int offset = Math.max(1, parseInt(args.get("offset"), 1));
                        int limit = Math.max(1, parseInt(args.get("limit"), 2000));
                        int from = offset - 1;
                        int to = Math.min(from + limit, lines.size());
                        if (from >= lines.size()) return "offset 超出范围（共 " + lines.size() + " 行）";
                        StringBuilder sb = new StringBuilder();
                        sb.append(safe.getFileName()).append(" (lines ").append(offset).append("-").append(to)
                                .append(" of ").append(lines.size()).append(")\n");
                        for (int i = from; i < to; i++) {
                            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
                        }
                        return sb.toString().trim();
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }));

        tools.put("write_file", new Tool("write_file",
                "写入文件内容（仅限项目目录内，5MB 上限）",
                createParams(
                        param("path", "string", "文件路径", true),
                        param("content", "string", "文件内容", true)),
                args -> {
                    try {
                        String content = args.getOrDefault("content", "");
                        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
                            throw new PolicyException("写入内容超过 5MB 上限");
                        }
                        Path safe = pathGuard.resolveSafe(args.get("path"));
                        Path parent = safe.getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.writeString(safe, content, StandardCharsets.UTF_8);
                        return "文件已写入: " + args.get("path");
                    } catch (PolicyException e) {
                        throw e;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }));

        tools.put("list_dir", new Tool("list_dir",
                "列出目录内容",
                createParams(param("path", "string", "目录路径", true)),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    File[] files = safe.toFile().listFiles();
                    if (files == null) return "目录为空或不存在";
                    StringBuilder sb = new StringBuilder("目录内容:\n");
                    Arrays.sort(files, Comparator.comparing(File::getName));
                    for (File f : files) {
                        sb.append(f.isDirectory() ? "[D] " : "[F] ").append(f.getName()).append("\n");
                    }
                    return sb.toString().trim();
                }));

        tools.put("grep_code", new Tool("grep_code",
                "在项目内搜索代码关键字，返回文件名和行号",
                createParams(
                        param("pattern", "string", "搜索关键字", true),
                        param("path", "string", "搜索目录，默认 .", false),
                        param("glob", "string", "文件名过滤（如 *.java）", false)),
                (ToolExecutor) args -> grepCode(args)));
    }

    private void registerShellTools() {
        tools.put("execute_command", new Tool("execute_command",
                "在项目目录执行 Shell 命令（60秒超时，输出截断8KB）",
                createParams(param("command", "string", "要执行的命令", true)),
                (ToolExecutor) args -> executeCommand(args.get("command"))));
    }

    // ===== 具体实现 =====

    private String executeCommand(String command) {
        if (command == null || command.isBlank()) return "命令不能为空";
        String denyReason = CommandGuard.check(command);
        if (denyReason != null) throw new PolicyException(denyReason);

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Future<String> outputFuture = Executors.newSingleThreadExecutor().submit(() -> {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() < MAX_COMMAND_OUTPUT) {
                            output.append(line).append("\n");
                        }
                    }
                }
                if (output.length() > MAX_COMMAND_OUTPUT) {
                    return output.substring(0, MAX_COMMAND_OUTPUT) + "\n...(输出已截断)";
                }
                return output.toString();
            });

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时（" + COMMAND_TIMEOUT_SECONDS + "秒），已终止";
            }

            String output = outputFuture.get(2, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            return String.format("exit code: %d\n%s", exitCode, output.isEmpty() ? "(无输出)" : output);
        } catch (Exception e) {
            return "执行命令失败: " + e.getMessage();
        }
    }

    private String grepCode(Map<String, String> args) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) return "pattern 不能为空";

        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        String glob = args.getOrDefault("glob", "");

        List<String> results = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_GREP_RESULTS) return FileVisitResult.TERMINATE;
                    String fileName = file.getFileName().toString();
                    if (!glob.isEmpty() && !matchesGlob(fileName, glob)) return FileVisitResult.CONTINUE;

                    String relPath = root.relativize(file).toString();
                    if (relPath.startsWith(".git/") || relPath.contains("/target/")
                            || relPath.contains("/node_modules/") || relPath.contains("/.idea/")) {
                        return FileVisitResult.CONTINUE;
                    }

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).contains(pattern)) {
                                results.add(relPath + ":" + (i + 1) + ": " + lines.get(i).trim());
                                if (results.size() >= MAX_GREP_RESULTS) return FileVisitResult.TERMINATE;
                            }
                        }
                    } catch (Exception ignored) {
                        // skip binary/unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }

        if (results.isEmpty()) return "未找到匹配: " + pattern;
        StringBuilder sb = new StringBuilder();
        sb.append("匹配 ").append(results.size()).append(" 条:\n");
        for (String r : results) sb.append(r).append("\n");
        return sb.toString().trim();
    }

    // ===== 辅助方法 =====

    private static boolean matchesGlob(String fileName, String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }

    private static int parseInt(String s, int defaultValue) {
        if (s == null) return defaultValue;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // ===== JSON Schema 构建 =====

    private record Param(String name, String type, String description, boolean required) {}

    private static Param param(String name, String type, String desc, boolean required) {
        return new Param(name, type, desc, required);
    }

    private static JsonNode createParams(Param... params) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        List<String> required = new ArrayList<>();
        for (Param p : params) {
            ObjectNode prop = properties.putObject(p.name());
            prop.put("type", p.type());
            prop.put("description", p.description());
            if (p.required()) required.add(p.name());
        }
        if (!required.isEmpty()) {
            var reqArray = schema.putArray("required");
            required.forEach(reqArray::add);
        }
        return schema;
    }
}
