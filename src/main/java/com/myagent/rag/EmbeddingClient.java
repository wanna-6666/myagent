package com.myagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;

/**
 * Embedding 客户端 - 调用远程 API 生成文本向量
 */
public class EmbeddingClient {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient HTTP = new OkHttpClient();

    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public EmbeddingClient(String apiUrl, String apiKey, String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model != null ? model : "text-embedding-3-small";
    }

    /**
     * 生成文本的向量表示
     */
    public float[] embed(String text) throws IOException {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "model", model,
                "input", text.length() > 8000 ? text.substring(0, 8000) : text
        ));

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body, MediaType.parse("application/json")))
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Embedding API 失败: " + response.code());
            }
            String responseBody = response.body().string();
            var root = mapper.readTree(responseBody);
            var embedding = root.path("data").get(0).path("embedding");

            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            return vector;
        }
    }

    /**
     * 余弦相似度
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
