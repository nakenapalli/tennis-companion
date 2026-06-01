package com.tenniscompanion.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig {

    /** A RestClient pre-loaded with the RapidAPI auth headers + base URL for the adapter. */
    @Bean
    fun tennisApiRestClient(props: TennisApiProperties): RestClient =
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("X-RapidAPI-Key", props.key)
            .defaultHeader("X-RapidAPI-Host", props.host)
            // The JDK HTTP client doesn't auto-decompress; ask for uncompressed JSON so large
            // responses (e.g. rankings) don't arrive gzipped and break parsing.
            .defaultHeader("Accept-Encoding", "identity")
            .build()
}
