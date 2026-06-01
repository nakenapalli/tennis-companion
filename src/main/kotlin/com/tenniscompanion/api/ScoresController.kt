package com.tenniscompanion.api

import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.poller.LiveDataStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Reads from the cache/DB only — never calls upstream (design §6.6). */
@RestController
@RequestMapping("/api/scores")
class ScoresController(
    private val store: LiveDataStore,
    private val adapter: TennisApiAdapter,
) {
    @GetMapping("/live")
    fun live(): List<LiveMatchDto> = store.liveMatches(adapter.source)

    @GetMapping("/recent")
    fun recent(@RequestParam(defaultValue = "1") days: Long): List<LiveMatchDto> =
        store.recentMatches(adapter.source, Instant.now().minus(days.coerceIn(1, 30), ChronoUnit.DAYS))
}
