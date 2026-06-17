package com.myagent.llm;

/**
 * 通用 OpenAI 兼容客户端 - 可对接任意兼容 OpenAI 协议的 API
 *
 * 用于 GLM / Kimi / Qwen / 自定义端点等
 */
public class GenericOpenAiClient extends AbstractOpenAiCompatibleClient {

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final String providerName;

    public GenericOpenAiClient(String apiKey, String model, String apiUrl, String providerName) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
        this.providerName = providerName;
    }

    @Override
    protected String getApiUrl() { return apiUrl; }

    @Override
    protected String getModel() { return model; }

    @Override
    protected String getApiKey() { return apiKey; }

    @Override
    public String getModelName() { return model; }

    @Override
    public String getProviderName() { return providerName; }

    @Override
    public int maxContextWindow() {
        return switch (providerName) {
            case "glm" -> 128_000;
            case "kimi" -> 128_000;
            case "qwen" -> 131_072;
            default -> 128_000;
        };
    }

    @Override
    public boolean supportsPromptCaching() {
        return "glm".equals(providerName);
    }
}
