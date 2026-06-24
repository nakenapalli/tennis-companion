package com.tenniscompanion.api

import com.tenniscompanion.poller.LiveDataStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Current ATP/WTA ranking snapshot, served from the Redis/Postgres cache (never upstream). */
@RestController
@RequestMapping("/api/rankings")
class RankingsController(private val store: LiveDataStore) {

    /** `tour` is ATP|WTA (case-insensitive, default ATP); `limit` is clamped to 1..500. */
    @GetMapping
    fun rankings(
        @RequestParam(defaultValue = "ATP") tour: String,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<RankingRowDto> = store.rankings(tour.uppercase(), limit.coerceIn(1, 500))
}
