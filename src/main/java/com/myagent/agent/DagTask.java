package com.myagent.agent;

import java.util.List;

/**
 * DAG 任务节点 - 包含依赖关系
 */
public record DagTask(
        String id,              // 任务 ID（如 "t1", "t2"）
        String description,     // 任务描述
        List<String> dependsOn  // 依赖的任务 ID 列表（空 = 无依赖，可立即执行）
) {}
