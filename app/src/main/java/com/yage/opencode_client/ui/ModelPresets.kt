package com.yage.opencode_client.ui

/**
 * Curated model presets for the model selector, matching iOS implementation.
 * Only these models are shown in the dropdown instead of the full API list.
 */
object ModelPresets {
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GLM-5.3", "zai-coding-plan", "glm-5.3"),
        AppState.ModelOption("GPT-5.6 Sol", "openai", "gpt-5.6-sol"),
        AppState.ModelOption("Gemini 3.7 Flash", "google", "gemini-3.7-flash"),
        AppState.ModelOption("DeepSeek Local", "ds4", "deepseek-v4-flash"),
        AppState.ModelOption("Ollama GLM 5.2", "ollama-cloud", "glm-5.2"),
        AppState.ModelOption("GPT-5.6 Terra Fast", "openai", "gpt-5.6-terra-fast"),
        AppState.ModelOption("GPT-5.6 Luna", "openai", "gpt-5.6-luna"),
        AppState.ModelOption("Grok 4.6", "xai", "grok-4.6"),
    )
}
