package com.tenniscompanion.api

import java.time.LocalDate

/** Serving shape for a tournament. `draw` is reserved (draw/seed sync is deferred — quota-heavy). */
data class TournamentView(
    val id: Long,
    val externalId: String,
    val name: String,
    val level: String?,
    val surface: String?,
    val location: String?,
    val tour: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val draw: Any? = null,
)
