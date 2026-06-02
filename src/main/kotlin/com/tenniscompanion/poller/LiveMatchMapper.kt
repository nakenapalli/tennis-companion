package com.tenniscompanion.poller

import com.tenniscompanion.api.LiveMatchDto
import com.tenniscompanion.api.PlayerSideDto
import com.tenniscompanion.integration.NormalizedMatch
import com.tenniscompanion.integration.NormalizedPlayerRef
import com.tenniscompanion.reconcile.ReconciliationRequest
import com.tenniscompanion.reconcile.ReconciliationService
import org.springframework.stereotype.Component

/**
 * Turns a provider-normalized match into the served DTO, resolving each player to a canonical Sackmann
 * id via the reconciliation engine. Shared by the live and recent (completed-today) pollers so the
 * reconcile-and-map step lives in one place.
 */
@Component
class LiveMatchMapper(private val reconciliation: ReconciliationService) {

    fun toDto(source: String, m: NormalizedMatch): LiveMatchDto = LiveMatchDto(
        externalId = m.externalId,
        status = m.status,
        tournamentName = m.tournamentName,
        round = m.round,
        surface = m.surface,
        tour = m.tour,
        category = m.category,
        player1 = side(source, m.player1),
        player2 = side(source, m.player2),
        score = m.score,
        startTime = m.startTime,
    )

    private fun side(source: String, p: NormalizedPlayerRef): PlayerSideDto {
        val playerId = reconciliation.resolve(
            ReconciliationRequest(
                source = source,
                externalId = p.externalId,
                externalName = p.name,
                tour = p.tour,
                countryCode = p.countryCode,
                rankHint = p.rankHint,
            ),
        ).playerId
        return PlayerSideDto(name = p.name, playerId = playerId, country = p.countryCode, rank = p.rankHint)
    }
}
