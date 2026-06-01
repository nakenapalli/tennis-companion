package com.tenniscompanion.loader

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Config for the one-time historical load. Bound from `app.historical-load.*` (constructor binding;
 * relaxed names map `atp-dir` -> `atpDir`). Defaults keep the load off and point at the cloned repos.
 */
@ConfigurationProperties(prefix = "app.historical-load")
data class HistoricalLoadProperties(
    val enabled: Boolean = false,
    val atpDir: String = "data/tennis_atp",
    val wtaDir: String = "data/tennis_wta",
    /** Match seasons to load (one CSV per year per tour). */
    val seasons: List<Int> = (2021..2026).toList(),
    /** Ranking file suffixes to load, e.g. "current" -> atp_rankings_current.csv. */
    val rankingFiles: List<String> = listOf("current"),
)
