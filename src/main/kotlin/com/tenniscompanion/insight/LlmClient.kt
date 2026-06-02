package com.tenniscompanion.insight

import com.fasterxml.jackson.annotation.JsonProperty
import com.tenniscompanion.config.LlmProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * One narrow seam to the LLM, so the provider stays swappable and the jobs are unit-testable with a
 * stub. Both call sites (weekly digest, Tier-3 reconciliation) are stateless single-shot completions:
 * a system + user message in, the model's text out. Grounding/validation lives in the callers.
 */
interface LlmClient {
    fun complete(system: String, user: String, model: String, maxTokens: Int): String
}

@Component
class AnthropicLlmClient(
    @Qualifier("anthropicRestClient") private val client: RestClient,
    private val props: LlmProperties,
) : LlmClient {

    override fun complete(system: String, user: String, model: String, maxTokens: Int): String {
        check(props.effectiveKey.isNotBlank()) { "LLM api key not configured (set LLM_API_KEY or ANTHROPIC_API_KEY)" }
        val req = AnthropicRequest(
            model = model,
            maxTokens = maxTokens,
            // system as a cacheable block (stable prefix); short prompts sit below the cache minimum
            // so this is a no-op today, but it's the correct placement if the prompts grow.
            system = listOf(AnthropicSystemBlock(text = system)),
            messages = listOf(AnthropicMessage(role = "user", content = user)),
        )
        val resp = client.post().uri("/v1/messages").body(req).retrieve().body(AnthropicResponse::class.java)
        return resp?.content?.firstOrNull { it.type == "text" }?.text?.trim()
            ?: error("Anthropic returned no text content")
    }
}

// --- Messages API wire shapes (only the fields we use; snake_case via @JsonProperty) ---

data class AnthropicRequest(
    val model: String,
    @JsonProperty("max_tokens") val maxTokens: Int,
    val system: List<AnthropicSystemBlock>,
    val messages: List<AnthropicMessage>,
)

data class AnthropicSystemBlock(
    val type: String = "text",
    val text: String,
    @JsonProperty("cache_control") val cacheControl: CacheControl = CacheControl(),
)

data class CacheControl(val type: String = "ephemeral")

data class AnthropicMessage(val role: String, val content: String)

data class AnthropicResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
    @JsonProperty("stop_reason") val stopReason: String? = null,
)

data class AnthropicContentBlock(val type: String? = null, val text: String? = null)
