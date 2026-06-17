# 精简版 Agent 项目功能总结

> 本文档记录精简版 Agent 的完整功能设计，持续更新中。

---

## 一、项目定位

基于 ReAct 架构的终端 AI 编程助手，对标 Claude Code 的基础功能。支持两种 Agent 模式、工具并行调度、SSE 流式输出、上下文压缩、长期记忆、轻量 RAG 代码检索。

**技术栈：** Java 17、Maven、OkHttp、JLine、JavaParser、SQLite

---

## 二、功能清单

| # | 功能模块 | 面试考点 | 状态 |
|---|---------|---------|------|
| 1 | ReAct Agent 循环 | Agent 架构、while 循环 | ✅ 已设计 |
| 2 | Plan-and-Execute 模式 | 策略模式、任务拆解 | ✅ 已设计 |
| 3 | 工具系统（4 工具 + 并行执行） | 注册表模式、并发编程 | ✅ 已设计 |
| 4 | LLM 客户端（SSE 流式） | 模板方法、流式协议解析 | ✅ 已设计 |
| 5 | 对话历史 + 自动压缩 | 上下文工程、Map-Reduce | ✅ 已设计 |
| 6 | 长期记忆（project / global） | 持久化、作用域隔离 | ✅ 已设计 |
| 7 | 轻量 RAG（三级切块 + 混合检索） | AST 解析、向量检索、RRF | ✅ 已设计 |
| 8 | Token 预算（双层） | 防御性编程、滑动窗口 | ✅ 已设计 |
| 9 | Prompt Cache | 缓存优化、成本控制 | ✅ 已设计 |
| 10 | HITL 人机审批（设计说明） | 安全体系三层防护、审计链 | ✅ 已设计（暂不实现） |
| 11 | Skill 插件系统（设计说明） | 三层加载、frontmatter 解析、上下文注入 | ✅ 已设计（暂不实现） |
| 12 | MCP 协议集成 | JSON-RPC 2.0、stdio 传输、工具动态发现与注册 | ✅ 已实现 |

---

## 三、功能详细设计 + 伪代码

---

### 1. ReAct Agent 循环

**核心思想：** Think → Act → Observe 迭代循环，LLM 自主决定是直接回答还是调工具。

```java
public class Agent {
    private LlmClient llmClient;
    private ToolRegistry toolRegistry;
    private List<Message> conversationHistory;
    private MemoryManager memoryManager;

    public String run(String userInput) {
        // 1. 存入短期记忆
        memoryManager.addUserMessage(userInput);

        // 2. 检索相关长期记忆，注入 system prompt
        String memoryContext = memoryManager.buildContextForQuery(userInput);
        updateSystemPrompt(memoryContext);

        // 3. 添加用户消息到对话历史
        conversationHistory.add(Message.user(userInput));

        TokenBudget tokenBudget = new TokenBudget(llmClient.maxContextWindow());
        AgentGuard guard = new AgentGuard(30, 3, 0);

        // 4. ReAct 主循环
        while (true) {
            // 检查是否该停
            AgentGuard.StopReason reason = guard.shouldStop();
            if (reason != AgentGuard.StopReason.CONTINUE) {
                return "⏹️ " + guard.describe(reason);
            }
            guard.nextIteration();

            // 检查上下文是否需要压缩
            if (tokenBudget.needsCompression(conversationHistory)) {
                compressHistory();
            }

            // 调 LLM（传入对话历史 + 工具定义）
            ChatResponse response = llmClient.chat(
                conversationHistory,
                toolRegistry.getToolDefinitions(),
                streamListener  // SSE 流式回调
            );
            tokenBudget.record(response.inputTokens(), response.outputTokens());
            guard.addTokens(response.inputTokens() + response.outputTokens());

            // 有工具调用 → 执行并继续循环
            if (response.hasToolCalls()) {
                guard.recordToolCalls(response.toolCalls());
                conversationHistory.add(Message.assistant(response.toolCalls()));

                List<ToolResult> results = toolRegistry.executeTools(response.toolCalls());
                for (ToolResult r : results) {
                    memoryManager.addToolResult(r.name(), r.result());
                    conversationHistory.add(Message.tool(r.id(), r.result()));
                }
                continue;  // 让 LLM 根据工具结果继续思考
            }

            // 没有工具调用 → 返回最终答案
            guard.recordToolCalls(null);
            conversationHistory.add(Message.assistant(response.content()));
            memoryManager.addAssistantMessage(response.content());
            return response.content();
        }
    }
}
```

---

### 2. Plan-and-Execute 模式

**核心思想：** 复杂任务先让 LLM 拆解为多步计划，用户确认后逐步执行，每一步内部复用 ReAct Agent。

```java
public class PlanExecuteAgent {
    private LlmClient llmClient;
    private Agent reactAgent;  // 复用已有的 ReAct Agent

    private static final String PLAN_PROMPT = """
        请将以下任务拆解为 3-5 个具体步骤，每步一行，格式：
        1. [步骤描述]
        2. [步骤描述]
        ...
        任务：%s
        """;

    public String run(String task) {
        // Step 1: 让 LLM 生成计划
        ChatResponse planResponse = llmClient.chat(
            List.of(Message.user(String.format(PLAN_PROMPT, task))),
            null
        );
        List<String> steps = parseSteps(planResponse.content());

        // Step 2: 展示计划，等待用户确认
        print("📋 计划如下：");
        for (int i = 0; i < steps.size(); i++) {
            print("  " + (i + 1) + ". " + steps.get(i));
        }
        // 用户按 Enter 确认，ESC 取消，I 补充要求重新规划
        UserDecision decision = waitForUserReview();
        if (decision == UserDecision.CANCEL) return "已取消";

        // Step 3: 逐步执行（每步复用 ReAct Agent）
        StringBuilder allResults = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            print("▶ 执行步骤 " + (i + 1) + "/" + steps.size() + ": " + steps.get(i));
            String stepResult = reactAgent.run(steps.get(i));
            allResults.append("步骤 ").append(i + 1).append(": ").append(stepResult).append("\n\n");
        }

        // Step 4: 汇总结果
        return "✅ 计划执行完成\n\n" + allResults;
    }

    private List<String> parseSteps(String content) {
        // 解析 "1. xxx\n2. xxx" 格式
        return Arrays.stream(content.split("\n"))
            .filter(line -> line.matches("^\\d+\\..*"))
            .map(line -> line.replaceFirst("^\\d+\\.\\s*", ""))
            .toList();
    }
}
```

**Main.java 中切换：**
```java
switch (command) {
    case "/plan" -> {
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, reactAgent);
        result = planAgent.run(userInput);
    }
    default -> result = reactAgent.run(userInput);  // 默认 ReAct
}
```

---

### 3. 工具系统

**核心思想：** 注册表模式 + 函数式接口，每个工具是一个 lambda 执行器。多个工具并行执行。

```java
// 工具定义
public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

public interface ToolExecutor {
    String execute(Map<String, String> args);
}

// 工具注册表
public class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private static final int MAX_PARALLEL = 4;
    private PathGuard pathGuard;  // 路径安全检查

    public ToolRegistry(String projectPath) {
        this.pathGuard = new PathGuard(projectPath);
        registerFileTools();
        registerShellTools();
    }

    private void registerFileTools() {
        // read_file: 读取文件（支持 offset/limit 分页读）
        tools.put("read_file", new Tool("read_file", "读取文件内容",
            createParameters(
                new Param("path", "string", "文件路径", true),
                new Param("offset", "integer", "起始行号", false),
                new Param("limit", "integer", "最多读取行数", false)
            ),
            args -> {
                Path safe = pathGuard.resolveSafe(args.get("path"));  // 安全检查
                return readFile(safe, args);
            }
        ));

        // write_file: 写入文件（5MB 上限）
        tools.put("write_file", new Tool("write_file", "写入文件",
            createParameters(
                new Param("path", "string", "文件路径", true),
                new Param("content", "string", "文件内容", true)
            ),
            args -> {
                String content = args.get("content");
                if (content.getBytes().length > 5 * 1024 * 1024) {
                    throw new PolicyException("超过 5MB 上限");
                }
                Path safe = pathGuard.resolveSafe(args.get("path"));
                Files.writeString(safe, content);
                return "文件已写入: " + args.get("path");
            }
        ));

        // grep_code: 关键字搜索代码（优先 ripgrep）
        tools.put("grep_code", new Tool("grep_code", "搜索代码",
            createParameters(
                new Param("pattern", "string", "搜索关键字", true),
                new Param("path", "string", "搜索目录", false),
                new Param("glob", "string", "文件过滤", false)
            ),
            args -> grepCode(args)
        ));

        // list_dir: 列出目录
        tools.put("list_dir", new Tool("list_dir", "列出目录",
            createParameters(new Param("path", "string", "目录路径", true)),
            args -> listDir(pathGuard.resolveSafe(args.get("path")))
        ));
    }

    private void registerShellTools() {
        // execute_command: 执行 Shell 命令（60 秒超时，命令黑名单）
        tools.put("execute_command", new Tool("execute_command", "执行命令",
            createParameters(new Param("command", "string", "命令", true)),
            args -> {
                String cmd = args.get("command");
                CommandGuard.check(cmd);  // 黑名单检查（sudo、rm -rf / 等）
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
                pb.directory(new File(projectPath));
                Process process = pb.start();
                process.waitFor(60, TimeUnit.SECONDS);  // 超时
                return readOutput(process);  // 输出截断 8KB
            }
        ));
    }

    // 并行执行多个工具
    public List<ToolResult> executeTools(List<ToolCall> calls) {
        if (calls.size() == 1) {
            // 单个工具直接同步执行
            return List.of(executeOne(calls.get(0)));
        }
        // 多个工具线程池并行（最多 4 个）
        int parallelism = Math.min(calls.size(), MAX_PARALLEL);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        List<Future<ToolResult>> futures = executor.invokeAll(
            calls.stream().map(call -> (Callable<ToolResult>) () -> executeOne(call)).toList(),
            90, TimeUnit.SECONDS  // 批次超时
        );
        return futures.stream().map(f -> get(f)).toList();  // 按原序返回
    }

    private ToolResult executeOne(ToolCall call) {
        Tool tool = tools.get(call.name());
        Map<String, String> args = parseArgs(call.arguments());
        String result = tool.executor().execute(args);
        return new ToolResult(call.id(), call.name(), result);
    }
}
```

---

### 4. LLM 客户端（SSE 流式）

**核心思想：** 接口 + 模板方法，基类封装 SSE 流式解析，子类只覆盖 URL/模型/Key。

