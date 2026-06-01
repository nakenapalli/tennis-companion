package com.tenniscompanion.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * A historical match. Rows are written by the bulk loader (raw JDBC), so this entity is used only
 * for reads. `winnerId` / `loserId` are soft references (no FK) to `players`; the names are stored
 * denormalized so results render even when a player isn't in the player file.
 */
@Entity
@Table(name = "matches")
class Match(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val tour: String,
    val tourneyId: String?,
    val tourneyName: String?,
    val surface: String?,
    val tourneyLevel: String?,
    val tourneyDate: LocalDate?,
    val matchNum: Int?,
    val round: String?,
    val bestOf: Int?,
    val winnerId: Long?,
    val loserId: Long?,
    val winnerName: String?,
    val loserName: String?,
    val score: String?,
)
