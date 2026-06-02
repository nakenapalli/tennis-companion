package com.tenniscompanion.api

import com.tenniscompanion.insight.DigestStore
import com.tenniscompanion.insight.StoredInsight
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/** Public-facing insight (no source_data — that's admin-only traceability). */
data class InsightView(
    val id: Long,
    val type: String,
    val title: String,
    val bodyMarkdown: String,
    val generatedAt: Instant,
    val publishedAt: Instant?,
)

private fun StoredInsight.toView() = InsightView(id, type, title, bodyMarkdown, generatedAt, publishedAt)

/** Serves only PUBLISHED insights (drafts are admin-only). */
@RestController
@RequestMapping("/api/insights")
class InsightController(private val store: DigestStore) {

    @GetMapping("/latest")
    fun latest(@RequestParam(defaultValue = "weekly_digest") type: String): ResponseEntity<InsightView> =
        store.latestPublished(type)?.let { ResponseEntity.ok(it.toView()) } ?: ResponseEntity.noContent().build()

    @GetMapping("/{id}")
    fun byId(@PathVariable id: Long): InsightView =
        store.byId(id)?.takeIf { it.status == "PUBLISHED" }?.toView()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No published insight $id")
}
