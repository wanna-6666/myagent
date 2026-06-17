# AGENTS.md

仓库给 AI Agent / 新线程使用的首读入口。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `PAI.md` > 4. `CLAUDE.md`

## 项目快照

- 项目名：`My Agent`
- 定位：基于 ReAct 的终端 AI 编程助手（精简版），对标 Claude Code 基础功能
- 技术栈：Java 17、Maven、OkHttp、JLine、JavaParser、SQLite、jieba 分词
- 代码规模：24 个源文件，约 2600 行
- 已实现功能：ReAct 循环、Plan-and-Execute、工具系统（5 个内置工具 + 并行执行）、SSE 流式 LLM 客户端、对话历史压缩、长期记忆（project/global 双作用域）、RAG 代码检索（三级切块 + 混合检索 + RRF）、Token 预算、Prompt Cache

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`DEEPSEEK_API_KEY`（在 `.env` 中配置）

## 常用命令

```bash
mvn clean package -DskipTests       # 构建（跳过测试）
java -jar target/my-agent-1.0-SNAPSHOT.jar   # 启动

# Agent 内斜杠命令
/plan              # 下一条任务使用 Plan-and-Execute 模式
/plan <任务>       # 直接用计划模式执行
/clear             # 清空对话历史（长期记忆保留）
/context           # 查看上下文和记忆状态
/save <事实>       # 保存项目级长期记忆
/save --global <事实>  # 保存全局长期记忆
/memory            # 查看长期记忆列表
/index             # 索引当前项目代码（RAG）
/search <查询>     # RAG 代码检索
/exit              # 退出
```

## 架构概览

两条主执行路径，共享 ToolRegistry / MemoryManager：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |

核心内置工具 5 个：`read_file` / `write_file` / `list_dir` / `grep_code` / `execute_command`

RAG 检索策略：先 `grep_code` 精确搜索，命中不足时走向量 + TF-IDF 混合检索，RRF 融合排序 + 上下文扩展。

## 仓库结构

```
src/main/java/com/myagent/
├── cli/Main.java           # 入口 + while 循环 + 斜杠命令
├── agent/
│   ├── Agent.java          # ReAct 主循环
│   ├── PlanExecuteAgent.java  # Plan-and-Execute 模式
│   └── AgentGuard.java     # 循环保护（停滞检测 + 轮数上限）
├── llm/
│   ├── LlmClient.java      # 接口 + 消息模型（Message/ToolCall/ChatResponse）
│   ├── AbstractOpenAiCompatibleClient.java  # SSE 流式基类（模板方法）
│   └── DeepSeekClient.java  # DeepSeek 实现（瘦子类）
├── tool/
│   ├── ToolRegistry.java   # 工具注册 + 并行执行
│   ├── ToolOutput.java     # 工具输出
│   ├── PathGuard.java      # 路径安全（防穿越）
│   ├── CommandGuard.java   # 命令黑名单
│   └── PolicyException.java  # 安全策略异常
├── memory/
│   ├── MemoryManager.java  # 记忆管理器（门面）
│   ├── ConversationMemory.java  # 短期记忆（token 预算 + 自动淘汰）
│   ├── LongTermMemory.java # 长期记忆（project/global + JSON 持久化）
│   ├── ConversationHistoryCompactor.java  # 对话压缩（LLM 摘要）
│   ├── MemoryEntry.java    # 记忆条目
│   └── TokenBudget.java    # Token 预算
└── rag/
    ├── CodeIndex.java      # RAG 检索引擎（混合检索 + RRF + 上下文扩展）
    ├── CodeChunk.java      # 代码块模型
    ├── CodeChunker.java    # 三级切块（JavaParser AST）
    ├── VectorStore.java    # SQLite 向量存储
    ├── EmbeddingClient.java  # Embedding API 客户端
    └── TfidfIndex.java     # TF-IDF 倒排索引
```

## 关键行为约束

### ReAct 循环

- 主退出条件：LLM 返回 content 不再调工具时退出
- 三道保险阀（先到先触发）：停滞检测（3 轮重复工具调用）→ Token 预算 → 硬轮数上限（30 轮）
- 每轮调 LLM 前检查 conversationHistory 是否需要压缩（80% 阈值触发）

### Memory

- 短期记忆：ConversationMemory 有 token 预算，超出自动淘汰最旧条目
- 长期记忆：只通过 `/save` 或用户明确要求保存；不要自动提取临时事实
- 长期记忆支持 project/global 双作用域：project 级只在当前项目可见，global 级全局可见
- 每轮对话前从长期记忆检索相关事实注入 system prompt（只检索长期，不检索短期）
- 两道压缩不要混淆：ConversationMemory 压缩（淘汰旧条目）vs ConversationHistoryCompactor（压缩 conversationHistory 防 window 超限）

### 工具系统

- ToolRegistry 用 ConcurrentHashMap 管理工具，支持运行时动态注册
- 多个 tool_call 通过线程池并行执行（最多 4 路），结果按原序返回
- 单个工具直接同步执行，不开线程池
- PathGuard 强制路径限定在项目根内（防路径穿越）
- CommandGuard 做命令黑名单（sudo、rm -rf / 等）
- 拦截顺序：ToolRegistry → PathGuard/CommandGuard
- execute_command 60 秒超时，输出截断 8KB
- write_file 单次写入 5MB 上限

### LLM 客户端

- 模板方法模式：AbstractOpenAiCompatibleClient 封装 SSE 流式解析，子类只覆盖 URL/模型/Key
- DeepSeek 特殊处理：强制 HTTP/1.1、历史消息保留 reasoning_content
- Tool call 参数流式到达，用 ToolCallAccumulator 按 index 聚合
- Prompt Cache：保持 system prompt 前缀稳定，最大化缓存命中率

### RAG

- 三级切块：文件 → 类 → 方法（JavaParser AST 解析）
- 检索策略：grep_code 精确优先 → 向量 + TF-IDF 混合检索兜底
- RRF 融合排序（k=60），不依赖分数归一化
- 上下文扩展：命中 chunk 取前后各 1 块
- TF-IDF 用 jieba 分词
- 向量存 SQLite（JSON 序列化），全量扫描余弦相似度

### Plan-and-Execute

- 先让 LLM 拆解为 3-5 步计划，展示给用户后逐步执行
- 每一步内部复用 ReAct Agent
- 用户可 Enter 确认 / 取消

## 修改时的硬规则

### 1. 改行为 → 同步文档
`AGENTS.md` / `CLAUDE.md` / `PAI.md`

### 2. 改命令入口 → 联动
`Main.java` + 文档

### 3. 改工具集 → 联动
`ToolRegistry.java` + `Agent.java` system prompt + 文档

### 4. 改模型/接口 → 联动
对应 Client + `.env` + 文档

### 5. 改记忆 → 联动
`MemoryManager` + `LongTermMemory` + `TokenBudget` + 文档

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

## 给新线程的导航

1. 先看本文件 → 2. `CLAUDE.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java |
| Agent 循环 | Agent.java + AgentGuard.java |
| 规划模式 | PlanExecuteAgent.java |
| 工具调用 | ToolRegistry.java |
| 模型/API | llm/AbstractOpenAiCompatibleClient.java + DeepSeekClient.java |
| 记忆系统 | memory/MemoryManager.java |
| RAG 检索 | rag/CodeIndex.java + CodeChunker.java |

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。
