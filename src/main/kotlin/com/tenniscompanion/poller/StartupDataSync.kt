package com.tenniscompanion.poller

import com.tenniscompanion.config.PollProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Pulls rankings + tournaments once on startup so a freshly-booted instance serves current data
 * immediately, rather than waiting for the next daily @Scheduled cron. Gated by
 * `app.poll.enabled` + `app.poll.startup-sync` (both default true; tests disable polling, so this
 * no-ops there). Each call is wrapped in runCatching — matching the pollers' resilience convention —
 * so an upstream outage degrades to "keep last-good" and never blocks the app from starting.
 *
 * Reconciliation during the rankings pull assumes the canonical Sackmann players are already loaded
 * (the one-time historical load); on a normal boot they're already in the DB.
 */
@Component
class StartupDataSync(
    private val rankingsPoller: RankingsPoller,
    private val tournamentSyncJob: TournamentSyncJob,
    private val props: PollProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (!props.enabled || !props.startupSync) {
            log.info("Startup data sync disabled (app.poll.enabled={}, app.poll.startup-sync={}).", props.enabled, props.startupSync)
            return
        }
        runCatching { rankingsPoller.poll() }
            .onFailure { log.warn("Startup rankings sync skipped (upstream error): {}", it.message) }
        runCatching { tournamentSyncJob.sync() }
            .onFailure { log.warn("Startup tournament sync skipped (upstream error): {}", it.message) }
        log.info("Startup data sync complete.")
    }
}