```java
// 统一接口
public interface LlmClient {
    ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException;
    String getModelName();
    int maxContextWindow();
    boolean supportsPromptCaching();
}

// 消息模型
public record Message(String role, String content, String reasoningContent,
                      List<ToolCall> toolCalls, String toolCallId) {
    public static Message system(String content) { return new Message("system", content, ...); }
    public static Message user(String content) { return new Message("user", content, ...); }
    public static Message assistant(String content) { return new Message("assistant", content, ...); }
    public static Message tool(String id, String content) { return new Message("tool", content, ..., id); }
}

public record ChatResponse(String content, String reasoningContent, List<ToolCall> toolCalls,
                           int inputTokens, int outputTokens, int cachedInputTokens) {}

public record ToolCall(String id, String name, String arguments) {}

// 流式回调
public interface StreamListener {
    default void onReasoningDelta(String delta) {}  // 思考过程逐字到达
    default void onContentDelta(String delta) {}     // 正文逐字到达
}

// 抽象基类（模板方法）
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // SSE 流式需要长超时
        .build();

    protected abstract String getApiUrl();
    protected abstract String getModel();
    protected abstract String getApiKey();

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        // 1. 构建请求体
        String body = buildRequestBody(messages, tools);

        // 2. 发送 HTTP 请求
        Request request = new Request.Builder()
            .url(getApiUrl())
            .header("Authorization", "Bearer " + getApiKey())
            .post(RequestBody.create(body, MediaType.parse("application/json")))
            .build();

        // 3. SSE 流式解析
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
            int inputTokens = 0, outputTokens = 0;

            BufferedSource source = response.body().source();
            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null || !line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) break;

                JsonNode delta = parse(payload).path("choices").get(0).path("delta");

                // 思考过程（如 DeepSeek R1 的推理链）
                String reasoningDelta = delta.path("reasoning_content").asText("");
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    listener.onReasoningDelta(reasoningDelta);  // 实时推给渲染器
                }

                // 正文
                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    listener.onContentDelta(contentDelta);  // 实时推给渲染器
                }

                // 工具调用（参数逐字符到达，用累加器聚合）
                mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));

                // Token 统计
                JsonNode usage = parse(payload).path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("prompt_tokens").asInt();
                    outputTokens = usage.path("completion_tokens").asInt();
                }
            }

            return new ChatResponse(content.toString(), reasoning.toString(),
                buildToolCalls(toolAccumulators), inputTokens, outputTokens, 0);
        }
    }
}

// 具体实现（瘦子类，只覆盖差异）
public class DeepSeekClient extends AbstractOpenAiCompatibleClient {
    protected String getApiUrl() { return "https://api.deepseek.com/chat/completions"; }
    protected String getModel() { return "deepseek-v4-flash"; }
    protected String getApiKey() { return apiKey; }
    public int maxContextWindow() { return 1_000_000; }
    public boolean supportsPromptCaching() { return true; }
}
```

---

### 5. 对话历史 + 自动压缩

**核心思想：** 对话历史接近上下文窗口上限时，自动将早期对话压缩为摘要。

```java
public class ConversationHistoryCompactor {
    private LlmClient llmClient;
    private static final int RETAIN_RECENT_ROUNDS = 3;  // 保留最近 3 轮

    private static final String SUMMARY_PROMPT = """
        请把下面的对话历史压缩成简明摘要，保留：
        1. 用户的关键诉求与目标
        2. Agent 已完成的关键操作和结果
        3. 已达成的结论
        4. 仍未解决的问题
        输出 1-3 段，不要复述原文。
        === 待压缩的对话 ===
        %s
        """;

    // 每轮调 LLM 前检查是否需要压缩
    public boolean compactIfNeeded(List<Message> history, int triggerTokens) {
        int currentTokens = TokenBudget.estimateTokens(history);
        if (currentTokens < triggerTokens) return false;  // 没到阈值，不压

        // 找到分割点：保留最近 3 个 user message 之后的内容
        int splitIdx = findLastNthUserMessage(history, RETAIN_RECENT_ROUNDS);
        if (splitIdx <= 0) return false;  // 轮次太少，不压

        // 取出旧消息，调 LLM 生成摘要
        List<Message> oldMessages = history.subList(1, splitIdx);  // 跳过 system
        String summary = summarize(oldMessages);

        // 重建历史：[system] + [摘要] + [保留的近期消息]
        List<Message> rebuilt = new ArrayList<>();
        rebuilt.add(history.get(0));  // system prompt 不动
        rebuilt.add(Message.user("[已压缩的历史对话摘要]\n" + summary));
        rebuilt.add(Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        history.clear();
        history.addAll(rebuilt);
        return true;
    }

    private String summarize(List<Message> messages) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.role()).append(": ").append(m.content()).append("\n\n");
        }
        ChatResponse response = llmClient.chat(
            List.of(Message.user(String.format(SUMMARY_PROMPT, sb))),
            null
        );
        return response.content();
    }
}
```

---

### 6. 长期记忆（project / global 双作用域）

**核心思想：** 跨会话持久化的关键事实，project 级仅对当前项目生效，global 级全局生效。

```java
public class LongTermMemory {
    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private final File storageFile;  // ~/.paicli/memory/long_term_memory.json

    // 存储事实
    public void store(String fact, String scope, String currentProject) {
        // 去重：内容完全相同的跳过
        boolean duplicate = entries.values().stream()
            .anyMatch(e -> e.getContent().equals(fact));
        if (duplicate) return;

        Map<String, String> metadata = new HashMap<>();
        metadata.put("scope", scope);  // "project" 或 "global"
        if ("project".equals(scope)) {
            metadata.put("project", currentProject);  // 绑定项目路径
        }

        MemoryEntry entry = new MemoryEntry(
            "fact-" + UUID.randomUUID().toString().substring(0, 8),
            fact,
            MemoryType.FACT,
            metadata
        );
        entries.put(entry.getId(), entry);
        saveToDisk();  // JSON 序列化到磁盘
    }

    // 检索：只返回当前项目可见的记忆
    public List<MemoryEntry> search(String query, int limit, String currentProject) {
        Set<String> queryTokens = tokenize(query);

        return entries.values().stream()
            .filter(entry -> isVisible(entry, currentProject))  // 作用域过滤
            .filter(entry -> matches(entry.getContent(), queryTokens))  // 关键词匹配
            .limit(limit)
            .toList();
    }

    // 作用域可见性判断
    private boolean isVisible(MemoryEntry entry, String currentProject) {
        String scope = entry.getMetadata().get("scope");
        if ("global".equals(scope)) return true;  // global 对所有项目可见
        // project 级：只有项目路径匹配才可见
        return currentProject != null
            && currentProject.equals(entry.getMetadata().get("project"));
    }

    // 构建注入 system prompt 的记忆上下文
    public String buildContextForQuery(String query, int maxTokens, String currentProject) {
        List<MemoryEntry> relevant = search(query, 10, currentProject);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder("## 相关长期记忆\n\n");
        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;
            context.append("- ").append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }
        return context.toString();
    }

    // JSON 持久化
    private void saveToDisk() {
        mapper.writeValue(storageFile, entries.values());
    }
    private void loadFromDisk() {
        if (storageFile.exists()) {
            entries.putAll(mapper.readValue(storageFile, ...));
        }
    }
}

// 用户手动保存：/save 这个项目用Java 17
// 用户手动保存：/save --global 我习惯用中文回答
// Agent 自动保存：用户明确说 "记住xxx" 时调用 save_memory 工具
```

---

### 7. 轻量 RAG（三级切块 + 混合检索）

**核心思想：**
- 代码切块分三级：文件 → 类 → 方法，用 JavaParser 做 AST 解析递减切分
- 检索策略：先用 grep_code / read_file 精确读取，信息不够时再走向量检索
- 向量检索：Embedding API + SQLite 存储 + 余弦相似度
- 混合检索：向量 + 关键词（TF-IDF），RRF 融合排序
- 上下文扩展：命中 chunk 后返回前后各 1 块

