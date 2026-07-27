package com.yage.opencode_client.ui

/**
 * Legacy model presets used only to migrate pre-dynamic-selector saved indexes.
 * The chat model selector is populated from the server provider list.
 */
object ModelPresets {
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GLM-5.2", "zai-coding-plan", "glm-5.2"),
        AppState.ModelOption("GPT-5.6 Sol", "openai", "gpt-5.6-sol"),
        AppState.ModelOption("Gemini 3.6 Flash", "google", "gemini-3.6-flash"),
        AppState.ModelOption("DeepSeek Local", "ds4", "deepseek-v4-flash"),
        AppState.ModelOption("DeepSeek V4 Pro", "deepseek", "deepseek-v4-pro"),
        AppState.ModelOption("Ollama GLM 5.2", "ollama-cloud", "glm-5.2"),
        AppState.ModelOption("GPT-5.6 Sol Fast", "openai", "gpt-5.6-sol-fast"),
        AppState.ModelOption("GPT-5.6 Terra Fast", "openai", "gpt-5.6-terra-fast"),
    )
}
