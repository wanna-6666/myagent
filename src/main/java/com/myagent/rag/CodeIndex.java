package com.myagent.rag;

import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 代码检索引擎 - 三级切块 + 混合检索 + RRF 融合 + 上下文扩展
 */
public class CodeIndex {

    private final String projectPath;
    private final String embeddingApiUrl;
    private final String embeddingApiKey;
    private final String embeddingModel;

    private VectorStore vectorStore;
    private final TfidfIndex tfidfIndex = new TfidfIndex();
    private final Map<String, CodeChunk> chunkById = new LinkedHashMap<>();
    private final List<CodeChunk> allChunks = new ArrayList<>();

    public CodeIndex(String projectPath, String embeddingApiUrl, String embeddingApiKey, String embeddingModel) {
        this.projectPath = projectPath;
        this.embeddingApiUrl = embeddingApiUrl;
        this.embeddingApiKey = embeddingApiKey;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 索引项目
     */
    public String indexProject(PrintStream out) {
        try {
            out.println("🔍 扫描项目文件...");
            CodeChunker chunker = new CodeChunker();
            List<CodeChunk> chunks = chunker.chunkProject(projectPath);
            out.println("📦 切块完成: " + chunks.size() + " 个代码块");

            allChunks.clear();
            chunkById.clear();
            allChunks.addAll(chunks);
            for (CodeChunk c : chunks) chunkById.put(c.getId(), c);

            // 构建 TF-IDF 索引
            out.println("📝 构建关键词索引...");
            tfidfIndex.build(chunks);

            // 构建向量索引
            if (embeddingApiUrl != null && embeddingApiKey != null) {
                out.println("🧠 生成向量嵌入...");
                String dbPath = projectPath + "/.myagent/vectors.db";
                vectorStore = new VectorStore(dbPath);
                EmbeddingClient embedder = new EmbeddingClient(embeddingApiUrl, embeddingApiKey, embeddingModel);

                int done = 0;
                for (CodeChunk chunk : chunks) {
                    try {
                        float[] vector = embedder.embed(chunk.getContent());
                        vectorStore.save(chunk, vector);
                        done++;
                        if (done % 50 == 0) {
                            out.println("  进度: " + done + "/" + chunks.size());
                        }
                    } catch (Exception e) {
                        // 单个 chunk 嵌入失败，跳过
                    }
                }
                out.println("✅ 索引完成: " + done + " 个向量, " + chunks.size() + " 个关键词条目");
            } else {
                out.println("⚠️ 未配置 Embedding API，仅启用关键词检索");
            }

            return "索引完成: " + chunks.size() + " 个代码块";
        } catch (Exception e) {
            return "索引失败: " + e.getMessage();
        }
    }

    /**
     * 混合检索：向量 + TF-IDF，RRF 融合排序 + 上下文扩展
     */
    public String hybridSearch(String query, int topK) {
        try {
            // 关键词通道
            List<TfidfIndex.SearchResult> keywordResults = tfidfIndex.search(query, 10);

            // 向量通道
            List<VectorStore.SearchResult> vectorResults = List.of();
            if (vectorStore != null && embeddingApiKey != null) {
                EmbeddingClient embedder = new EmbeddingClient(embeddingApiUrl, embeddingApiKey, embeddingModel);
                float[] queryVector = embedder.embed(query);
                vectorResults = vectorStore.search(queryVector, 10);
            }

            // RRF 融合
            List<CodeChunk> merged = rrfMerge(vectorResults, keywordResults, topK);

            // 上下文扩展
            List<CodeChunk> expanded = expandContext(merged);

            // 格式化输出
            return formatResults(query, expanded);
        } catch (Exception e) {
            return "检索失败: " + e.getMessage();
        }
    }

    private List<CodeChunk> rrfMerge(
            List<VectorStore.SearchResult> vectorResults,
            List<TfidfIndex.SearchResult> keywordResults,
            int topK) {

        Map<String, Double> scores = new HashMap<>();
        int k = 60;

        for (int i = 0; i < vectorResults.size(); i++) {
            String id = vectorResults.get(i).chunk().getId();
            scores.merge(id, 1.0 / (k + i + 1), Double::sum);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            String id = keywordResults.get(i).chunk().getId();
            scores.merge(id, 1.0 / (k + i + 1), Double::sum);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> chunkById.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<CodeChunk> expandContext(List<CodeChunk> chunks) {
        List<CodeChunk> expanded = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (CodeChunk chunk : chunks) {
            int idx = allChunks.indexOf(chunk);
            // 前一块
            if (idx > 0) {
                CodeChunk prev = allChunks.get(idx - 1);
                if (prev.getFile().equals(chunk.getFile()) && seen.add(prev.getId())) {
                    expanded.add(prev);
                }
            }
            // 当前块
            if (seen.add(chunk.getId())) {
                expanded.add(chunk);
            }
            // 后一块
            if (idx >= 0 && idx < allChunks.size() - 1) {
                CodeChunk next = allChunks.get(idx + 1);
                if (next.getFile().equals(chunk.getFile()) && seen.add(next.getId())) {
                    expanded.add(next);
                }
            }
        }
        return expanded;
    }

    private String formatResults(String query, List<CodeChunk> results) {
        if (results.isEmpty()) return "未找到相关代码: " + query;

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 个相关代码块:\n\n");

        for (int i = 0; i < results.size(); i++) {
            CodeChunk c = results.get(i);
            String name = c.getName() != null ? c.getName() : c.getFile().getFileName().toString();
            sb.append("--- ").append(i + 1).append(". ")
                    .append("[").append(c.getLevel()).append("] ")
                    .append(name)
                    .append(" (").append(c.getFile().getFileName()).append(":")
                    .append(c.getStartLine()).append("-").append(c.getEndLine()).append(") ---\n");

            String content = c.getContent();
            if (content.length() > 1000) {
                content = content.substring(0, 1000) + "\n...(截断)";
            }
            sb.append(content).append("\n\n");
        }
        return sb.toString().trim();
    }
}