```java
public class CodeIndex {

    // ===== 索引阶段 =====

    // 三级切块：文件 → 类 → 方法
    public List<CodeChunk> indexProject(String projectPath) {
        List<CodeChunk> chunks = new ArrayList<>();

        for (Path file : walkJavaFiles(projectPath)) {
            String source = Files.readString(file);

            // Level 1: 文件级 chunk（整个文件）
            CodeChunk fileChunk = new CodeChunk(file, "file", source, 0, countLines(source));
            chunks.add(fileChunk);

            // Level 2 & 3: 用 JavaParser AST 解析出类和方法
            try {
                CompilationUnit cu = StaticJavaParser.parse(source);

                for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    // Level 2: 类级 chunk
                    CodeChunk classChunk = new CodeChunk(
                        file, "class", clazz.toString(),
                        clazz.getBegin().get().line, clazz.getEnd().get().line
                    );
                    classChunk.setName(clazz.getNameAsString());
                    chunks.add(classChunk);

                    // Level 3: 方法级 chunk
                    for (MethodDeclaration method : clazz.getMethods()) {
                        CodeChunk methodChunk = new CodeChunk(
                            file, "method", method.toString(),
                            method.getBegin().get().line, method.getEnd().get().line
                        );
                        methodChunk.setName(clazz.getNameAsString() + "." + method.getNameAsString());
                        chunks.add(methodChunk);
                    }
                }
            } catch (Exception e) {
                // AST 解析失败，只保留文件级 chunk
            }
        }

        // 生成 Embedding 并存入 SQLite
        for (CodeChunk chunk : chunks) {
            float[] vector = embeddingClient.embed(chunk.getContent());
            vectorStore.save(chunk, vector);
        }

        // 同时构建 TF-IDF 倒排索引（用于关键词检索）
        tfidfIndex.build(chunks);

        return chunks;
    }

    // ===== 检索阶段 =====

    // 检索策略：精确优先，模糊兜底
    public String search(String query, String projectPath) {
        // 第一阶段：尝试精确搜索（grep_code）
        GrepResult grepResult = grepSearch(query, projectPath);
        if (grepResult.matches().size() >= 3) {
            return formatGrepResults(grepResult);  // 精确结果够用，直接返回
        }

        // 第二阶段：精确结果不够，走向量 + 关键词混合检索
        return hybridSearch(query, 5);
    }

    // 混合检索：向量 + 关键词，RRF 融合
    public String hybridSearch(String query, int topK) {
        // 向量通道：Embedding → 余弦相似度 Top 10
        float[] queryVector = embeddingClient.embed(query);
        List<SearchResult> vectorResults = vectorStore.search(queryVector, 10);

        // 关键词通道：TF-IDF Top 10
        List<SearchResult> keywordResults = tfidfIndex.search(query, 10);

        // RRF 融合排序
        List<SearchResult> merged = rrfMerge(vectorResults, keywordResults, topK);

        // 上下文扩展：每个命中 chunk 取前后各 1 块
        List<SearchResult> expanded = expandContext(merged);

        return formatResults(query, expanded);
    }

    // RRF (Reciprocal Rank Fusion) 融合算法
    private List<SearchResult> rrfMerge(List<SearchResult> vector, List<SearchResult> keyword, int topK) {
        Map<String, Double> scores = new HashMap<>();
        int k = 60;  // RRF 常数

        // 向量结果按排名打分：1/(k+rank)
        for (int i = 0; i < vector.size(); i++) {
            String key = vector.get(i).chunkId();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
        }

        // 关键词结果同样打分，累加
        for (int i = 0; i < keyword.size(); i++) {
            String key = keyword.get(i).chunkId();
            scores.merge(key, 1.0 / (k + i + 1), Double::sum);
        }

        // 按融合分数降序，取 Top K
        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> findResultById(e.getKey()))
            .toList();
    }

    // 上下文扩展
    private List<SearchResult> expandContext(List<SearchResult> results) {
        List<SearchResult> expanded = new ArrayList<>();
        for (SearchResult r : results) {
            CodeChunk prev = getPreviousChunk(r.chunk());  // 前一个 chunk
            CodeChunk next = getNextChunk(r.chunk());      // 后一个 chunk
            if (prev != null) expanded.add(new SearchResult(prev, r.score()));
            expanded.add(r);
            if (next != null) expanded.add(new SearchResult(next, r.score()));
        }
        return expanded;
    }
}

// Embedding 客户端（调远程 API）
public class EmbeddingClient {
    private final String apiUrl;
    private final String apiKey;

    public float[] embed(String text) {
        String body = """
            {"model": "embedding-3", "input": "%s"}
            """.formatted(escapeJson(text));
        // POST 请求到 Embedding API
        JsonNode response = post(apiUrl, body, apiKey);
        return parseFloatArray(response.path("data").get(0).path("embedding"));
    }
}

// 向量存储（SQLite）
public class VectorStore {
    private final Connection db;

    public void save(CodeChunk chunk, float[] vector) {
        // 存入 SQLite：id, file, name, level, content, vector(JSON), start_line, end_line
        PreparedStatement stmt = db.prepareStatement(
            "INSERT INTO chunks (id, file, name, level, content, vector, start_line, end_line) VALUES (?,?,?,?,?,?,?,?)"
        );
        stmt.setString(1, chunk.getId());
        stmt.setString(2, chunk.getFile().toString());
        stmt.setString(3, chunk.getName());
        stmt.setString(4, chunk.getLevel());  // "file" / "class" / "method"
        stmt.setString(5, chunk.getContent());
        stmt.setString(6, toJson(vector));     // float[] → JSON 字符串
        stmt.setInt(7, chunk.getStartLine());
        stmt.setInt(8, chunk.getEndLine());
        stmt.execute();
    }

    public List<SearchResult> search(float[] queryVector, int topK) {
        // 全量扫描余弦相似度（数据量小时够用）
        List<ScoredChunk> scored = new ArrayList<>();
        for (ChunkRow row : db.query("SELECT * FROM chunks")) {
            float[] vec = fromJson(row.vector());
            double similarity = cosineSimilarity(queryVector, vec);
            scored.add(new ScoredChunk(row, similarity));
        }
        return scored.stream()
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .limit(topK)
            .map(sc -> new SearchResult(sc.chunk(), sc.score()))
            .toList();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

// TF-IDF 倒排索引（关键词检索）
public class TfidfIndex {
    // 倒排表：term → {chunkId → tf}
    private final Map<String, Map<String, Integer>> invertedIndex = new HashMap<>();
    private final Map<String, Integer> docLengths = new HashMap<>();
    private int totalDocs;

    public void build(List<CodeChunk> chunks) {
        totalDocs = chunks.size();
        for (CodeChunk chunk : chunks) {
            List<String> terms = tokenize(chunk.getContent());  // jieba 分词
            docLengths.put(chunk.getId(), terms.size());
            for (String term : terms) {
                invertedIndex.computeIfAbsent(term, k -> new HashMap<>())
                    .merge(chunk.getId(), 1, Integer::sum);
            }
        }
    }

    public List<SearchResult> search(String query, int topK) {
        List<String> queryTerms = tokenize(query);
        Map<String, Double> scores = new HashMap<>();

        for (String term : queryTerms) {
            Map<String, Integer> postings = invertedIndex.getOrDefault(term, Map.of());
            int df = postings.size();
            if (df == 0) continue;
            double idf = Math.log((double) totalDocs / df);

            for (Map.Entry<String, Integer> entry : postings.entrySet()) {
                double tf = (double) entry.getValue() / docLengths.get(entry.getKey());
                scores.merge(entry.getKey(), tf * idf, Double::sum);
            }
        }

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> new SearchResult(findChunk(e.getKey()), e.getValue()))
            .toList();
    }
}
```

---

### 8. Token 预算（双层）

```java
// 第 1 层：TokenBudget — 管"单次请求别超模型窗口"
public class TokenBudget {
    private final int contextWindow;       // 如 128000
    private final int reservedForSystem;   // 500
    private final int reservedForTools;    // 800
    private final int reservedForResponse; // 2000
    private int totalInputTokens, totalOutputTokens, callCount;

    public int available() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    public boolean needsCompression(List<Message> history) {
        return estimate(history) > available() * 0.8;  // 80% 触发压缩
    }

    public static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 3);  // 中英文混合经验值
    }

    public void record(int inputTokens, int outputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        callCount++;
    }
}

// 第 2 层：AgentGuard — 管"循环别失控"
public class AgentGuard {
    public enum StopReason { CONTINUE, TOKEN_EXCEEDED, STAGNATION, MAX_ITERATIONS }

    private final int maxIterations;      // 30
    private final int stagnationWindow;   // 3
    private final int tokenBudget;        // 0 = 不限
    private int iteration, totalTokens;
    private final Deque<String> toolSignatures = new ArrayDeque<>();

    public StopReason shouldStop() {
        if (iteration >= maxIterations) return StopReason.MAX_ITERATIONS;
        if (tokenBudget > 0 && totalTokens >= tokenBudget) return StopReason.TOKEN_EXCEEDED;
        if (isStagnant()) return StopReason.STAGNATION;
        return StopReason.CONTINUE;
    }

    public void recordToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) { toolSignatures.clear(); return; }
        String sig = calls.stream()
            .map(tc -> tc.name() + "|" + tc.arguments()).collect(Collectors.joining(";"));
        toolSignatures.addLast(sig);
        while (toolSignatures.size() > stagnationWindow) toolSignatures.removeFirst();
    }

    private boolean isStagnant() {
        if (toolSignatures.size() < stagnationWindow) return false;
        String first = toolSignatures.peekFirst();
        return toolSignatures.stream().allMatch(s -> s.equals(first));
    }
}
```

---

### 9. Prompt Cache

**核心思想：** 如果连续请求的 system prompt + 工具定义不变，API 可以复用缓存的前缀，减少 input token 费用。

```java
// 在 LlmClient 接口层声明支持
public interface LlmClient {
    default boolean supportsPromptCaching() { return false; }
    default String promptCacheMode() { return "none"; }
}

// 在请求构建时注入缓存控制标记
public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected JsonNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", getModel());
        body.put("stream", true);

        // 消息列表
        ArrayNode msgArray = body.putArray("messages");
        for (Message msg : messages) {
            msgArray.add(serializeMessage(msg));
        }

        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", serializeTools(tools));
        }

        // Prompt Cache 支持
        if (supportsPromptCaching()) {
            injectCacheControl(body);
        }

        return body;
    }

    // 给 system prompt 和工具定义注入 cache_control 标记
    // API 会缓存这部分前缀，后续请求相同前缀时不重复计费
    private void injectCacheControl(ObjectNode body) {
        String mode = promptCacheMode();

        if ("automatic-prefix-cache".equals(mode)) {
            // DeepSeek 模式：API 自动缓存相同前缀，无需额外标记
            // 只需保证 system prompt 和 tools 在消息列表最前面且不变
            return;
        }

        if ("glm-prompt-cache".equals(mode)) {
            // GLM 模式：在 system message 上标记 cache_control
            ArrayNode messages = (ArrayNode) body.get("messages");
            if (messages.size() > 0 && "system".equals(messages.get(0).get("role").asText())) {
                ObjectNode systemMsg = (ObjectNode) messages.get(0);
                systemMsg.set("cache_control", mapper.createObjectNode().put("type", "ephemeral"));
            }
        }
    }
}

// 在 Agent 中保证 system prompt 稳定性
public class Agent {
    // system prompt 放 conversationHistory[0]，只有记忆更新时才替换
    // 工具定义每轮相同（除非 MCP 工具变化）
    // 这样 API 前缀不变，缓存命中率最高

    private void updateSystemPromptWithMemory(String memoryContext) {
        // 只在记忆变化时更新 system prompt
        // 保持前缀稳定，最大化 prompt cache 命中率
        conversationHistory.set(0, Message.system(buildSystemPrompt(memoryContext)));
    }
}

// Token 统计中体现缓存效果
public record ChatResponse(
    String content,
    String reasoningContent,
    List<ToolCall> toolCalls,
    int inputTokens,        // 总输入 token
    int outputTokens,
    int cachedInputTokens   // 缓存命中的 token（这部分费用更低）
) {}
```

---

## 四、项目文件结构

```
src/main/java/com/yourname/aicli/
├── cli/
│   └── Main.java                     # 入口 + while 循环 + 命令解析
├── agent/
│   ├── Agent.java                    # ReAct 循环
│   ├── AgentGuard.java               # 循环保护（停滞检测 + 轮数上限）
│   └── PlanExecuteAgent.java         # Plan-and-Execute 模式
├── llm/
│   ├── LlmClient.java                # 接口 + 消息模型
│   ├── AbstractOpenAiCompatibleClient.java  # SSE 流式基类
│   └── DeepSeekClient.java           # 具体实现
├── tool/
│   ├── ToolRegistry.java             # 工具注册 + 并行执行
│   ├── ToolExecutor.java             # 函数式接口
│   └── PathGuard.java                # 路径安全检查
├── memory/
│   ├── MemoryManager.java            # 记忆管理器（门面）
│   ├── ConversationMemory.java       # 短期记忆
│   ├── LongTermMemory.java           # 长期记忆（project/global）
│   ├── ConversationHistoryCompactor.java  # 对话压缩
│   └── TokenBudget.java              # Token 预算
├── rag/
│   ├── CodeIndex.java                # 索引 + 检索入口
│   ├── CodeChunk.java                # 代码块模型
│   ├── CodeChunker.java              # 三级切块（JavaParser AST）
│   ├── VectorStore.java              # SQLite 向量存储
│   ├── EmbeddingClient.java          # Embedding API
│   └── TfidfIndex.java               # TF-IDF 倒排索引
└── prompt/
    └── PromptAssembler.java          # Prompt 组装（含 cache 策略）

resources/
├── prompts/
│   └── system.md                     # System prompt 模板
└── logback.xml
```

---

## 五、简历描述

