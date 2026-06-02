package com.tenniscompanion.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * LLM config (Anthropic). Bound from app.llm.* (env-driven). `api-key` reuses the existing
 * ANTHROPIC_API_KEY when LLM_API_KEY isn't set. `model` is the digest model (Sonnet); `tier3Model`
 * (Haiku) is reserved for the Phase 6b reconciliation classifier. The job no-ops when the key is blank.
 */
@ConfigurationProperties(prefix = "app.llm")
data class LlmProperties(
    val apiKey: String = "",          // LLM_API_KEY (override)
    val anthropicApiKey: String = "", // ANTHROPIC_API_KEY (fallback) — two plain placeholders avoid a
    val model: String = "claude-sonnet-4-6",
    val tier3Model: String = "claude-haiku-4-5",
    val baseUrl: String = "https://api.anthropic.com",
    val enabled: Boolean = true,
) {
    /** The key to actually use: LLM_API_KEY if set, else ANTHROPIC_API_KEY. (A nested-default
     *  placeholder `${LLM_API_KEY:${ANTHROPIC_API_KEY:}}` did not resolve the inner default.) */
    val effectiveKey: String get() = apiKey.ifBlank { anthropicApiKey }
}
