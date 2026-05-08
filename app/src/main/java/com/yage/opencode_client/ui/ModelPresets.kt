package com.yage.opencode_client.ui

/**
 * Curated model presets for the model selector, matching iOS implementation.
 * Only these models are shown in the dropdown instead of the full API list.
 */
object ModelPresets {
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GLM-5.1", "zai-coding-plan", "glm-5.1"),
        AppState.ModelOption("GPT-5.5", "openai", "gpt-5.5"),
        AppState.ModelOption("DeepSeek V4 Flash", "deepseek", "deepseek-v4-flash"),
        AppState.ModelOption("DeepSeek V4 Pro", "deepseek", "deepseek-v4-pro"),
    )
}
