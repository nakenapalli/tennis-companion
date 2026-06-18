package com.tenniscompanion.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.util.UUID

/**
 * A player in the canonical store. `id` is a UUID generated at first insert (either by the
 * Sackmann historical loader or when a new player is created from the API Tennis feed).
 * `sackmannId` is the old namespaced BIGINT kept for traceability and for the Tier-3 LLM
 * candidate prompts (which need a stable integer identifier). Property names map to snake_case
 * via Spring Boot's default Hibernate naming strategy.
 */
@Entity
@Table(name = "players")
class Player(
    @Id
    val id: UUID,
    val sackmannId: Long?,       // former namespaced player_id; null for API-Tennis-only players
    val sourcePlayerId: Long?,   // raw (pre-offset) Sackmann id; null for non-Sackmann players
    val firstName: String?,
    val lastName: String?,
    val hand: String?,
    val birthDate: LocalDate?,
    val countryCode: String?,
    val heightCm: Int?,
    val tour: String,
)
