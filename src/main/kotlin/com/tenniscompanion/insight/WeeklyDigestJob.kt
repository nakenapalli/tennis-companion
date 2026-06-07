package com.tenniscompanion.insight

import com.tenniscompanion.config.LlmProperties
import com.tenniscompanion.config.NewsProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Builds the grounded fact sheet, scrapes recent articles for cited context, asks the LLM to write the
 * narrative, validates (anti-plagiarism block + citation/entity flags), then fact-checks the result
 * against the DB facts and AUTO-PUBLISHES only if the fact-check is clean (design §9). Scheduled weekly
 * (Monday 9am); gated by `app.llm.enabled` + a key. `generate()` is also the on-demand admin trigger.
 *
 * News is required input now (the digest is a news-enriched artifact): if no article can be scraped, the
 * run is skipped — nothing is generated or shown. Articles are used only transiently and never persisted.
 */
@Component
class WeeklyDigestJob(
    private val factSheets: FactSheetBuilder,
    private val llm: LlmClient,
    private val store: DigestStore,
    private val props: LlmProperties,
    private val news: NewsSource,
    private val newsProps: NewsProperties,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.digest.cron:0 0 9 * * MON}")
    fun scheduled() {
        if (props.enabled && props.effectiveKey.isNotBlank()) generate()
    }

    /** Returns the new insight id (DRAFT or auto-published), or null if skipped/aborted. */
    fun generate(): Long? {
        val weekOf = LocalDate.now(ZoneOffset.UTC)
        val factSheet = factSheets.build(weekOf)
        if (factSheet.isEmpty) {
            log.info("Weekly digest skipped: no current tournaments or reconciled matchups to ground on")
            return null
        }

        val articles = fetchArticles(weekOf, factSheet.entityNames)
        if (articles.isEmpty()) {
            log.info("Weekly digest skipped: no news articles could be scraped for context")
            return null
        }

        val factSheetJson = mapper.writeValueAsString(factSheet.data)
        val articlesJson = mapper.writeValueAsString(articles.map(Article::asContext))
        val sources = articles.flatMap { listOf(it.title, it.text) }

        val result = generateClean(factSheetJson, articlesJson, sources) ?: run {
            log.warn("Weekly digest aborted: could not produce a draft free of verbatim overlap with the sources")
            return null
        }

        // Advisory checks (human review is the backstop for these).
        val fabricated = DigestParsing.fabricatedCitations(result.bodyMarkdown, articles.mapTo(HashSet()) { it.url })
        if (fabricated.isNotEmpty()) log.warn("Weekly digest cites sources not supplied: {}", fabricated)
        val grounded = factSheet.entityNames + articles.flatMap { listOfNotNull(it.title, it.text, it.publication, it.author) }
        val ungrounded = DigestParsing.ungroundedEntities(result.bodyMarkdown, grounded)
        if (ungrounded.isNotEmpty()) log.warn("Weekly digest may reference ungrounded entities: {}", ungrounded)

        // Persist the generated content only — NEVER any article data.
        val id = store.saveDraft(TYPE, result.title, result.bodyMarkdown, factSheet.data, props.model)

        // Fact-check against the DB facts; auto-publish only if it ran AND found no contradictions.
        val report = factCheck(result.bodyMarkdown, factSheetJson)
        val contradictions = report?.let { FactCheckParsing.contradictions(it) } ?: emptyList()
        if (report != null && contradictions.isEmpty()) {
            store.publish(id)
            log.info("Weekly digest {} auto-published ('{}', {} articles, fact-check clean)", id, result.title, articles.size)
        } else {
            val why = if (report == null) "fact-check unavailable" else "${contradictions.size} contradiction(s): ${contradictions.map { it.claim }}"
            log.warn("Weekly digest {} kept as DRAFT ('{}') — {}", id, result.title, why)
        }
        return id
    }

    private fun fetchArticles(weekOf: LocalDate, entityNames: Set<String>): List<Article> {
        if (!newsProps.enabled) return emptyList()
        val since = weekOf.minusDays(newsProps.maxAgeDays).atStartOfDay(ZoneOffset.UTC).toInstant()
        return runCatching { news.recentArticles(since, newsProps.maxArticles, entityNames) }
            .getOrElse { log.warn("News fetch failed; skipping digest: {}", it.message); emptyList() }
    }

    /** Generate, rejecting any draft that copies phrasing from the sources; one stricter retry, then give up. */
    private fun generateClean(factSheetJson: String, articlesJson: String, sources: List<String>): DigestResult? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val base = DigestPrompts.user(factSheetJson, articlesJson)
            val user = if (attempt == 0) base else base +
                "\n\nIMPORTANT: a previous attempt reused wording from the articles. Rewrite everything in " +
                "your own words — do not reuse any phrase of five or more words from an article."
            val raw = llm.complete(DigestPrompts.SYSTEM, user, props.model, MAX_TOKENS)
            val result = DigestParsing.parse(mapper, raw)
            val overlaps = DigestParsing.verbatimOverlaps(result.bodyMarkdown, sources)
            if (overlaps.isEmpty()) return result
            log.warn("Weekly digest attempt {}/{} had verbatim overlap with sources: {}", attempt + 1, MAX_ATTEMPTS, overlaps.take(2))
        }
        return null
    }

    /** Verify the digest's hard facts against the DB fact sheet. Null = the check couldn't run. */
    private fun factCheck(bodyMarkdown: String, factSheetJson: String): FactCheckParsing.FactCheckReport? =
        runCatching {
            val raw = llm.complete(FactCheckPrompts.SYSTEM, FactCheckPrompts.user(bodyMarkdown, factSheetJson), props.model, FACT_CHECK_MAX_TOKENS)
            FactCheckParsing.parse(mapper, raw)
        }.getOrElse { log.warn("Weekly digest fact-check failed (kept as DRAFT): {}", it.message); null }

    companion object {
        const val TYPE = "weekly_digest"
        private const val MAX_TOKENS = 2000 // ~250-400 words + headroom
        private const val FACT_CHECK_MAX_TOKENS = 1500 // a list of per-claim verdicts
        private const val MAX_ATTEMPTS = 2 // initial + one stricter retry on a plagiarism hit
    }
}
