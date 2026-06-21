package com.tenniscompanion.config

import com.tenniscompanion.integration.UpstreamRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

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
    fun tennisApiRestClient(props: TennisApiProperties, rateLimiter: UpstreamRateLimiter): RestClient =
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("Accept-Encoding", "identity")
            // Every upstream call passes the client-side rate limiter first (blocks briefly for a permit).
            .requestInterceptor { request, body, execution ->
                rateLimiter.acquire()
                execution.execute(request, body)
            }
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

    /** RestClient for fetching RSS news feeds. No base URL (feeds are absolute); a UA header (some feeds
     *  reject blank agents) and tight timeouts so one slow feed can't stall digest generation. */
    @Bean
    fun newsRestClient(): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(8))
        }
        return RestClient.builder()
            .requestFactory(factory)
            .defaultHeader("User-Agent", "TennisCompanion/1.0 (portfolio)")
            .build()
    }
}
