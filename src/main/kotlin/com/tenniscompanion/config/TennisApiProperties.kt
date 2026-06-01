package com.tenniscompanion.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Upstream feed config (RapidAPI "TennisApi"). Bound from app.tennis-api.* (env-driven). */
@ConfigurationProperties(prefix = "app.tennis-api")
data class TennisApiProperties(
    val baseUrl: String = "https://tennisapi1.p.rapidapi.com",
    val key: String = "",
    val host: String = "tennisapi1.p.rapidapi.com",
)

/**
 * Polling config. Scheduled polling is OFF by default — the free tier is ~50 requests/day, so we
 * poll on demand via the admin trigger endpoints instead of burning quota on a cron.
 */
@ConfigurationProperties(prefix = "app.poll")
data class PollProperties(
    val enabled: Boolean = false,
    val rankingTopN: Int = 150,
)
