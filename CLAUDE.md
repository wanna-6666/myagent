# CLAUDE.md

## 项目概述

My Agent — 基于 ReAct 的终端 AI 编程助手（精简版），对标 Claude Code 基础功能。
Java 17 + Maven，约 2600 行核心代码，24 个源文件。

## 编码规范

- 不过度抽象，保持代码可读性
- 新功能优先复用已有模块（ToolRegistry / MemoryManager），不创建孤立能力
- 中文注释和日志输出
- record 优先于 class（数据载体场景）
- 工具执行结果统一返回 String，错误信息包含在返回值中而非抛异常

## 关键约定

- 改工具集 → 联动 `ToolRegistry.java` + `Agent.java` 的 system prompt
- 改模型 → 联动 `AbstractOpenAiCompatibleClient` + `DeepSeekClient` + `.env`
- 改记忆 → 联动 `MemoryManager` + `LongTermMemory` + `TokenBudget`
- 不提交 `.env`、真实 API Key、`target/` 产物

## 运行

```bash
mvn clean package -DskipTests
java -jar target/my-agent-1.0-SNAPSHOT.jar
```
