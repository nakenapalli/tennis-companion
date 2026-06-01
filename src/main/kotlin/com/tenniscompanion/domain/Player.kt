package com.tenniscompanion.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * A player from the Sackmann historical data. `playerId` is the canonical (namespaced) id —
 * see the V2 migration. The kotlin-jpa (no-arg) + all-open Gradle plugins let this `class` with
 * read-only `val`s serve as a JPA entity without a hand-written no-arg constructor. Property names
 * map to snake_case columns via Spring Boot's default Hibernate naming strategy (no `@Column`s needed).
 */
@Entity
@Table(name = "players")
class Player(
    @Id
    val playerId: Long,
    val sourcePlayerId: Long,
    val firstName: String?,
    val lastName: String?,
    val hand: String?,
    val birthDate: LocalDate?,
    val countryCode: String?,
    val heightCm: Int?,
    val tour: String,
)