**项目名：** AI Code Assistant — 基于 ReAct 的终端 AI 编程助手

**描述：**
> 基于 ReAct 架构实现的终端 AI 编程助手，支持 ReAct 和 Plan-and-Execute 两种 Agent 模式。实现了工具注册与并行调度、SSE 流式输出、上下文自动压缩、长期记忆（project/global 双作用域）、基于 JavaParser AST 三级切块的 RAG 代码检索（混合检索 + RRF 融合）、Prompt Cache 优化等核心能力。

**技术亮点（简历用，按吸引力排序）：**

> ① 设计并实现了基于 ReAct 的终端 AI 编程助手，支持 ReAct 和 Plan-and-Execute 双模式，通过 Think-Act-Observe 循环驱动大模型自主调用工具完成复杂编码任务，含停滞检测、Token 预算、轮数兜底三重循环保护机制

> ② 实现了工具注册表 + 并行调度器，同一轮 LLM 返回多个 tool_call 时通过线程池并发执行（最多 4 路），基于 Future + invokeAll 实现批次超时控制，结果按原始顺序回灌消息历史，保证 OpenAI Function Calling 协议一致性

> ③ 集成轻量 RAG 代码检索引擎，基于 JavaParser AST 实现文件/类/方法三级递减切块，采用向量检索 + TF-IDF 关键词检索双通道混合检索，通过 RRF（Reciprocal Rank Fusion）融合排序，配合上下文窗口扩展提升召回质量

> ④ 基于模板方法模式封装 OpenAI 兼容协议的 SSE 流式解析，实现逐 token 流式输出和思考链展示；通过工厂模式 + 策略模式支持 DeepSeek/GLM/Kimi 等多模型运行时热切换，对话上下文无感保持

> ⑤ 设计了双层上下文管理体系：对话历史接近窗口上限时自动 Map-Reduce 摘要压缩，长期记忆按 project/global 双作用域隔离并 JSON 持久化；通过 Prompt Cache 策略保持 system prompt 前缀稳定，减少重复 token 计费

> ⑤ 集成 MCP（Model Context Protocol）动态工具发现与注册，基于 JSON-RPC 2.0 协议通过 stdio 传输与外部 MCP Server 通信，启动时自动握手、发现工具、按 mcp__server__tool 命名空间注册到 ToolRegistry，支持配置文件两层合并和 JVM ShutdownHook 生命周期管理

**使用建议：**
- 简历空间够 → 5 条全放
- 只能放 4 条 → 砍 ④（SSE 流式不如 MCP 有区分度）
- 只能放 3 条 → 留 ①②③（架构 + 并发 + RAG 是最强组合）

**技术栈：** Java 17、Maven、OkHttp、JLine、JavaParser、SQLite

---

---

### 10. HITL 人机审批（设计说明，暂不实现）

**定位：** 当前版本通过 PathGuard + CommandGuard 做静态安全检查，不做运行时人工审批。以下为 HITL 的完整设计，用于面试讲解和后续扩展。

**核心思想：** 对危险工具（write_file、execute_command、create_project）执行前弹出确认提示，用户批准后才执行，结果记入审计链。

```java
// 危险工具定义
public class ApprovalPolicy {
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
        "write_file", "execute_command", "create_project"
    );
    public static boolean isDangerous(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || toolName.startsWith("mcp__");
    }
}

// 审批结果
public enum ApprovalResult {
    APPROVE,       // 批准这一次
    APPROVE_ALL,   // 本次会话全部放行
    DENY,          // 拒绝
    SKIP,          // 跳过，不执行
    MODIFY         // 修改参数后执行
}

// HITL 审批处理器
public interface HitlHandler {
    ApprovalResult requestApproval(String toolName, String arguments, String reason);
    boolean isEnabled();
}

// 终端审批实现
public class TerminalHitlHandler implements HitlHandler {
    private boolean enabled = false;
    private final Set<String> approvedAllTools = new HashSet<>();  // "全部放行"缓存

    @Override
    public ApprovalResult requestApproval(String toolName, String arguments, String reason) {
        // 已选过"全部放行"的工具直接通过
        if (approvedAllTools.contains(toolName)) return ApprovalResult.APPROVE;

        // 终端弹出确认
        print("⚠️  危险操作需要确认：");
        print("   工具: " + toolName);
        print("   参数: " + truncate(arguments, 200));
        if (reason != null) print("   原因: " + reason);
        print("");
        print("   [Y] 批准   [A] 全部放行   [N] 拒绝   [S] 跳过");

        String input = readKey();
        return switch (input.toLowerCase()) {
            case "y" -> ApprovalResult.APPROVE;
            case "a" -> {
                approvedAllTools.add(toolName);
                yield ApprovalResult.APPROVE_ALL;
            }
            case "n" -> ApprovalResult.DENY;
            case "s" -> ApprovalResult.SKIP;
            default -> ApprovalResult.DENY;
        };
    }
}

// HITL 工具注册表：包装原始 ToolRegistry，拦截危险工具
public class HitlToolRegistry extends ToolRegistry {
    private final HitlHandler hitlHandler;
    private final AuditLog auditLog;

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        boolean shouldAudit = ApprovalPolicy.isDangerous(name);

        // 危险工具 → 先走 HITL 审批
        if (shouldAudit && hitlHandler.isEnabled()) {
            ApprovalResult result = hitlHandler.requestApproval(name, argumentsJson, null);
            switch (result) {
                case DENY -> {
                    auditLog.record(AuditEntry.denyByUser(name, argumentsJson));
                    return ToolOutput.text("🚫 用户拒绝了此操作");
                }
                case SKIP -> {
                    auditLog.record(AuditEntry.skipped(name, argumentsJson));
                    return ToolOutput.text("⏭️ 用户跳过了此操作");
                }
                case APPROVE, APPROVE_ALL -> {
                    auditLog.record(AuditEntry.approved(name, argumentsJson));
                    // 继续执行
                }
            }
        }

        // 安全检查（路径围栏 + 命令黑名单）
        try {
            ToolOutput output = super.executeToolOutput(name, argumentsJson);
            if (shouldAudit) {
                auditLog.record(AuditEntry.allow(name, argumentsJson));
            }
            return output;
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditEntry.denyByPolicy(name, argumentsJson, e.getMessage()));
            }
            return ToolOutput.text("🛡️ 策略拒绝: " + e.getMessage());
        }
    }
}

// 审计日志
public class AuditLog {
    public record AuditEntry(
        String toolName,
        String arguments,
        String decision,  // "allow" / "deny_by_user" / "deny_by_policy" / "skipped"
        long timestamp,
        String detail
    ) {
        public static AuditEntry allow(String name, String args) {
            return new AuditEntry(name, args, "allow", System.currentTimeMillis(), null);
        }
        public static AuditEntry denyByUser(String name, String args) {
            return new AuditEntry(name, args, "deny_by_user", System.currentTimeMillis(), null);
        }
        public static AuditEntry denyByPolicy(String name, String args, String reason) {
            return new AuditEntry(name, args, "deny_by_policy", System.currentTimeMillis(), reason);
        }
    }

    private final List<AuditEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final File auditFile;  // ~/.paicli/audit/audit.jsonl

    public void record(AuditEntry entry) {
        entries.add(entry);
        appendToFile(entry);  // 追加写入 JSONL
    }

    public List<AuditEntry> tail(int n) {
        return entries.subList(Math.max(0, entries.size() - n), entries.size());
    }
}

// 静态安全检查（已有，不依赖 HITL）
public class CommandGuard {
    private static final List<String> DENIED_PATTERNS = List.of(
        "sudo ", "rm -rf /", "mkfs", "dd of=/dev",
        ":(){ :|:& };:", "curl|sh", "chmod 777 /",
        "shutdown", "reboot", "> /dev/sda"
    );

    public static String check(String command) {
        for (String pattern : DENIED_PATTERNS) {
            if (command.contains(pattern)) {
                return "命令被安全策略拒绝: 包含危险模式 '" + pattern + "'";
            }
        }
        return null;  // null = 通过
    }
}

public class PathGuard {
    private final Path projectRoot;

    public Path resolveSafe(String path) {
        Path resolved = projectRoot.resolve(path).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new PolicyException("路径越界: " + path + " 不在项目目录内");
        }
        return resolved;
    }
}
```

**安全体系三层防护：**
```
第 1 层：PathGuard + CommandGuard（静态规则，无条件执行）
    ↓ 通过
第 2 层：HITL 审批（运行时人工确认，可开关）
    ↓ 批准
第 3 层：AuditLog 审计链（所有危险操作留痕，可追溯）
```

**面试话术：**
> "安全体系分三层：PathGuard 做路径围栏，限定所有文件操作必须在项目目录内；CommandGuard 做命令黑名单，sudo、rm -rf / 这类直接拒绝；这两层是静态规则无条件执行。第三层是 HITL 人机审批，对 write_file、execute_command 等危险工具在运行时弹出确认提示，支持单次批准、全部放行、拒绝、跳过四种决策。所有危险操作都记录到 AuditLog 审计链，JSONL 格式追加写入，支持 /audit 命令查看。当前版本只实现了前两层静态检查，HITL 作为后续扩展已设计好架构。"

---

---

### 11. Skill 插件系统（设计说明，暂不实现）

**定位：** 可扩展的技能插件机制，通过 Markdown 文件定义技能，运行时注入 system prompt 和自定义工具。当前精简版不实现，以下为完整设计。

**核心思想：** 每个 Skill 是一个带 YAML frontmatter 的 Markdown 文件（SKILL.md），支持三层目录加载，Agent 运行时将启用的 Skill 内容注入到 system prompt。

**Skill 文件结构：**
```
~/.paicli/skills/
├── web-access/
│   └── SKILL.md
├── code-review/
│   └── SKILL.md
└── my-custom-skill/
    └── SKILL.md

# 三层目录优先级：
# 1. 内置 Skill（打包在 jar 的 resources/skills/ 里）
# 2. 用户级 Skill（~/.paicli/skills/）
# 3. 项目级 Skill（.paicli/skills/，随仓库提交，团队共享）
```

**SKILL.md 格式：**
```markdown
---
name: web-access
description: 帮助 Agent 访问和分析网页内容
version: 1.0
tools: [web_fetch, web_search]     # 可选：该 Skill 提供的自定义工具
enabled: true                       # 默认启用
---

你是一个擅长网页内容提取和分析的助手。当用户需要查看网页内容时：

1. 使用 web_fetch 获取网页正文
2. 对获取的内容进行结构化分析
3. 提取关键信息并总结

注意事项：
- 优先提取正文，忽略导航栏和广告
- 如果页面需要登录，提示用户
```

