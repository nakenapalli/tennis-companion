package com.tenniscompanion.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/players")
class PlayerController(private val service: PlayerService) {

    @GetMapping("/{playerId}")
    fun profile(@PathVariable playerId: Long): PlayerProfileDto =
        service.profile(playerId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No player with id $playerId")

    @GetMapping("/{playerId}/matches")
    fun matches(
        @PathVariable playerId: Long,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<MatchDto> = service.recentMatches(playerId, limit)

    @GetMapping("/{playerId}/h2h")
    fun headToHead(
        @PathVariable playerId: Long,
        @RequestParam opponentId: Long,
    ): H2hDto = service.headToHead(playerId, opponentId)
}
