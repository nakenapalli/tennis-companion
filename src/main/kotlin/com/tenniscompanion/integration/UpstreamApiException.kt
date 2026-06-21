package com.tenniscompanion.integration

/**
 * Thrown when an upstream (api-tennis) call fails in a way the poll path must NOT mistake for a
 * legitimate empty result — a non-2xx response, an error envelope (`success != 1`), a missing body,
 * or the client-side rate limiter refusing to wait any longer. Callers on the write path let it
 * propagate so the last-good Redis/Postgres snapshot is left untouched (design §"serve last good data").
 */
class UpstreamApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