```java
// Skill 数据模型
public record Skill(
    String name,            // 技能名
    String description,     // 描述
    String body,            // Markdown 正文（注入到 prompt）
    List<String> tools,     // 自定义工具列表
    boolean enabled,        // 是否启用
    Path sourceDir          // 来源目录（用于区分内置/用户/项目）
) {}

// YAML Frontmatter 解析器
public class SkillFrontmatterParser {
    public static Skill parse(Path skillMd) throws IOException {
        String content = Files.readString(skillMd);

        // 解析 --- 包围的 YAML frontmatter
        if (!content.startsWith("---")) {
            throw new IOException("SKILL.md 缺少 frontmatter");
        }
        int endIndex = content.indexOf("---", 3);
        String frontmatter = content.substring(3, endIndex).trim();
        String body = content.substring(endIndex + 3).trim();

        // 简单解析 YAML（name, description, tools, enabled）
        Map<String, String> meta = parseSimpleYaml(frontmatter);

        return new Skill(
            meta.get("name"),
            meta.get("description"),
            body,
            parseToolsList(meta.get("tools")),
            Boolean.parseBoolean(meta.getOrDefault("enabled", "true")),
            skillMd.getParent()
        );
    }
}

// Skill 注册表：扫描三层目录，加载所有 Skill
public class SkillRegistry {
    private final Path builtinDir;   // jar 内 resources/skills/
    private final Path userDir;      // ~/.paicli/skills/
    private final Path projectDir;   // .paicli/skills/
    private final SkillStateStore stateStore;  // 启用/禁用状态持久化

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public void reload() {
        skills.clear();
        // 按优先级加载：内置 < 用户 < 项目（后加载的覆盖先加载的）
        scanDir(builtinDir);
        scanDir(userDir);
        scanDir(projectDir);
        // 应用持久化的启用/禁用状态
        applyStateOverrides();
    }

    private void scanDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> dirs = Files.list(dir)) {
            dirs.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillMd = skillDir.resolve("SKILL.md");
                if (Files.exists(skillMd)) {
                    Skill skill = SkillFrontmatterParser.parse(skillMd);
                    skills.put(skill.name(), skill);
                }
            });
        }
    }

    public List<Skill> enabledSkills() {
        return skills.values().stream()
            .filter(Skill::enabled)
            .toList();
    }
}

// Skill 上下文注入器：把启用的 Skill 内容组装成 prompt 片段
public class SkillContextBuffer {
    private final SkillRegistry registry;

    public String buildSkillContext() {
        List<Skill> enabled = registry.enabledSkills();
        if (enabled.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## 已启用的 Skills\n\n");
        for (Skill skill : enabled) {
            sb.append("### ").append(skill.name()).append("\n");
            sb.append(skill.description()).append("\n\n");
            sb.append(skill.body()).append("\n\n");
        }
        return sb.toString();
    }

    // 生成 Skill 索引（简短列表，放在 system prompt 里）
    public String buildSkillIndex() {
        List<Skill> enabled = registry.enabledSkills();
        if (enabled.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("可用 Skills:\n");
        for (Skill skill : enabled) {
            sb.append("- ").append(skill.name()).append(": ").append(skill.description()).append("\n");
        }
        sb.append("\n使用 /skill show <name> 查看 Skill 详情\n");
        return sb.toString();
    }
}

// Agent 中使用
public class Agent {
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptContext.builder()
            .memoryContext(memoryContext)
            .skillIndex(skillContextBuffer.buildSkillIndex())    // Skill 索引
            .skillBody(skillContextBuffer.buildSkillContext())    // Skill 正文
            .build());
    }
}

// 用户通过斜杠命令管理 Skill
// /skill list       → 列出所有 Skill 及状态
// /skill show <name> → 查看 Skill 详情
// /skill on <name>  → 启用
// /skill off <name> → 禁用
// /skill reload     → 重新扫描目录
```

**三层加载优先级：**
```
项目级 (.paicli/skills/)   ← 最高优先级，随仓库提交，团队共享
    ↓ 覆盖
用户级 (~/.paicli/skills/)  ← 个人定制
    ↓ 覆盖
内置 (jar/resources/skills/) ← 最低优先级，打包分发
```

**面试话术：**
> "项目有一个 Skill 插件系统，类似 VS Code 的扩展。每个 Skill 是一个带 YAML frontmatter 的 Markdown 文件，定义了名称、描述、要注入的上下文内容。支持三层目录加载：内置（打包在 jar 里）、用户级（~/.paicli/skills/）、项目级（.paicli/skills/ 随仓库提交），高优先级覆盖低优先级。Agent 运行时，SkillContextBuffer 把所有启用的 Skill 内容注入到 system prompt，让 Agent 获得额外的领域知识和行为指导。用户通过 /skill 命令管理启用/禁用，状态持久化到 JSON 文件。"

---

---

### 12. MCP 协议集成（Model Context Protocol）

**核心思想：** 实现 MCP Client，通过 JSON-RPC 2.0 协议与外部 MCP Server 通信。启动时加载配置，启动 Server 子进程，握手后自动发现并注册工具到 ToolRegistry，Agent 调用 MCP 工具时通过 `tools/call` 转发。

**调用流程：**
```
用户输入 → Agent 选择 MCP 工具（mcp__server__tool）
    ↓
ToolRegistry.executeTool() 找到注册的 lambda
    ↓
McpClient.callTool(toolName, arguments)
    ↓
McpTransport.send("tools/call", params)
    ↓
写入 JSON-RPC 请求到 Server stdin
    ↓
读取 Server stdout 响应
    ↓
解析 result.content[].text
    ↓
返回结果给 Agent
```

**配置文件格式（~/.myagent/mcp.json）：**
```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    }
  }
}
```

```java
// ===== MCP 传输层（stdio）=====

public class McpTransport implements AutoCloseable {
    private final Process process;          // Server 子进程
    private final BufferedWriter writer;    // → Server stdin
    private final BufferedReader reader;    // ← Server stdout
    private final AtomicInteger requestId;  // JSON-RPC 请求 ID 自增

    // 启动 Server 子进程
    public McpTransport(String command, String[] args, String workDir) {
        ProcessBuilder pb = new ProcessBuilder(command, ...args);
        pb.redirectErrorStream(false);  // stderr 不混入 JSON-RPC 流
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    }

    // 发送 JSON-RPC 请求并等待响应
    public JsonNode send(String method, JsonNode params) {
        int id = requestId.getAndIncrement();
        // 构建: {"jsonrpc":"2.0", "id":N, "method":"xxx", "params":{...}}
        writer.write(json + "\n");
        writer.flush();

        // 读取响应，跳过通知（无 id 的消息），匹配当前 id
        while (true) {
            String line = reader.readLine();
            JsonNode response = parse(line);
            if (!response.has("id")) continue;       // 跳过通知
            if (response.get("id").asInt() != id) continue;  // 不是我们的
            if (response.has("error")) throw error;
            return response.get("result");
        }
    }
}

// ===== MCP Client =====

public class McpClient {
    // MCP 握手：initialize → initialized 通知
    public void initialize() {
        transport.send("initialize", {
            protocolVersion: "2024-11-05",
            clientInfo: { name: "my-agent", version: "1.0.0" },
            capabilities: { tools: {} }
        });
        transport.notify("notifications/initialized", {});
    }

    // 获取 Server 工具列表
    public List<McpToolDescriptor> listTools() {
        JsonNode result = transport.send("tools/list", {});
        // 解析 result.tools[] → name, description, inputSchema
        return parseTools(result);
    }

    // 调用 Server 工具
    public String callTool(String toolName, String arguments) {
        JsonNode result = transport.send("tools/call", {
            name: toolName,
            arguments: parse(arguments)
        });
        // 提取 result.content[].text
        return extractText(result);
    }
}

// ===== MCP Server 管理器 =====

public class McpServerManager {
    // 启动所有配置的 MCP Server
    public void startAll() {
        for (config : loadConfigs()) {
            McpTransport transport = new McpTransport(config.command, config.args);
            McpClient client = new McpClient(name, transport);
            client.initialize();              // 握手
            List<Tool> tools = client.listTools();  // 发现工具

            // 动态注册到 ToolRegistry
            for (tool : tools) {
                toolRegistry.registerMcpTool(
                    "mcp__" + serverName + "__" + tool.name,  // 命名空间
                    tool.description,
                    tool.inputSchema,
                    args -> client.callTool(tool.name, args)  // 转发执行
                );
            }
        }
    }
}
```

**MCP 工具命名空间：**
```
mcp__filesystem__read_file     ← filesystem server 的 read_file 工具
mcp__fetch__fetch              ← fetch server 的 fetch 工具
mcp__github__create_issue      ← github server 的 create_issue 工具
```

**面试话术：**
> "我实现了 MCP Client，通过 JSON-RPC 2.0 协议与 MCP Server 通信。传输层用 stdio——启动 Server 子进程，通过 stdin 发请求、stdout 读响应。启动时先 initialize 握手协商协议版本和capabilities，然后 tools/list 自动发现 Server 暴露的工具，按 mcp__server__tool 命名空间注册到 ToolRegistry。Agent 调用时通过 tools/call 转发，Server 执行后返回结果。配置文件支持用户级和项目级两层合并。这样 Agent 就能动态获得新工具而不需要改代码。"

---

## 六、待补充

- [ ] 完善 Plan-and-Execute 的用户交互（Ctrl+O 展开、ESC 取消）
- [ ] HITL 人机审批（设计已完成，待实现）
- [ ] Skill 插件系统（设计已完成，待实现）
- [ ] 更多内置工具（web_search、web_fetch）
- [ ] 渲染系统（inline 流式渲染）

---

---

## 七、大厂面试模拟 Q&A

> 模拟真实面试场景，覆盖架构设计、设计模式、并发编程、AI/LLM、RAG、工程实践等方向。

---

### A. 项目整体架构

---

**Q1：简单介绍一下你这个项目。**

> 这是一个终端里的 AI 编程助手，类似简化版的 Claude Code。核心是一个 ReAct Agent 循环：用户输入任务后，Agent 把对话历史和可用工具定义发给大模型，模型决定是直接回答还是调用工具。如果调工具，执行完把结果灌回对话历史，继续下一轮循环，直到模型认为不需要再调工具为止。支持两种模式——简单任务直接 ReAct，复杂任务走 Plan-and-Execute 先拆解再逐步执行。工具系统用注册表模式，支持文件读写、命令执行、代码搜索，多个工具可以并行执行。还有长期记忆系统、RAG 代码检索、上下文自动压缩、Token 预算控制等能力。整个项目用 Java 17 实现，大约 2500 行核心代码。

---

**Q2：为什么选 ReAct 而不是其他 Agent 架构？**

> ReAct 的优势是简单且通用。Think-Act-Observe 的循环天然适合终端编程助手这种"一步步试、一步步调"的场景。模型先思考该做什么，然后调工具执行，观察结果后再决定下一步。对比 Plan-and-Execute，ReAct 更灵活，不需要预先生成完整计划，适合探索性的任务。但 ReAct 的缺点是复杂任务容易迷失方向，所以我加了 Plan-and-Execute 模式作为补充——复杂任务先让 LLM 拆解成 3-5 个步骤，用户确认后再逐步执行，每一步内部还是走 ReAct。两种模式通过策略模式切换，用户输入 /plan 命令就能用计划模式。

