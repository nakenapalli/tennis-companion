package com.tenniscompanion.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PlayerRepository : JpaRepository<Player, Long>

interface MatchRepository : JpaRepository<Match, Long> {

    /** Recent matches involving a player (as winner OR loser); page/sort supplied by the caller. */
    fun findByWinnerIdOrLoserId(winnerId: Long, loserId: Long, pageable: Pageable): List<Match>

    /** All matches between two players, most recent first. */
    @Query(
        """
        SELECT m FROM Match m
        WHERE (m.winnerId = :a AND m.loserId = :b)
           OR (m.winnerId = :b AND m.loserId = :a)
        ORDER BY m.tourneyDate DESC
        """,
    )
    fun findHeadToHead(@Param("a") a: Long, @Param("b") b: Long): List<Match>
}
