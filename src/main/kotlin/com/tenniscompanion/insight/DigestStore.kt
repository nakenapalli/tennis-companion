package com.tenniscompanion.insight

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/** A stored insight (full row, incl. source_data for admin traceability). */
data class StoredInsight(
    val id: Long,
    val type: String,
    val title: String,
    val bodyMarkdown: String,
    val model: String?,
    val status: String,
    val generatedAt: Instant,
    val publishedAt: Instant?,
)

@Repository
class DigestStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {

    fun saveDraft(type: String, title: String, bodyMarkdown: String, sourceData: Map<String, Any?>, model: String): Long =
        jdbc.queryForObject(
            """
            INSERT INTO generated_insights(type, title, body_markdown, source_data, model, status)
            VALUES (?,?,?,?::jsonb,?, 'DRAFT') RETURNING id
            """.trimIndent(),
            Long::class.java,
            type, title, bodyMarkdown, mapper.writeValueAsString(sourceData), model,
        )!!

    fun latestPublished(type: String): StoredInsight? =
        jdbc.query(
            "SELECT * FROM generated_insights WHERE type = ? AND status = 'PUBLISHED' ORDER BY published_at DESC LIMIT 1",
            ROW_MAPPER, type,
        ).firstOrNull()

    fun byId(id: Long): StoredInsight? =
        jdbc.query("SELECT * FROM generated_insights WHERE id = ?", ROW_MAPPER, id).firstOrNull()

    fun listByStatus(status: String): List<StoredInsight> =
        jdbc.query("SELECT * FROM generated_insights WHERE status = ? ORDER BY generated_at DESC", ROW_MAPPER, status)

    /** Returns true if a DRAFT row was published (false if it didn't exist or wasn't a draft). */
    fun publish(id: Long): Boolean =
        jdbc.update(
            "UPDATE generated_insights SET status = 'PUBLISHED', published_at = now() WHERE id = ? AND status = 'DRAFT'",
            id,
        ) > 0

    companion object {
        private val ROW_MAPPER = RowMapper { rs, _ ->
            StoredInsight(
                id = rs.getLong("id"),
                type = rs.getString("type"),
                title = rs.getString("title"),
                bodyMarkdown = rs.getString("body_markdown"),
                model = rs.getString("model"),
                status = rs.getString("status"),
                generatedAt = rs.getTimestamp("generated_at").toInstant(),
                publishedAt = rs.getTimestamp("published_at")?.toInstant(),
            )
        }
    }
}
