package com.tenniscompanion.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig {

    /**
     * RestClient for the adapter. api-tennis.com authenticates via an `APIkey` query param (added
     * per-call in the adapter), so no auth headers here — just the base URL. Accept-Encoding: identity
     * keeps large responses uncompressed (the JDK HTTP client doesn't auto-decompress → broke parsing).
     * `@Primary` so the adapter's unqualified `RestClient` injection still resolves now that there's a
     * second RestClient bean (the Anthropic one, qualified by name).
     */
    @Bean
    @Primary
    fun tennisApiRestClient(props: TennisApiProperties): RestClient =
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("Accept-Encoding", "identity")
            .build()

    /** RestClient for the Anthropic Messages API (LLM digest / reconciliation). Key + version headers. */
    @Bean
    fun anthropicRestClient(props: LlmProperties): RestClient =
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("x-api-key", props.effectiveKey)
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader("content-type", "application/json")
            .build()
}
