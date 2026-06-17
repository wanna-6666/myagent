# PAI.md

## Commands

- 构建：`mvn clean package -DskipTests`
- 启动：`java -jar target/my-agent-1.0-SNAPSHOT.jar`
- 斜杠命令：`/plan` `/clear` `/context` `/save` `/memory` `/index` `/search` `/exit`

## What This Is

My Agent 是基于 ReAct 的终端 AI 编程助手（精简版），对标 Claude Code 基础功能。
支持 ReAct 和 Plan-and-Execute 两种执行模式，5 个内置工具，RAG 代码检索，长期记忆系统。

## Architecture

- 两条执行路径共享 `ToolRegistry` / `MemoryManager`，不要为某个模式创建孤立能力
- 精确代码定位优先 `grep_code` / `read_file`，RAG 向量检索只做语义辅助
- system prompt 在 `Agent.java` 中硬编码，长期记忆通过 `MemoryManager.buildContextForQuery()` 动态注入

## Things That Will Bite You

- 改工具集要联动 `ToolRegistry.java` 和 `Agent.java` 的 system prompt
- 改命令入口要联动 `Main.java` 和文档
- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取临时事实
- 两道压缩不要混淆：ConversationMemory 淘汰旧条目 vs ConversationHistoryCompactor 压缩 conversationHistory
- PathGuard 强制路径在项目根内，CommandGuard 是辅助黑名单
- 并行工具最多 4 路并发，结果保持原始顺序

## Don't

- 不提交 `.env`、真实 API Key、`target/` 产物
- 不在工具执行器（ToolExecutor）中抛出受检异常，错误信息包在返回字符串中
- 不让 Agent 自动提取事实存入长期记忆，只有用户显式操作才保存
