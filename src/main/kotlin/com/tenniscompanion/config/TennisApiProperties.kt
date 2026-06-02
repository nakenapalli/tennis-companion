package com.tenniscompanion.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Upstream feed config (api-tennis.com). Bound from app.tennis-api.* (env-driven). `key` is the APIkey. */
@ConfigurationProperties(prefix = "app.tennis-api")
data class TennisApiProperties(
    val baseUrl: String = "https://api.api-tennis.com/tennis/",
    val key: String = "",
)

/**
 * Polling config. ON by default now that the api-tennis quota is generous (Starter = 8,000 req/day);
 * a 60s live poll is ~1,440/day. `liveInterval` (ISO-8601 duration) is read directly by the live
 * poller's @Scheduled. Tests disable polling via `app.poll.enabled=false`.
 */
@ConfigurationProperties(prefix = "app.poll")
data class PollProperties(
    val enabled: Boolean = true,
    val rankingTopN: Int = 150,
    val liveInterval: String = "PT1M",
)
