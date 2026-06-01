package com.tenniscompanion.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Thin liveness endpoint, distinct from Actuator's deeper `/actuator/health` (which also probes
 * Postgres + Redis). Useful as a trivial "is the app answering HTTP at all" check.
 */
@RestController
class HealthController {

    // A Kotlin `mapOf(...)` literal serializes straight to a JSON object via Jackson.
    @GetMapping("/api/health")
    fun health(): Map<String, String> =
        mapOf("status" to "UP", "service" to "tennis-companion")
}
