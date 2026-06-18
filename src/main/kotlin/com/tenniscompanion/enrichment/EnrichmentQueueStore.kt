package com.tenniscompanion.enrichment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class EnrichmentTask(
    val entityType: String,
    val entityId: String,
    val fieldsNeeded: List<String>,
    /** Pre-update attempt count — DB value will be this + 1 after dequeue. */
    val attempts: Int,
)

@Repository
class EnrichmentQueueStore(private val jdbc: JdbcTemplate) {

    /** Idempotent — a second enqueue for the same entity is silently ignored. */
    fun enqueue(entityType: String, entityId: String, fieldsNeeded: List<String>) {
        val arrayParam = "{${fieldsNeeded.joinToString(",")}}"
        jdbc.update(
            """
            INSERT INTO enrichment_queue(entity_type, entity_id, fields_needed)
            VALUES (?,?,?::text[])
            ON CONFLICT (entity_type, entity_id) DO NOTHING
            """.trimIndent(),
            entityType, entityId, arrayParam,
        )
    }

    /**
     * Pulls up to [limit] pending tasks and marks them in_progress. Not atomically locked — acceptable
     * for the single-threaded scheduled job. Returns tasks with pre-update attempt counts.
     */
    fun dequeueForProcessing(limit: Int): List<EnrichmentTask> {
        val tasks = jdbc.query(
            "SELECT entity_type, entity_id, fields_needed, attempts FROM enrichment_queue WHERE status = 'pending' ORDER BY created_at LIMIT ?",
            { rs, _ ->
                EnrichmentTask(
                    entityType = rs.getString("entity_type"),
                    entityId = rs.getString("entity_id"),
                    fieldsNeeded = (rs.getArray("fields_needed")?.array as? Array<*>)
                        ?.filterIsInstance<String>() ?: emptyList(),
                    attempts = rs.getInt("attempts"),
                )
            },
            limit,
        )
        for (t in tasks) {
            jdbc.update(
                "UPDATE enrichment_queue SET status = 'in_progress', attempts = attempts + 1, last_attempted_at = now() WHERE entity_type = ? AND entity_id = ?",
                t.entityType, t.entityId,
            )
        }
        return tasks
    }

    fun markDone(entityType: String, entityId: String) {
        jdbc.update(
            "UPDATE enrichment_queue SET status = 'done', resolved_at = now() WHERE entity_type = ? AND entity_id = ?",
            entityType, entityId,
        )
    }

    fun markExhausted(entityType: String, entityId: String) {
        jdbc.update(
            "UPDATE enrichment_queue SET status = 'exhausted' WHERE entity_type = ? AND entity_id = ?",
            entityType, entityId,
        )
    }

    fun markPending(entityType: String, entityId: String) {
        jdbc.update(
            "UPDATE enrichment_queue SET status = 'pending' WHERE entity_type = ? AND entity_id = ?",
            entityType, entityId,
        )
    }
}
