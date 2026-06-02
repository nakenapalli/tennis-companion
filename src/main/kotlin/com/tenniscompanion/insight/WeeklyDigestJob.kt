package com.tenniscompanion.insight

import com.tenniscompanion.config.LlmProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Builds the grounded fact sheet, asks the LLM to write the narrative around it, validates lightly,
 * and stores a DRAFT (design §9). Scheduled weekly (Monday 9am); gated by `app.llm.enabled` + a key.
 * `generate()` is also the on-demand admin trigger. Publishing is a separate manual step.
 */
@Component
class WeeklyDigestJob(
    private val factSheets: FactSheetBuilder,
    private val llm: LlmClient,
    private val store: DigestStore,
    private val props: LlmProperties,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.digest.cron:0 0 9 * * MON}")
    fun scheduled() {
        if (props.enabled && props.effectiveKey.isNotBlank()) generate()
    }

    /** Returns the new DRAFT id, or null if generation was skipped (insufficient data to ground on). */
    fun generate(): Long? {
        val weekOf = LocalDate.now(ZoneOffset.UTC)
        val factSheet = factSheets.build(weekOf)
        if (factSheet.isEmpty) {
            log.info("Weekly digest skipped: no current tournaments or reconciled matchups to ground on")
            return null
        }

        val factSheetJson = mapper.writeValueAsString(factSheet.data)
        val raw = llm.complete(DigestPrompts.SYSTEM, DigestPrompts.user(factSheetJson), props.model, MAX_TOKENS)
        val result = DigestParsing.parse(mapper, raw)

        val ungrounded = DigestParsing.ungroundedEntities(result.bodyMarkdown, factSheet.entityNames)
        if (ungrounded.isNotEmpty()) {
            log.warn("Weekly digest may reference ungrounded entities (review before publishing): {}", ungrounded)
        }

        val id = store.saveDraft(TYPE, result.title, result.bodyMarkdown, factSheet.data, props.model)
        log.info("Weekly digest DRAFT {} generated ('{}', {} flagged)", id, result.title, ungrounded.size)
        return id
    }

    companion object {
        const val TYPE = "weekly_digest"
        private const val MAX_TOKENS = 2000 // ~250-400 words + headroom
    }
}
