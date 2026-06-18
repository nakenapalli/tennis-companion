package com.tenniscompanion.enrichment

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

data class EnrichmentSummary(val resolved: Int, val exhausted: Int, val pending: Int)

/**
 * Drives the enrichment queue: dequeues pending tasks, attempts deterministic resolution, and schedules
 * unresolved items for retry (up to [MAX_ATTEMPTS]). Phase 3 will add an agent pass between deterministic
 * failure and exhaustion.
 */
@Component
class EnrichmentJob(
    private val queue: EnrichmentQueueStore,
    private val enricher: DeterministicEnricher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ENRICHMENT_JOB_CRON:0 20 0 * * *}")
    fun scheduled() { run() }

    fun run(limit: Int = 50): EnrichmentSummary {
        val tasks = queue.dequeueForProcessing(limit.coerceIn(1, 200))
        var resolved = 0; var exhausted = 0; var pending = 0
        for (task in tasks) {
            val done = enricher.enrich(task)
            when {
                done -> { queue.markDone(task.entityType, task.entityId); resolved++ }
                task.attempts + 1 >= MAX_ATTEMPTS -> { queue.markExhausted(task.entityType, task.entityId); exhausted++ }
                else -> { queue.markPending(task.entityType, task.entityId); pending++ }
            }
        }
        log.info("Enrichment run: resolved={} exhausted={} retrying={} total={}", resolved, exhausted, pending, tasks.size)
        return EnrichmentSummary(resolved, exhausted, pending)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
