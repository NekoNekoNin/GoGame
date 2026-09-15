package com.november.gogame.client.ai;

/**
 * AI 服务提供方预设：全部走 OpenAI 兼容的 {@code /chat/completions}（实施计划 Phase 4）。
 *
 * <p>{@link #CUSTOM} 优先——玩家填自己的地址/模型；其余四家是一键预设。五家各记一套
 * Key/地址/模型（{@link AiConfig} 的按服务方分槽）：{@link AiConfig#switchProvider} 切过去时
 * 恢复该家上次记住的值，从没配过才回这里的预设默认。默认模型挑各家「快而便宜」
 * 档：围棋一手只要回几十字符，回得快比回得深更要紧。
 */
public enum AiProvider {

    CUSTOM("gogame.ai.provider.custom", "", ""),
    GPT("gogame.ai.provider.gpt", "https://api.openai.com/v1", "gpt-4o-mini"),
    QWEN("gogame.ai.provider.qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    DEEPSEEK("gogame.ai.provider.deepseek", "https://api.deepseek.com", "deepseek-chat"),
    ZHIPU("gogame.ai.provider.zhipu", "https://open.bigmodel.cn/api/paas/v4", "glm-4-air");

    private final String langKey;
    private final String defaultBaseUrl;
    private final String defaultModel;

    AiProvider(String langKey, String defaultBaseUrl, String defaultModel) {
        this.langKey = langKey;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    /** 显示名翻译键（渲染线程翻译） */
    public String langKey() { return langKey; }

    /** 预设默认地址；CUSTOM 为空串（必须玩家自填） */
    public String defaultBaseUrl() { return defaultBaseUrl; }

    /** 预设默认模型；CUSTOM 为空串 */
    public String defaultModel() { return defaultModel; }
}