---

**Q3：你的 Agent 循环是怎么退出的？不会死循环吗？**

> 主退出条件由 LLM 自己决定——当它返回 content 而不再调用工具时，循环就退出了。但为了防止异常情况，我设计了三道保险阀：第一，停滞检测，用滑动窗口记录最近 3 轮工具调用的签名（工具名+参数拼接），如果连续 3 轮完全相同就判定为死循环，强制退出；第二，Token 预算，累计 input+output token 超过阈值后收尾；第三，硬轮数上限 30 轮作为兜底。三个条件先到先触发。

---

**Q4：Plan-and-Execute 的计划是怎么生成的？用户能修改计划吗？**

> 计划生成就是让 LLM 做任务拆解。我设计了一个 prompt 模板，要求模型把任务拆成 3-5 个具体步骤，每步一行。生成后展示给用户，用户可以按 Enter 确认执行、ESC 取消、按 I 输入补充要求后让模型重新规划。确认后逐步执行，每一步复用已有的 ReAct Agent。执行完汇总所有步骤的结果返回给用户。

---

### B. 设计模式

---

**Q5：你项目里用了哪些设计模式？**

> 主要用了 5 种：
>
> **策略模式**——ReAct 和 Plan-and-Execute 两种 Agent 模式通过 Callable 抽象切换，运行时根据用户命令选择策略。
>
> **模板方法**——LLM 客户端的 AbstractOpenAiCompatibleClient 封装了 SSE 流式解析、请求构建、Token 统计等通用逻辑，子类只覆盖 getApiUrl()、getModel()、getApiKey() 三个抽象方法。
>
> **工厂模式**——LlmClientFactory 根据 provider 名称创建对应的客户端实例，用户切模型时工厂动态创建新实例替换旧引用。
>
> **注册表模式**——ToolRegistry 用 ConcurrentHashMap 管理所有工具，每个工具是一个 Tool record 包含名称、描述、参数 Schema 和 lambda 执行器。新增工具只需要 put 一个新条目。
>
> **门面模式**——MemoryManager 作为门面统一管理短期记忆、长期记忆、上下文压缩、Token 预算等子系统，Agent 只和 MemoryManager 交互。

---

**Q6：为什么 LLM 客户端用模板方法而不是策略模式？**

> 因为所有模型提供商（DeepSeek、Kimi、GLM）的 API 都兼容 OpenAI 的 SSE 流式协议，95% 的逻辑是相同的——构建请求体、发送 HTTP、逐行解析 SSE、累加 delta、组装 ToolCall。差异只在 API URL、模型名、API Key 这几个字段，以及个别模型的定制行为（比如 DeepSeek 需要强制 HTTP/1.1，GLM 的图片格式不同）。模板方法正好适合这种"大段相同、小段不同"的场景。策略模式更适合算法整体替换的场景，比如搜索引擎的切换。

---

**Q7：工具注册表为什么用 ConcurrentHashMap 而不是普通 HashMap？**

> 因为工具有并行执行的场景。当 LLM 一轮返回多个 tool_call 时，我用线程池并发执行它们，多个线程同时从 Map 中 get 工具定义。ConcurrentHashMap 的读操作是无锁的，不会成为并行执行的瓶颈。而且后续扩展 MCP 工具时，工具可以在运行时动态注册和注销，ConcurrentHashMap 天然支持这种并发修改。

---

### C. 并发编程

---

**Q8：工具并行执行是怎么实现的？**

> 当 LLM 一轮返回多个 tool_call 时，我用 ExecutorService 创建一个固定大小线程池（最多 4 个线程），把每个工具调用包装成 Callable 提交进去，用 invokeAll 等待全部完成，设置 90 秒批次超时。结果按传入顺序返回，保证回灌消息历史时顺序和原始 tool_call 一致。如果某个工具超时，future 会被 cancel，返回超时结果而不是阻塞其他工具。单个工具（只有一个 tool_call 时）直接同步执行，不开线程池，避免不必要的开销。

---

**Q9：并行执行工具会不会有线程安全问题？**

> 会。主要风险在两个地方：第一，write_file 工具如果两个并行调用同时写同一个文件会冲突，但因为 LLM 很少在一轮里让 Agent 同时写同一个文件两次，所以实践中问题不大。如果要做严格保护，可以给 write_file 加文件级锁。第二，execute_command 如果两个命令同时修改同一个文件也会有问题，这靠 LLM 自己判断——模型通常不会在一轮里发出两个互相冲突的命令。工具执行本身是无状态的——每个调用独立拿到参数、执行、返回结果，不共享可变状态，所以 ToolRegistry 的并发安全性靠 ConcurrentHashMap 就够了。

---

**Q10：ESC 取消任务是怎么实现的？**

