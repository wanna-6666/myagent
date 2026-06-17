package com.myagent.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TF-IDF 倒排索引 - 关键词检索通道
 */
public class TfidfIndex {

    private final Map<String, Map<String, Integer>> invertedIndex = new HashMap<>();
    private final Map<String, Integer> docLengths = new HashMap<>();
    private final Map<String, CodeChunk> chunkMap = new HashMap<>();
    private int totalDocs;
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public record SearchResult(CodeChunk chunk, double score) {}

    public void build(List<CodeChunk> chunks) {
        invertedIndex.clear();
        docLengths.clear();
        chunkMap.clear();
        totalDocs = chunks.size();

        for (CodeChunk chunk : chunks) {
            chunkMap.put(chunk.getId(), chunk);
            List<String> terms = tokenize(chunk.getContent());
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
                double tf = (double) entry.getValue() / Math.max(1, docLengths.getOrDefault(entry.getKey(), 1));
                scores.merge(entry.getKey(), tf * idf, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .filter(e -> chunkMap.containsKey(e.getKey()))
                .map(e -> new SearchResult(chunkMap.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = segmenter.sentenceProcess(text);
        return tokens.stream()
                .map(w -> w.trim().toLowerCase())
                .filter(w -> w.length() >= 2)
                .collect(Collectors.toList());
    }
}
