package com.tenniscompanion.api

import com.tenniscompanion.poller.LiveDataStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rankings")
class RankingsController(private val store: LiveDataStore) {

    @GetMapping
    fun rankings(
        @RequestParam(defaultValue = "ATP") tour: String,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<RankingRowDto> = store.rankings(tour.uppercase(), limit.coerceIn(1, 500))
}
