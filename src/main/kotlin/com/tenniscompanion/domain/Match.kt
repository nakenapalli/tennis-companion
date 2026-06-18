package com.tenniscompanion.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A match in the canonical store. Covers both Sackmann historical rows (source='sackmann',
 * status='finished') and API Tennis live/recent rows (source='api-tennis'). The two views of
 * players — player1/player2 (by-position, always present for API Tennis rows) and winner/loser
 * (by-outcome, null until the match finishes) — are stored side-by-side so both the live scores
 * feed and the historical query paths can share the same table.
 *
 * Fields that are only ever written/read by LiveDataStore via raw JDBC (scoreDetail JSONB,
 * lastPolledAt, serve) are intentionally omitted here — Hibernate ignores extra DB columns
 * when ddl-auto=validate, and those fields don't participate in JPA queries.
 */
@Entity
@Table(name = "matches")
class Match(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    val source: String = "sackmann",
    val externalId: String? = null,

    val tourneyId: String? = null,
    val tourneyName: String? = null,
    val surface: String? = null,
    val tourneyLevel: String? = null,
    val tourneyDate: LocalDate? = null,
    val category: String? = null,
    val matchNum: Int? = null,
    val round: String? = null,
    val bestOf: Int? = null,
    val qualifying: Boolean = false,

    val status: String = "finished",
    val startTime: Instant? = null,
    val tour: String,

    val player1Id: UUID? = null,
    val player2Id: UUID? = null,
    val player1Name: String? = null,
    val player2Name: String? = null,

    val winnerId: UUID? = null,
    val loserId: UUID? = null,
    val winnerName: String? = null,
    val loserName: String? = null,

    val score: String? = null,
)
