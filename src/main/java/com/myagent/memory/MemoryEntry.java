package com.myagent.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆条目
 */
public record MemoryEntry(
        String id,
        String content,
        MemoryType type,
        Map<String, String> metadata,
        Instant timestamp,
        int tokenCount
) {
    public enum MemoryType {
        CONVERSATION, FACT, TOOL_RESULT, SUMMARY
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata) {
        this(id, content, type, metadata, Instant.now(), estimateTokens(content));
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, metadata, Instant.now(), tokenCount);
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 3);
    }
}
