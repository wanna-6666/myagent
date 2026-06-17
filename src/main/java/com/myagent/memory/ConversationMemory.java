package com.myagent.memory;

import java.util.*;

/**
 * 短期记忆 - 当前对话的消息管理
 *
 * 超出 token 预算时自动淘汰最旧的条目
 */
public class ConversationMemory {

    private final LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
    private int maxTokens;
    private int currentTokens;

    public ConversationMemory(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void store(MemoryEntry entry) {
        entries.put(entry.id(), entry);
        currentTokens += entry.tokenCount();
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public int getTokenCount() { return currentTokens; }
    public int size() { return entries.size(); }
    public int getMaxTokens() { return maxTokens; }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        while (currentTokens > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    public void clear() {
        entries.clear();
        currentTokens = 0;
    }

    public double getUsageRatio() {
        return maxTokens > 0 ? (double) currentTokens / maxTokens : 0;
    }

    private void evictOldest() {
        Iterator<Map.Entry<String, MemoryEntry>> it = entries.entrySet().iterator();
        if (it.hasNext()) {
            MemoryEntry oldest = it.next().getValue();
            it.remove();
            currentTokens -= oldest.tokenCount();
        }
    }
}
