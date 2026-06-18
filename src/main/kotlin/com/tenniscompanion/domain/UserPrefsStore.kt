package com.tenniscompanion.domain

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class FavoriteDto(val playerId: UUID, val firstName: String?, val lastName: String?, val tour: String?)

/** Home-screen config (JSONB) + favorites for a user. Plain JDBC. */
@Repository
class UserPrefsStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {

    private val defaultLayout: Map<String, Any?> = mapOf(
        "widgets" to listOf("recent-results", "live-matches", "rankings-atp", "favorites", "latest-digest"),
    )

    @Suppress("UNCHECKED_CAST")
    fun homeConfig(userId: Long): Map<String, Any?> {
        val json = jdbc.query(
            "SELECT layout::text FROM user_home_config WHERE user_id = ?",
            { rs, _ -> rs.getString(1) }, userId,
        ).firstOrNull()
        return json?.let { mapper.readValue(it, Map::class.java) as Map<String, Any?> } ?: defaultLayout
    }

    fun saveHomeConfig(userId: Long, layout: Map<String, Any?>) {
        jdbc.update(
            "INSERT INTO user_home_config(user_id, layout) VALUES (?, ?::jsonb) ON CONFLICT (user_id) DO UPDATE SET layout = EXCLUDED.layout",
            userId, mapper.writeValueAsString(layout),
        )
    }

    fun favorites(userId: Long): List<FavoriteDto> = jdbc.query(
        """
        SELECT f.player_id, p.first_name, p.last_name, p.tour
        FROM user_favorites f LEFT JOIN players p ON p.id = f.player_id
        WHERE f.user_id = ?
        ORDER BY p.last_name NULLS LAST
        """.trimIndent(),
        { rs, _ -> FavoriteDto(rs.getObject("player_id", UUID::class.java), rs.getString("first_name"), rs.getString("last_name"), rs.getString("tour")) },
        userId,
    )

    fun addFavorite(userId: Long, playerId: UUID) {
        jdbc.update("INSERT INTO user_favorites(user_id, player_id) VALUES (?, ?::uuid) ON CONFLICT DO NOTHING", userId, playerId.toString())
    }

    fun removeFavorite(userId: Long, playerId: UUID) {
        jdbc.update("DELETE FROM user_favorites WHERE user_id = ? AND player_id = ?::uuid", userId, playerId.toString())
    }
}
