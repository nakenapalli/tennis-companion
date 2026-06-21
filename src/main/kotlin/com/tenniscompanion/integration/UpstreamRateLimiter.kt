package com.tenniscompanion.integration

import com.tenniscompanion.config.TennisApiProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * A small thread-safe token bucket guarding every call to the upstream feed, applied via a
 * [com.tenniscompanion.config.RestClientConfig] interceptor. The api-tennis Starter quota is generous
 * (~8,000 req/day), so this is a *safety cap* — it smooths bursts (a rankings poll is two back-to-back
 * calls) and stops a misconfiguration or runaway loop from burning the daily quota, rather than a tight
 * throttle. Normal polling (one live call/60s + a couple of daily jobs) never gets close to the limit.
 *
 * `acquire()` blocks the caller (a scheduled poll thread or an admin-triggered request) until a permit
 * is free, but only up to [TennisApiProperties.rateLimitMaxWaitSeconds]; past that it throws
 * [UpstreamApiException] so a stuck/over-subscribed limiter degrades to "skip this poll, keep last-good"
 * instead of pinning a thread forever.
 */
@Component
class UpstreamRateLimiter(props: TennisApiProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val capacity = props.rateLimitBurst.coerceAtLeast(1).toDouble()
    private val permitsPerMinute = props.rateLimitPerMinute.coerceAtLeast(1)
    /** Nanoseconds of elapsed time that earns one permit back. */
    private val refillIntervalNanos = TimeUnit.MINUTES.toNanos(1) / permitsPerMinute
    private val maxWaitNanos = TimeUnit.SECONDS.toNanos(props.rateLimitMaxWaitSeconds.coerceAtLeast(0))

    private val lock = Any()
    private var tokens = capacity
    private var lastRefill = System.nanoTime()

    /** Acquire one permit, blocking (bounded) until one is available. Throws if the wait would be too long. */
    fun acquire() {
        var waited = 0L
        while (true) {
            val waitNanos = synchronized(lock) {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return
                }
                // Time until the bucket accrues the fraction of a token we're short.
                ((1.0 - tokens) * refillIntervalNanos).toLong().coerceAtLeast(1_000_000)
            }
            if (waited + waitNanos > maxWaitNanos) {
                throw UpstreamApiException(
                    "Upstream rate limit exceeded (${permitsPerMinute}/min): waited ${waited / 1_000_000}ms, would need ${waitNanos / 1_000_000}ms more",
                )
            }
            if (waited == 0L) log.debug("Upstream rate limit hit; waiting {}ms for a permit", waitNanos / 1_000_000)
            TimeUnit.NANOSECONDS.sleep(waitNanos)
            waited += waitNanos
        }
    }

    /** Accrue tokens for the time elapsed since the last refill, capped at the bucket capacity. */
    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - lastRefill
        if (elapsed > 0) {
            tokens = (tokens + elapsed.toDouble() / refillIntervalNanos).coerceAtMost(capacity)
            lastRefill = now
        }
    }
}
