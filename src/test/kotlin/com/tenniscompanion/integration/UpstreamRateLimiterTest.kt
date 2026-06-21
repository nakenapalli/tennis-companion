package com.tenniscompanion.integration

import com.tenniscompanion.config.TennisApiProperties
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UpstreamRateLimiterTest {

    @Test
    fun `permits a burst up to the bucket capacity`() {
        // Fast refill so timing can't interfere; just verify the burst budget is honored.
        val limiter = UpstreamRateLimiter(
            TennisApiProperties(rateLimitPerMinute = 600_000, rateLimitBurst = 3, rateLimitMaxWaitSeconds = 5),
        )
        assertDoesNotThrow {
            repeat(3) { limiter.acquire() }
        }
    }

    @Test
    fun `throws instead of blocking when a permit would take longer than the max wait`() {
        // 1 permit/min (refill ~60s) with a 1-permit bucket and zero patience: the first call drains the
        // bucket, the second would have to wait ~60s > 0 → degrade to an exception, not a stalled thread.
        val limiter = UpstreamRateLimiter(
            TennisApiProperties(rateLimitPerMinute = 1, rateLimitBurst = 1, rateLimitMaxWaitSeconds = 0),
        )
        limiter.acquire()
        assertThrows(UpstreamApiException::class.java) { limiter.acquire() }
    }
}
