package com.myagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨会话持久化，支持 project/global 双作用域
 */
public class LongTermMemory {

    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger(0);
    private final ObjectMapper mapper;
    private final File storageFile;

    public LongTermMemory() {
        this(new File(new File(System.getProperty("user.home"), ".myagent"), "long_term_memory.json"));
    }

    public LongTermMemory(File storageFile) {
        this.storageFile = storageFile;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        storageFile.getParentFile().mkdirs();
        loadFromDisk();
    }

    public void store(String fact, String scope, String currentProject) {
        // 去重
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.content().equals(fact));
        if (duplicate) return;

        Map<String, String> metadata = new HashMap<>();
        metadata.put("scope", scope);
        if ("project".equals(scope)) {
            metadata.put("project", currentProject);
        }

        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                metadata
        );
        entries.put(entry.id(), entry);
        tokenCounter.addAndGet(entry.tokenCount());
        saveToDisk();
    }

    public List<MemoryEntry> search(String query, int limit, String currentProject) {
        Set<String> queryTokens = tokenize(query.toLowerCase());

        return entries.values().stream()
                .filter(e -> isVisible(e, currentProject))
                .filter(e -> matches(e.content().toLowerCase(), queryTokens))
                .sorted(Comparator.comparing(MemoryEntry::timestamp).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.tokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    public String buildContextForQuery(String query, int maxTokens, String currentProject) {
        List<MemoryEntry> relevant = search(query, 10, currentProject);
        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## 相关长期记忆\n\n");
        int used = 0;
        for (MemoryEntry e : relevant) {
            if (used + e.tokenCount() > maxTokens) break;
            sb.append("- ").append(e.content()).append("\n");
            used += e.tokenCount();
        }
        return sb.toString();
    }

    public String getStatusSummary() {
        return String.format("长期记忆: %d条 / %d tokens", entries.size(), tokenCounter.get());
    }

    // ===== 作用域可见性 =====

    private boolean isVisible(MemoryEntry entry, String currentProject) {
        String scope = entry.metadata().getOrDefault("scope", "project");
        if ("global".equals(scope)) return true;
        String entryProject = entry.metadata().get("project");
        return currentProject != null && currentProject.equals(entryProject);
    }

    // ===== 关键词匹配 =====

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String word : text.split("[\\s,;.!?，；。！？、]+")) {
            if (word.length() >= 2) tokens.add(word);
        }
        return tokens;
    }

    private boolean matches(String content, Set<String> queryTokens) {
        for (String token : queryTokens) {
            if (content.contains(token)) return true;
        }
        return false;
    }

    // ===== 持久化 =====

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;
        try {
            List<Map<String, Object>> data = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> map : data) {
                try {
                    String id = (String) map.get("id");
                    String content = (String) map.get("content");
                    String type = (String) map.get("type");
                    Map<String, String> metadata = new HashMap<>();
                    Object metaObj = map.get("metadata");
                    if (metaObj instanceof Map) {
                        ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
                    }
                    String ts = (String) map.get("timestamp");
                    Instant timestamp = ts != null ? Instant.parse(ts) : Instant.now();
                    int tokens = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);

                    MemoryEntry entry = new MemoryEntry(id, content,
                            MemoryEntry.MemoryType.valueOf(type), metadata, timestamp, tokens);
                    entries.put(id, entry);
                    tokenCounter.addAndGet(tokens);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private void saveToDisk() {
        try {
            List<Map<String, Object>> data = entries.values().stream().map(e -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", e.id());
                map.put("content", e.content());
                map.put("type", e.type().name());
                map.put("metadata", e.metadata());
                map.put("timestamp", e.timestamp().toString());
                map.put("tokenCount", e.tokenCount());
                return map;
            }).collect(Collectors.toList());
            mapper.writeValue(storageFile, data);
        } catch (IOException ignored) {}
    }
}