> 分两步。第一步，Agent 执行放在单独的线程里，用 Future 持有结果。主线程进入 terminal raw mode 监听键盘输入，每 150ms 轮询一次 Future 是否完成，同时检查是否按了 ESC。如果检测到孤立的 ESC（不是方向键的 ESC 序列），就调 CancellationToken.cancel() 并 Future.cancel(true)。第二步，Agent 循环内部在每个迭代开始时检查 CancellationContext.isCancelled()，如果已取消就提前退出返回"已取消"。这里有个陷阱：方向键也是 ESC 开头的控制序列（比如 ESC[A），不能误判为取消。我的做法是读到 ESC 后等 80ms 看有没有后续字节，如果有就是控制序列忽略掉，没有就是孤立 ESC 触发取消。

---

### D. LLM / SSE 流式

---

**Q11：SSE 流式解析是怎么做的？**

> OkHttp 拿到 ResponseBody 后，通过 BufferedSource 逐行读取。每一行格式是 "data: {JSON}"，我解析 JSON 中的 choices[0].delta 对象，提取三种增量：content（正文增量）、reasoning_content（思考过程增量）、tool_calls（工具调用增量）。每收到一个 delta 就通过 StreamListener 回调推给渲染器实现逐字输出。工具调用的参数是逐字符到达的，用 ToolCallAccumulator 按 index 聚合，等收到 [DONE] 信号后再一次性组装成完整的 ToolCall 列表。

---

**Q12：为什么 tool_calls 需要累加器？**

> 因为 OpenAI 的 SSE 协议里，工具调用的参数是流式分块发送的。比如调 read_file 时参数 {"path": "src/Main.java"}，不是一次性发过来的，而是分成多个 delta 片段，每个片段只有参数 JSON 的一小部分。所以我需要按 tool_call 的 index 维护累加器，把每个 delta 的 arguments fragment 拼接起来，等流结束后再统一 parse 成完整的 JSON。

---

**Q13：Prompt Cache 是怎么做的？**

> Prompt Cache 的核心思想是：如果连续请求的前缀相同（system prompt + 工具定义），API 服务端可以缓存这部分的 tokenization 结果，后续请求复用缓存，减少 input token 费用。我的实现方式是保证 system prompt 的稳定性——它放在 conversationHistory[0]，只有长期记忆变化时才更新。工具定义每轮也相同。这样 API 前缀不变，缓存命中率最高。DeepSeek 支持自动前缀缓存，不需要额外标记；GLM 需要在 system message 上加 cache_control 标记。响应里的 cachedInputTokens 字段就是统计缓存命中的 token 数。

---

### E. 记忆系统

---

**Q14：短期记忆和长期记忆有什么区别？**

> 短期记忆管理当前对话的消息，用 LinkedHashMap 维护，有 token 预算控制（比如 30000 token），超出时自动淘汰最旧的条目。生命周期是当前会话，/clear 命令就清空了。
>
> 长期记忆存的是跨会话复用的关键事实，比如"用户偏好中文回答"、"项目用 Java 17"这种稳定信息。用 ConcurrentHashMap 存储，JSON 文件持久化到 ~/.paicli/memory/。支持 project 和 global 两种作用域：project 级只在当前项目目录下可见（通过路径匹配），global 级所有项目都可见。
>
> 每轮对话开始前，我会从长期记忆中检索和用户输入相关的事实，注入到 system prompt。只检索长期记忆不检索短期，因为短期记忆的内容已经在 conversationHistory 里了，再注入会让模型把当前请求误读成历史事实。

---

**Q15：对话压缩是怎么做的？为什么需要两套压缩器？**

> 压缩用的是 ConversationHistoryCompactor。每轮调 LLM 前，估算 conversationHistory 的 token 数，如果超过阈值（比如上下文窗口的 80%），就把早期对话压缩。做法是找到最近 3 个 user message 的位置作为分割点，分割点之前的消息全部喂给 LLM 生成摘要，然后重建历史：[system prompt] + [摘要] + [保留的近期消息]。分割点必须在 user message 边界切，避免切断 tool_call/tool_result 的成对关系，否则 OpenAI 协议会报错。
>
> 项目里原来有个 ContextCompressor 压的是 ConversationMemory（PaiCLI 内部记忆条目），后来发现 Agent 直接维护 conversationHistory，两者并行导致旧压缩器没有真正减少发给 LLM 的 token。所以补了 ConversationHistoryCompactor 直接压实际消息列表。这是工程演进中发现的问题。

---

**Q16：长期记忆怎么检索的？用了向量相似度吗？**

> 没用向量，用的关键词匹配 + 时间衰减。原因是长期记忆条目本身就很精炼（每条就一句话），关键词匹配足够了，引入向量化反而增加复杂度和延迟。检索时先 jieba 分词把 query 切成词，然后逐条匹配，计算匹配词数/总词数得到关键词分数。再乘以时间衰减系数（24 小时内从 1.0 衰减到 0.5），长期记忆额外乘 1.2 权重因为它更精炼。最后按分数降序取 Top 10，受 token 预算约束。

---

### F. RAG

---

**Q17：RAG 的代码切块怎么做的？为什么分三级？**

> 用 JavaParser 做 AST 解析，递减切分三级：文件级（整个文件）→ 类级（ClassOrInterfaceDeclaration）→ 方法级（MethodDeclaration）。每级保留起止行号和所属文件路径。分三级的原因是不同查询需要不同粒度：搜"项目的整体结构"需要文件级，搜"UserService 这个类"需要类级，搜"登录方法的实现"需要方法级。如果只有一级，要么太粗（返回整个文件）要么太细（丢失上下文）。AST 解析失败时降级只保留文件级 chunk。

---

**Q18：为什么检索策略是先 grep 再向量？**

> 因为大多数编程场景的查询是精确的——搜一个类名、方法名、变量名，grep 关键字搜索比向量检索更快更准。向量检索擅长的是语义模糊查询，比如"用户认证是怎么实现的"这种自然语言描述。所以我设计了两阶段策略：先走 grep_code 做精确搜索，如果命中 3 条以上说明精确结果够用，直接返回；不够才走向量 + 关键词的混合检索。这样兼顾了速度和召回率。

---

**Q19：RRF 融合排序的原理是什么？为什么不用简单的加权求和？**

> RRF 是 Reciprocal Rank Fusion，按排名倒数加权：score = 1/(k+rank)，k 是常数通常取 60。两个通道的结果分别计算 RRF 分数后累加，按总分降序取 Top K。不用加权求和的原因是向量相似度和 TF-IDF 分数的量纲不同，向量余弦值在 0-1 之间，TF-IDF 可能是任意正数，直接加权需要归一化，而归一化方式的选择会引入额外调参。RRF 只看排名不看分数，天然规避了量纲问题，更鲁棒。

---

**Q20：上下文扩展怎么做的？为什么不直接返回更大的 chunk？**

> 命中一个 chunk 后，我额外取它前后各 1 个相邻 chunk 一起返回。这样用户看到的不只是一个孤立的方法，还有它上面的类声明和下面的相关方法。不直接切更大的 chunk 是因为：大 chunk 检索精度低（一段 500 行代码里可能只有 10 行和查询相关），小 chunk 检索精度高但上下文不完整。用小 chunk 检索 + 扩展上下文是两全的方案——检索时精准定位，返回时补充上下文。

---

**Q21：向量存储为什么用 SQLite 而不是专门的向量数据库？**

> 因为项目定位是本地 CLI 工具，不应该要求用户额外安装 Milvus 或 Pinecone。SQLite 零配置、单文件、嵌入式，完美适合这个场景。向量存成 JSON 字符串字段，检索时全量扫描计算余弦相似度。对于个人项目级别的代码量（几万到几十万行），全量扫描的延迟在可接受范围内。如果数据量真的大了，可以用 SQLite 的向量扩展（sqlite-vss）或者迁移到专用向量数据库，但当前阶段没必要。

---

### G. Token 预算

---

**Q22：Token 预算怎么估算的？准确吗？**

> 用字符数除以 3 作为经验值。中文大约 1.5 字一个 token，英文大约 4 个字符一个 token，代码混合场景下 3 是一个合理的折中。不准确，但够用——Token 预算不需要精确到个位数，它的作用是判断"是否接近上限"来触发压缩，不是精确计费。真正的 token 消耗从 API 响应的 usage 字段获取。用 tiktoken 库可以更准确，但引入额外依赖不值得。

---

**Q23：为什么 Token 预算默认是无限的？**

> 因为长上下文模型（GLM 200K、DeepSeek 1M）配合套餐用户的场景，硬预算反而会造成不必要的中断。LLM 自己知道什么时候任务完成了（不再调工具就返回），预算只在异常情况下兜底。需要严格成本控制的场景（CI 自动化批跑）可以通过系统属性显式设置。这是"让正常路径畅通，异常路径有保护"的设计思路。

---

### H. 安全

---

**Q24：你的 Agent 怎么防止执行危险命令？**

> 三层防护。第一层 PathGuard 做路径围栏，所有文件操作的路径必须 resolve 到项目根目录之内，normalize 后检查 startsWith，防止 ../ 逃逸。第二层 CommandGuard 做命令黑名单，sudo、rm -rf /、mkfs、dd of=/dev、fork bomb 这些模式直接拒绝。这两层是静态规则无条件执行。第三层是 HITL 人机审批，对 write_file、execute_command 这类危险工具弹出确认提示，支持批准/全部放行/拒绝/跳过四种决策。所有危险操作都记录到 AuditLog 审计链。

---

**Q25：路径围栏怎么防止路径穿越攻击？**

> 用 Path.resolve() 拼接用户输入的路径，然后 normalize() 消除 . 和 ..，最后检查 normalize 后的路径是否 startsWith 项目根目录。比如用户输入 "../../etc/passwd"，resolve 后变成 "/project/root/../../etc/passwd"，normalize 后变成 "/etc/passwd"，不以项目根开头，直接拒绝。这是 Java NIO 的标准做法。

---

### I. 工程实践与 Trade-off

---

**Q26：这个项目有什么你觉得做得不好的地方？**

> 两个点。第一，短期记忆和 conversationHistory 并行维护的问题。ConversationMemory 和 Agent 的 conversationHistory 是两个独立的数据结构，存了相似但不完全相同的信息。压缩的时候需要分别处理，增加了复杂度。更好的设计是统一成一个数据源，其他组件从它派生视图。第二，Token 估算是粗略的字符数除以 3，在纯中文或纯代码场景偏差较大。虽然不影响功能（预算判断是阈值触发的），但如果要做精确的成本控制，应该引入 tiktoken。

---

**Q27：如果让你重新设计，你会怎么改？**

> 三个方面。第一，把 conversationHistory 作为唯一数据源，短期记忆、压缩、Token 统计都从它派生，消除双数据源的不一致。第二，给 RAG 加增量索引——当前每次 /index 都是全量重建，应该记录文件 hash，只对变化的文件重新切块和向量化。第三，加 WebSocket 或 SSE 的 Runtime API，让外部 IDE 插件可以提交任务给 Agent 并实时接收流式结果，这样就能从终端工具扩展成 IDE 插件的后端。

---

**Q28：你的项目和 Claude Code / Cursor 有什么差距？**

> 差距主要在四个方面。第一，模型能力——Claude Code 用的是 Claude 模型，代码理解和工具调用能力远强于开源模型，这是模型层面的差距不是工程层面的。第二，工具丰富度——Claude Code 有几十个内置工具加上 MCP 生态，我只有 4 个基础工具。第三，编辑体验——Cursor 有 LSP 集成、内联 diff、多文件编辑，我的 write_file 是全量覆盖。第四，上下文管理——Claude Code 有更精细的 prompt engineering 和上下文策略。但架构层面，核心思路是相似的：ReAct 循环 + 工具注册 + 流式输出 + 记忆管理。

---

**Q29：为什么用 Java 而不是 Python 做这个项目？**

> 三个原因。第一，Java 的类型系统在重构时更安全，Agent 项目有大量的 record 和接口定义，编译期就能发现类型不匹配。第二，Java 的并发模型（ExecutorService、Future、ConcurrentHashMap）比 Python 的 asyncio 更成熟，工具并行执行写起来更直观。第三，Java 生态有 JavaParser（AST 解析）、JGit（Git 操作）、OkHttp（HTTP 客户端）这些高质量库，不需要造轮子。当然 Python 在 AI 领域的生态更好（LangChain、LlamaIndex），如果目标纯粹是快速原型，Python 更合适。

---

### J. 压力面 / 深追

---

**Q30：如果 LLM 一直调工具不停下来怎么办？**

> 刚才说了三道保险阀，但还有一种极端情况：模型每轮调不同的工具，不重复也不停下来，比如先读文件 A，再读文件 B，再读文件 C……这种情况下停滞检测不会触发（因为工具参数不同），但 30 轮硬上限会兜底。另外 Token 预算如果显式设置了，累计 token 超了也会停。实际使用中这种情况很少见，因为大多数模型在 10 轮左右就会得出结论。

---

**Q31：你的 RAG 召回率怎么样？怎么评估？**

> 没有做量化评估（个人项目条件有限），但做了定性优化。三个提升召回率的手段：第一，三级切块保证不同粒度的查询都能命中；第二，混合检索（向量 + TF-IDF）覆盖了语义和精确两种查询类型；第三，上下文扩展补充了前后文。降低误召回的手段：RRF 融合让两个通道互相验证，排名靠后的噪声结果被过滤掉。如果要正式评估，应该建一个 golden set（标注好的 query-chunk 对），跑 recall@K 和 MRR 指标。

---

**Q32：如果两个用户同时用你的 Agent 操作同一个项目会怎样？**

> 当前设计不支持多用户并发。Agent 是单进程的 CLI 工具，一个终端一个实例。如果两个人同时操作同一个项目，文件系统层面会有冲突（同时写同一个文件）。长期记忆的 JSON 文件用 ConcurrentHashMap 做内存级并发安全，但磁盘持久化没有加文件锁。如果要支持多用户，需要加文件锁或迁移到数据库存储，以及给 ToolRegistry 的写操作加分布式锁。但作为个人 CLI 工具，这个场景不在设计范围内。

---

**Q33：你的 Embedding 调用是同步的还是异步的？索引大项目时会不会很慢？**

> 当前是同步的，每个 chunk 调一次 Embedding API。大项目（比如 10 万行代码，大概 2000 个 chunk）索引可能需要几分钟。优化方向有三个：第一，批量化——把多个 chunk 合并成一次 Embedding 请求（大多数 API 支持 batch）；第二，异步化——用 CompletableFuture 并发发送多个 Embedding 请求；第三，增量索引——记录文件 hash，只重新 embed 变化的文件。当前版本没做这些优化，因为定位是个人工具，索引频率不高。

---

---

### K. 大模型接入

---

**Q34：你用的什么大模型？为什么选它？**

> 我自己买的 DeepSeek 的 API，按量付费。选它的原因有三个：第一，DeepSeek 兼容 OpenAI 协议，接一个就通了所有兼容的模型，换模型只需要改 URL 和 Key；第二，DeepSeek 的代码理解和工具调用能力在开源模型里是第一梯队的，性价比高；第三，按量付费没有套餐限制，开发调试灵活。架构上通过 LlmClientFactory 可以一键切换到 GLM、Kimi、Qwen 等其他 provider，不影响上层任何代码。

---

**Q35：怎么接入的大模型？用的什么协议？**

> 用的是 OpenAI 兼容的 Chat Completions API，SSE 流式协议。具体流程：
>
> 第一步，构建请求体 JSON，包含 model、messages（对话历史）、tools（工具定义的 JSON Schema）、stream=true。
>
> 第二步，用 OkHttp 发送 POST 请求到 `{base_url}/chat/completions`，Header 带 `Authorization: Bearer {api_key}`。
>
> 第三步，响应是 SSE 格式，每行一个 `data: {JSON}` 事件。我逐行读取，解析 `choices[0].delta` 对象，提取三种增量：content（正文）、reasoning_content（思考过程）、tool_calls（工具调用）。每收到一个 delta 就通过 StreamListener 回调推给渲染器实现逐字输出。
>
> 第四步，收到 `data: [DONE]` 信号后结束流，组装完整的 ChatResponse 返回给 Agent。
>
> 整个解析逻辑封装在 AbstractOpenAiCompatibleClient 基类里，所有兼容 OpenAI 协议的模型都能直接复用。

---

**Q36：Function Calling / Tool Use 是怎么实现的？**

> 分两端：请求端和响应端。
>
> 请求端：把 ToolRegistry 里所有工具的定义转成 JSON Schema 格式，放在请求体的 tools 字段里。每个工具包含 name、description、parameters（参数的 JSON Schema）。LLM 看到这些定义后，如果决定调工具，会在响应里返回 tool_calls 数组。
>
> 响应端：tool_calls 里每个元素有 id（调用标识）、function.name（工具名）、function.arguments（参数 JSON 字符串）。我解析出来后，从 ToolRegistry 找到对应的 ToolExecutor，把 arguments parse 成 Map 传给它执行，拿到结果后以 role=tool、tool_call_id=原始 id 的 message 追加到对话历史。下一轮 LLM 就能看到工具结果继续思考。
>
> 这里有个细节：tool_call 的参数在 SSE 流式响应里是逐字符到达的，需要用累加器按 index 聚合，等流结束后再统一 parse。

---

**Q37：API 调用失败了怎么处理？**

> 分三层处理。第一层，网络层异常（超时、连接失败），OkHttp 抛 IOException，Agent.run() catch 后返回"❌ 调用 LLM 失败"给用户，不重试——因为可能是网络问题，重试也没用。第二层，API 业务错误（401 认证失败、429 限流、500 服务异常），HTTP 状态码非 2xx 时抛 IOException 附带错误信息。第三层，流式响应中间出错（比如 SSE 流中途断开、JSON 解析失败），同样抛异常让 Agent 兜底。没有做自动重试和退避，因为 CLI 场景下用户看到错误后手动重试更可控。如果是生产服务，应该加指数退避重试 + 熔断。

---

**Q38：模型的上下文窗口有多大？你怎么处理的？**

> 不同模型差异很大：GLM 200K token，DeepSeek 1M token，Qwen 系列 128K-1M。我的处理方式是：每个 LlmClient 实现通过 maxContextWindow() 返回自己的窗口大小，ContextProfile 根据这个值计算各项预算——对话历史可用额度 = 窗口 - 系统预留(500) - 工具预留(800) - 回复预留(2000)。当对话历史接近 80% 可用额度时自动触发压缩。这样切到一个窗口更小的模型时，压缩会更频繁触发；切到大窗口模型时，可以保留更多历史。整个预算体系是跟着模型动态调整的。

---

**Q39：不同模型的 API 有差异吗？你怎么处理的？**

> 有差异，但通过模板方法模式屏蔽了。主要差异有这几个：
>
> 第一，reasoning_content 字段名不同。DeepSeek 用 `reasoning_content`，有的模型用 `reasoning`，GLM 用 `reasoning_details` 数组。我在 extractReasoningDelta() 方法里兼容了这几种格式。
>
> 第二，HTTP 协议兼容性。DeepSeek 服务端不支持 HTTP/2，OkHttp 默认会尝试 HTTP/2 导致失败，所以 DeepSeekClient 覆盖 httpClient() 强制用 HTTP/1.1。
>
> 第三，历史消息里要不要带 reasoning_content。DeepSeek 要求在历史消息里保留 reasoning_content（否则报错），其他模型不需要。通过 shouldSendReasoningContentInRequestHistory() 钩子控制。
>
> 第四，图片格式。GLM-5V 接受 base64 原始数据，其他模型需要 data URI。通过 toImageUrl() 钩子分模型处理。
>
> 这些差异都在子类里覆盖，基类完全不感知，加新模型只需要新建一个瘦子类。

---

**Q40：你的 Agent 调一次 LLM 大概花多少钱？成本怎么控制？**

> DeepSeek 按量付费，输入 2 元/百万 token，输出 8 元/百万 token。一次典型的编程任务（比如"分析这个文件的代码结构"），Agent 大概跑 5-8 轮，每轮输入 5000-20000 token（随对话积累增长），输出 500-2000 token，一次任务总消耗大约 5-10 万 input + 5000-10000 output，折合人民币约 0.15-0.3 元。我开发整个项目下来大概花了二三十块钱。
>
> 成本控制手段有四个：第一，Prompt Cache，system prompt 和工具定义不变时 API 复用缓存，缓存命中的 token 价格低很多；第二，上下文压缩，对话过长时自动摘要，减少每轮的 input token；第三，工具结果截断，工具返回的内容超过 500 字符就截断存入记忆，避免撑大上下文；第四，Token 预算，可以显式设置总预算上限，超了就停。

---

**Q41：如果让你接入一个全新的大模型，需要做什么？**

> 只需要三步。第一步，新建一个类继承 AbstractOpenAiCompatibleClient，覆盖 getApiUrl()、getModel()、getApiKey() 三个方法，指定这个模型的 API 地址、默认模型名和认证方式。第二步，如果有特殊行为（比如需要强制 HTTP/1.1、需要保留 reasoning_content），覆盖对应的钩子方法。第三步，在 LlmClientFactory 的 switch 里加一个 case。整个过程不需要改基类一行代码，也不需要改 Agent 层。这就是模板方法 + 工厂模式的好处——扩展新 provider 的成本极低。

---

**Q42：你了解 MCP 吗？它和你直接注册工具有什么区别？**

> 了解。MCP 是 Anthropic 推的 Model Context Protocol，解决的问题是 Agent 怎么动态连接外部工具服务器。它和直接注册工具的核心区别是：
>
> 直接注册工具是静态的——工具在编译时就写死在代码里，新增工具需要改代码重新打包。MCP 是动态的——工具服务器是独立进程，通过 JSON-RPC 2.0 协议通信，Agent 启动时加载 MCP 配置文件，启动各个 Server 进程，自动发现它们暴露的 tools 和 resources，动态注册到 ToolRegistry。用户想加新工具只需要装一个 MCP Server，不需要改 Agent 代码。
>
> 架构上 MCP 是 Client-Server 模式：Server 端暴露 tools（工具）和 resources（资源），支持 stdio 和 HTTP 两种传输方式。Client 端启动时加载配置，启动 Server 进程，通过 initialize 握手获取能力声明，然后 tools/list 获取工具列表，tools/call 调用工具。工具按 `mcp__servername__toolname` 命名空间注册，和内置工具统一调度。
>
> 我的精简版用的是静态注册，但 ToolRegistry 同时维护 builtin tools 和 mcp tools 两个 Map，架构上预留了扩展点。

---

---

### L. MCP 协议

---

**Q43：什么是 MCP？为什么要用它？**

> MCP 是 Model Context Protocol，Anthropic 推的开放协议，解决的问题是 Agent 怎么动态连接外部工具和数据源。没有 MCP 的话，所有工具都要在代码里静态注册，新增工具需要改代码重新打包。有了 MCP，工具由独立的 Server 进程提供，Agent 启动时自动发现并注册，装新工具只需要加一个 MCP Server 配置，不需要改 Agent 代码。类似于插件系统的思想，但用标准协议统一了接口。

---

**Q44：MCP 的通信协议是什么？JSON-RPC 2.0 怎么工作的？**

> MCP 基于 JSON-RPC 2.0 协议。每个请求格式是 `{"jsonrpc":"2.0", "id":N, "method":"xxx", "params":{...}}`，响应格式是 `{"jsonrpc":"2.0", "id":N, "result":{...}}` 或 `{"jsonrpc":"2.0", "id":N, "error":{...}}`。id 字段用于请求-响应配对，因为 stdio 是异步流，可能有通知消息（没有 id 的）穿插其中，需要通过 id 匹配正确的响应。我的实现里用 AtomicInteger 自增生成请求 id，读响应时跳过无 id 的通知消息，直到匹配到当前 id。

---

**Q45：MCP 的 stdio 传输层怎么管理子进程生命周期？**

> 启动时用 ProcessBuilder 创建子进程，拿到 stdin/stdout 管道。stdin 用于发送 JSON-RPC 请求（BufferedWriter），stdout 用于读取响应（BufferedReader），stderr 不合并到 stdout 避免污染 JSON-RPC 流。子进程的生命周期由 McpTransport 管理，实现 AutoCloseable，关闭时先关 writer/reader 管道，再 destroyForcibly() 强制终止子进程。McpServerManager 注册了 JVM ShutdownHook，程序退出时自动关闭所有 MCP Server 子进程。

---

**Q46：MCP 握手的 initialize 流程是什么？**

> 三步：第一步 Client 发 `initialize` 请求，带上 protocolVersion（"2024-11-05"）、clientInfo（名称和版本）、capabilities（声明支持哪些能力，比如 tools）。Server 返回自己的能力声明。第二步 Client 发 `notifications/initialized` 通知，告诉 Server 初始化完成。第三步 Client 就可以发 `tools/list` 获取工具列表了。这个握手过程确保双方协议版本兼容、能力协商完成后再进行工具发现和调用。

---

**Q47：MCP 工具怎么注册到 Agent 的？命名空间怎么设计的？**

> Server 启动后调 `tools/list`，返回工具数组，每个工具有 name、description、inputSchema（JSON Schema）。我按 `mcp__{serverName}__{toolName}` 的命名空间格式注册到 ToolRegistry。比如 filesystem server 的 read_file 工具注册为 `mcp__filesystem__read_file`。这样不会和内置工具冲突，也能从名字看出工具来源。注册时把执行逻辑包装成 lambda：`args -> client.callTool(toolName, args)`，Agent 调用时通过 ToolRegistry 找到这个 lambda，lambda 内部通过 McpTransport 发 `tools/call` 给 Server 执行。

---

**Q48：MCP 工具调用失败怎么处理？**

> 分三层。第一层，传输层错误（子进程崩溃、管道断开），McpTransport 抛 IOException，McpClient 捕获后返回"MCP Server 连接已断开"。第二层，JSON-RPC 错误（Server 返回 error 响应），McpTransport 解析 error.message 抛异常。第三层，工具执行错误（Server 内部错误），Server 正常返回但 result.isError=true，我把错误信息包装在返回字符串中。Agent 拿到错误信息后会作为 tool result 回灌给 LLM，LLM 可以决定重试或换方案。

---

**Q49：MCP 配置文件的合并策略是什么？**

> 支持两层配置合并：用户级 `~/.myagent/mcp.json` 和项目级 `.myagent/mcp.json`。项目级配置覆盖用户级同名 Server，实现项目定制。加载顺序是先用户级后项目级，后者 put 覆盖前者。这和 Skill 的三层加载、Git 配置的 global/local 覆盖是同一个设计思想。

---

**Q50：MCP 和直接写工具有什么优劣？什么场景该用 MCP？**

> 直接写工具的优势是性能好（无进程间通信）、调试简单（同一进程）、类型安全。MCP 的优势是解耦（工具和 Agent 独立部署）、生态（社区 MCP Server 可直接用）、安全性（工具在独立进程中崩溃不影响 Agent）。适合 MCP 的场景：工具需要独立的运行时环境（如 Node.js 的 filesystem server、Python 的 fetch server）、需要频繁新增工具、多 Agent 共享工具。不适合的场景：高频调用的核心工具（如 read_file），进程间通信的开销不值得。

---

> 最后更新：2026-06-17
