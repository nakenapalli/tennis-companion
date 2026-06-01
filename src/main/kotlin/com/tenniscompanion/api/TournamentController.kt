package com.tenniscompanion.api

import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.poller.TournamentStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/tournaments")
class TournamentController(
    private val store: TournamentStore,
    private val adapter: TennisApiAdapter,
) {
    @GetMapping("/current")
    fun current(): List<TournamentView> = store.current(adapter.source)

    @GetMapping("/{id}")
    fun byId(@PathVariable id: Long): TournamentView =
        store.byId(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No tournament with id $id")
}
