package com.myagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * SQLite 向量存储
 */
public class VectorStore implements AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Connection db;

    public record SearchResult(CodeChunk chunk, double score) {}

    public VectorStore(String dbPath) throws SQLException {
        File dbFile = new File(dbPath);
        dbFile.getParentFile().mkdirs();
        this.db = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTable();
    }

    private void initTable() throws SQLException {
        try (Statement stmt = db.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id TEXT PRIMARY KEY,
                    file TEXT,
                    name TEXT,
                    level TEXT,
                    content TEXT,
                    vector TEXT,
                    start_line INTEGER,
                    end_line INTEGER
                )
                """);
        }
    }

    public void save(CodeChunk chunk, float[] vector) throws Exception {
        String sql = "INSERT OR REPLACE INTO chunks (id, file, name, level, content, vector, start_line, end_line) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = db.prepareStatement(sql)) {
            stmt.setString(1, chunk.getId());
            stmt.setString(2, chunk.getFile().toString());
            stmt.setString(3, chunk.getName());
            stmt.setString(4, chunk.getLevel());
            stmt.setString(5, chunk.getContent());
            stmt.setString(6, vectorToJson(vector));
            stmt.setInt(7, chunk.getStartLine());
            stmt.setInt(8, chunk.getEndLine());
            stmt.execute();
        }
    }

    public List<SearchResult> search(float[] queryVector, int topK) throws Exception {
        List<ScoredRow> scored = new ArrayList<>();

        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, file, name, level, content, vector, start_line, end_line FROM chunks")) {
            while (rs.next()) {
                float[] vec = jsonToVector(rs.getString("vector"));
                double similarity = EmbeddingClient.cosineSimilarity(queryVector, vec);
                scored.add(new ScoredRow(
                        rs.getString("id"), rs.getString("file"), rs.getString("name"),
                        rs.getString("level"), rs.getString("content"),
                        rs.getInt("start_line"), rs.getInt("end_line"),
                        similarity));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredRow::similarity).reversed());

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            ScoredRow row = scored.get(i);
            CodeChunk chunk = new CodeChunk(row.id, java.nio.file.Path.of(row.file),
                    row.level, row.content, row.startLine, row.endLine);
            chunk.setName(row.name);
            results.add(new SearchResult(chunk, row.similarity));
        }
        return results;
    }

    public int count() throws SQLException {
        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public void close() {
        try { db.close(); } catch (Exception ignored) {}
    }

    private record ScoredRow(String id, String file, String name, String level, String content,
                             int startLine, int endLine, double similarity) {}

    private static String vectorToJson(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        return sb.append("]").toString();
    }

    private static float[] jsonToVector(String json) {
        json = json.trim();
        if (json.startsWith("[")) json = json.substring(1);
        if (json.endsWith("]")) json = json.substring(0, json.length() - 1);
        String[] parts = json.split(",");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vec[i] = Float.parseFloat(parts[i].trim());
        }
        return vec;
    }
}
