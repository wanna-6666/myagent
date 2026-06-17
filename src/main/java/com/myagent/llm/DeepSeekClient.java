package com.myagent.llm;

/**
 * DeepSeek 客户端实现
 */
public class DeepSeekClient extends AbstractOpenAiCompatibleClient {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, API_URL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, API_URL);
    }

    public DeepSeekClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank()
                ? toChatCompletionsUrl(apiUrl)
                : API_URL;
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
    public String getProviderName() { return "deepseek"; }

    @Override
    public int maxContextWindow() { return 128_000; }

    @Override
    public boolean supportsPromptCaching() { return true; }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() { return true; }

    private static String toChatCompletionsUrl(String baseUrl) {
        String trimmed = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        if (trimmed.endsWith("/chat/completions")) return trimmed;
        return trimmed + "/chat/completions";
    }
}
