package com.myagent.llm;

import java.util.Properties;

/**
 * LLM 客户端工厂 - 根据 provider 创建对应客户端，支持运行时热切换
 */
public class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * 根据 provider 名称创建客户端
     */
    public static LlmClient create(String provider, Properties env) {
        if (provider == null || provider.isBlank()) return null;

        String normalized = provider.trim().toLowerCase();
        String apiKey = env.getProperty(normalized.toUpperCase() + "_API_KEY", "");
        if (apiKey.isBlank()) return null;

        String model = env.getProperty(normalized.toUpperCase() + "_MODEL", "");
        String baseUrl = env.getProperty(normalized.toUpperCase() + "_BASE_URL", "");

        return switch (normalized) {
            case "deepseek" -> new DeepSeekClient(apiKey,
                    model.isBlank() ? "deepseek-chat" : model,
                    baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl);

            case "glm" -> new GenericOpenAiClient(apiKey,
                    model.isBlank() ? "glm-4-flash" : model,
                    baseUrl.isBlank() ? "https://open.bigmodel.cn/api/paas/v4/chat/completions" : baseUrl,
                    "glm");

            case "kimi" -> new GenericOpenAiClient(apiKey,
                    model.isBlank() ? "moonshot-v1-auto" : model,
                    baseUrl.isBlank() ? "https://api.moonshot.cn/v1/chat/completions" : baseUrl,
                    "kimi");

            case "qwen" -> new GenericOpenAiClient(apiKey,
                    model.isBlank() ? "qwen-plus" : model,
                    baseUrl.isBlank() ? "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" : baseUrl,
                    "qwen");

            case "custom" -> new GenericOpenAiClient(apiKey,
                    model.isBlank() ? "default" : model,
                    baseUrl.isBlank() ? "" : baseUrl,
                    "custom");

            default -> null;
        };
    }

    /**
     * 从配置中创建默认客户端（按优先级尝试）
     */
    public static LlmClient createDefault(Properties env) {
        // 优先用 DEFAULT_PROVIDER 配置
        String defaultProvider = env.getProperty("DEFAULT_PROVIDER", "");
        if (!defaultProvider.isBlank()) {
            LlmClient client = create(defaultProvider, env);
            if (client != null) return client;
        }
        // 按优先级尝试
        for (String provider : new String[]{"deepseek", "glm", "kimi", "qwen", "custom"}) {
            LlmClient client = create(provider, env);
            if (client != null) return client;
        }
        return null;
    }

    /**
     * 获取所有已配置的 provider 列表
     */
    public static String listConfigured(Properties env) {
        StringBuilder sb = new StringBuilder();
        String[] providers = {"deepseek", "glm", "kimi", "qwen", "custom"};
        for (String p : providers) {
            String key = env.getProperty(p.toUpperCase() + "_API_KEY", "");
            String model = env.getProperty(p.toUpperCase() + "_MODEL", "(默认)");
            if (!key.isBlank()) {
                sb.append("  ✅ ").append(p).append(" - ").append(model.isBlank() ? "(默认)" : model).append("\n");
            } else {
                sb.append("  ⬜ ").append(p).append(" - 未配置\n");
            }
        }
        return sb.toString().trim();
    }
}
