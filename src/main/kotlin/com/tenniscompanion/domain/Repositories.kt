package com.tenniscompanion.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PlayerRepository : JpaRepository<Player, UUID>

interface MatchRepository : JpaRepository<Match, Long> {

    /** Recent finished matches involving a player (as winner OR loser); page/sort supplied by caller. */
    fun findByWinnerIdOrLoserId(winnerId: UUID, loserId: UUID, pageable: Pageable): List<Match>

    /** All matches between two players, most recent first. Covers both Sackmann and API Tennis rows. */
    @Query(
        """
        SELECT m FROM Match m
        WHERE (m.winnerId = :a AND m.loserId = :b)
           OR (m.winnerId = :b AND m.loserId = :a)
        ORDER BY m.startTime DESC, m.tourneyDate DESC
        """,
    )
    fun findHeadToHead(@Param("a") a: UUID, @Param("b") b: UUID): List<Match>
}
