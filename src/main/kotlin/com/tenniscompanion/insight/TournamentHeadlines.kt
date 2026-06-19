package com.tenniscompanion.insight

import com.tenniscompanion.api.HeadlineDto
import com.tenniscompanion.reconcile.NameNormalizer
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

/**
 * Best-effort tournament headlines for the Overview tab. Reuses the digest's [NewsSource] (scraped tennis
 * news), prioritizing + filtering to the tournament's name, and caches just the metadata in Redis for an
 * hour — scraping per page-load would be slow, and article bodies are never persisted.
 */
@Service
class TournamentHeadlines(
    private val news: NewsSource,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val ttl = Duration.ofHours(1)
    private val window = Duration.ofDays(21)
    private val limit = 6

    fun forTournament(id: Long, name: String, location: String?): List<HeadlineDto> {
        val key = "news:tournament:$id"
        redis.opsForValue().get(key)?.let { return mapper.readValue(it, Array<HeadlineDto>::class.java).toList() }

        val prioritize = setOfNotNull(name, location.takeUnless { it.isNullOrBlank() })
        // Distinctive name tokens (drops "open"/"cup"/tour prefixes) used to keep only on-topic articles.
        val tokens = NameNormalizer.tokens(name).filter { it.length >= 5 }.toSet()
        val articles = news.recentArticles(Instant.now().minus(window), limit * 3, prioritize)

        val headlines = articles.asSequence()
            .filter { a ->
                if (tokens.isEmpty()) return@filter true
                val hay = NameNormalizer.fold(a.title + " " + a.text)
                tokens.any { hay.contains(it) }
            }
            .take(limit)
            .map { HeadlineDto(it.title, it.publication, it.url, it.publishedAt) }
            .toList()

        redis.opsForValue().set(key, mapper.writeValueAsString(headlines), ttl)
        return headlines
    }
}
