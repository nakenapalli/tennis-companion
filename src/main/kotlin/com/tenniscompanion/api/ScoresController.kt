package com.tenniscompanion.api

import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.poller.LiveDataStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Reads from the cache/DB only — never calls upstream (design §6.6). */
@RestController
@RequestMapping("/api/scores")
class ScoresController(
    private val store: LiveDataStore,
    private val adapter: TennisApiAdapter,
) {
    @GetMapping("/live")
    fun live(): List<LiveMatchDto> = store.liveMatches(adapter.source)

    /** Today's completed matches — used by the UI when nothing is live. */
    @GetMapping("/recent")
    fun recent(): List<LiveMatchDto> = store.recentMatches(adapter.source)
}
