package com.myagent.agent;

import com.myagent.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Agent 循环保护 - 防止死循环和资源失控
 *
 * 三道保险阀（先到先触发）：
 * 1. 停滞检测：连续 N 轮相同工具调用 → 死循环
 * 2. Token 预算：累计 token 超限
 * 3. 硬轮数兜底：超过最大轮数
 */
public class AgentGuard {

    public enum StopReason {
        CONTINUE,
        TOKEN_EXCEEDED,
        STAGNATION,
        MAX_ITERATIONS
    }

    private static final int DEFAULT_STAGNATION_WINDOW = 3;
    private static final int DEFAULT_MAX_ITERATIONS = 30;

    private final int maxIterations;
    private final int stagnationWindow;
    private final int tokenBudget; // 0 = 不限

    private int iteration;
    private int totalTokens;
    private final Deque<String> toolSignatures = new ArrayDeque<>();
    private boolean stagnant;

    public AgentGuard() {
        this(DEFAULT_MAX_ITERATIONS, DEFAULT_STAGNATION_WINDOW, 0);
    }

    public AgentGuard(int maxIterations, int stagnationWindow, int tokenBudget) {
        this.maxIterations = maxIterations;
        this.stagnationWindow = stagnationWindow;
        this.tokenBudget = tokenBudget;
    }

    public StopReason shouldStop() {
        if (stagnant) return StopReason.STAGNATION;
        if (iteration >= maxIterations) return StopReason.MAX_ITERATIONS;
        if (tokenBudget > 0 && totalTokens >= tokenBudget) return StopReason.TOKEN_EXCEEDED;
        return StopReason.CONTINUE;
    }

    public void nextIteration() {
        iteration++;
    }

    public void addTokens(int tokens) {
        totalTokens += Math.max(0, tokens);
    }

    public void recordToolCalls(List<LlmClient.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            toolSignatures.clear();
            return;
        }
        String sig = calls.stream()
                .map(tc -> tc.function().name() + "|" + tc.function().arguments())
                .collect(Collectors.joining(";"));
        toolSignatures.addLast(sig);
        while (toolSignatures.size() > stagnationWindow) {
            toolSignatures.removeFirst();
        }
        if (toolSignatures.size() == stagnationWindow) {
            String first = toolSignatures.peekFirst();
            stagnant = toolSignatures.stream().allMatch(s -> s.equals(first));
        }
    }

    public String describe(StopReason reason) {
        return switch (reason) {
            case CONTINUE -> "";
            case TOKEN_EXCEEDED -> String.format(Locale.ROOT,
                    "Token 预算已用尽（%d / %d），任务被强制收尾", totalTokens, tokenBudget);
            case STAGNATION -> String.format(Locale.ROOT,
                    "检测到连续 %d 轮重复的工具调用，疑似死循环，已强制收尾", stagnationWindow);
            case MAX_ITERATIONS -> String.format(Locale.ROOT,
                    "达到最大轮数上限（%d），已强制收尾", maxIterations);
        };
    }

    public int iteration() { return iteration; }
    public int totalTokens() { return totalTokens; }
}
