package com.tenniscompanion.match

import com.tenniscompanion.api.PlayerService
import com.tenniscompanion.integration.NormalizedMatchDetail
import com.tenniscompanion.integration.NormalizedStat
import com.tenniscompanion.integration.TennisApiAdapter
import com.tenniscompanion.poller.LiveDataStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/**
 * Backs the match-view Momentum + Stats tabs. One upstream call per match (`get_fixtures&match_key=…`,
 * via the adapter) returns both the point-by-point flow and the statistics; we cache the normalized
 * detail in Redis (short TTL while live, long once finished) and compute the momentum line / stats
 * comparison on read. Player names + best-of come from the already-served match detail.
 */
@Service
class MatchDetailService(
    private val adapter: TennisApiAdapter,
    private val liveData: LiveDataStore,
    private val playerService: PlayerService,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val typeOrder = listOf("Service", "Return", "Points", "Games")

    fun momentum(externalId: String): MomentumDto? {
        val m = liveData.matchDetail(adapter.source, externalId) ?: return null
        val detail = detail(externalId, m.status) ?: return null
        if (detail.games.isEmpty()) return null
        val result = MomentumCalculator.compute(detail.games, bestOf(m.category, m.tour))
        return MomentumDto(
            bestOf = bestOf(m.category, m.tour),
            player1 = m.player1.name,
            player2 = m.player2.name,
            series = result.series,
            breaks = result.breaks,
            sets = result.sets,
            meta = result.meta,
        )
    }

    fun stats(externalId: String): MatchStatsDto? {
        val m = liveData.matchDetail(adapter.source, externalId) ?: return null
        val detail = detail(externalId, m.status) ?: return null
        if (detail.statistics.isEmpty()) return null
        return mapStats(detail.statistics, m.player1.name, m.player2.name)
    }

    /**
     * Head-to-head: prior meetings + the record. Uses our Sackmann history when both players are
     * reconciled (richer, free); falls back to the live feed's `get_H2H` by player key otherwise.
     */
    fun h2h(externalId: String): H2hViewDto? {
        val m = liveData.matchDetail(adapter.source, externalId) ?: return null
        return cached("h2h:${adapter.source}:$externalId", m.status) {
            val id1 = m.player1.playerId
            val id2 = m.player2.playerId
            if (id1 != null && id2 != null) {
                val h = playerService.headToHead(id1, id2)
                H2hViewDto(
                    player1 = m.player1.name, player2 = m.player2.name,
                    p1Wins = h.playerWins, p2Wins = h.opponentWins, source = "historical",
                    meetings = h.matches.map {
                        H2hMeetingDto(it.tourneyDate?.toString(), it.tourneyName, it.round, it.surface, if (it.result == "W") 1 else 2, it.score)
                    },
                )
            } else {
                val detail = detail(externalId, m.status)
                val k1 = detail?.player1Key
                val k2 = detail?.player2Key
                if (k1 == null || k2 == null) return@cached null
                val meetings = adapter.fetchH2H(k1, k2).ifEmpty { return@cached null }
                H2hViewDto(
                    player1 = m.player1.name, player2 = m.player2.name,
                    p1Wins = meetings.count { it.winnerSide == 1 }, p2Wins = meetings.count { it.winnerSide == 2 },
                    source = "live",
                    meetings = meetings.map { H2hMeetingDto(it.date, it.tournament, it.round, null, it.winnerSide, it.score) },
                )
            }
        }
    }

    /** Side-by-side bios: DB profile (reconciled players) + live career splits (by player key). */
    fun players(externalId: String): PlayersViewDto? {
        val m = liveData.matchDetail(adapter.source, externalId) ?: return null
        return cached("players:${adapter.source}:$externalId", m.status) {
            val detail = detail(externalId, m.status)
            PlayersViewDto(
                bio(m.player1.name, m.player1.playerId, m.player1.country, detail?.player1Key),
                bio(m.player2.name, m.player2.playerId, m.player2.country, detail?.player2Key),
            )
        }
    }

    private fun bio(name: String, playerId: UUID?, feedCountry: String?, key: String?): PlayerBioDto {
        val profile = playerId?.let { playerService.profile(it) }
        val career = key?.let { runCatching { adapter.fetchPlayerCareer(it) }.getOrNull() }
        val age = profile?.birthDate?.let { Period.between(it, LocalDate.now()).years }
            ?: career?.birthYear?.let { LocalDate.now().year - it }
        return PlayerBioDto(
            name = name,
            country = profile?.country ?: feedCountry ?: career?.country,
            hand = profile?.hand,
            heightCm = profile?.heightCm,
            age = age,
            rank = profile?.currentRank ?: career?.rank,
            logo = career?.logo,
            season = career?.season,
            titles = career?.titles,
            wins = career?.wins, losses = career?.losses,
            hardWins = career?.hardWins, hardLosses = career?.hardLosses,
            clayWins = career?.clayWins, clayLosses = career?.clayLosses,
            grassWins = career?.grassWins, grassLosses = career?.grassLosses,
        )
    }

    /** Cache a computed view in Redis (null → short-lived sentinel), TTL by match status. */
    private inline fun <reified T : Any> cached(key: String, status: String?, compute: () -> T?): T? {
        redis.opsForValue().get(key)?.let { return if (it == EMPTY) null else mapper.readValue(it, T::class.java) }
        val v = runCatching { compute() }.getOrNull()
        if (v == null) {
            redis.opsForValue().set(key, EMPTY, Duration.ofMinutes(5))
            return null
        }
        redis.opsForValue().set(key, mapper.writeValueAsString(v), ttl(status))
        return v
    }

    /** Cached normalized detail. A null upstream result is cached as a short-lived sentinel so a stats-less
     *  lower-circuit match isn't re-fetched on every request. */
    private fun detail(externalId: String, status: String?): NormalizedMatchDetail? {
        val key = "matchdetail:v2:${adapter.source}:$externalId" // v2: shape gained player keys
        redis.opsForValue().get(key)?.let {
            return if (it == EMPTY) null else mapper.readValue(it, NormalizedMatchDetail::class.java)
        }
        val fetched = runCatching { adapter.fetchMatchDetail(externalId) }.getOrNull()
        if (fetched == null) {
            redis.opsForValue().set(key, EMPTY, Duration.ofMinutes(5))
            return null
        }
        redis.opsForValue().set(key, mapper.writeValueAsString(fetched), ttl(status))
        return fetched
    }

    /** Live data churns; finished matches never change. */
    private fun ttl(status: String?): Duration = when (status) {
        "live" -> Duration.ofSeconds(30)
        "finished" -> Duration.ofHours(12)
        else -> Duration.ofMinutes(2)
    }

    /** Best-of-5 only for men's Grand Slam singles; everything else is best-of-3. */
    private fun bestOf(category: String?, tour: String?): Int =
        if (category == "Grand Slam" && tour == "ATP") 5 else 3

    private fun mapStats(stats: List<NormalizedStat>, p1: String, p2: String): MatchStatsDto {
        val periods = stats.map { it.period }.distinct().sortedBy { if (it == "match") "" else it }
        val byPeriod = periods.associateWith { period ->
            stats.filter { it.period == period }
                .groupBy { it.type }
                .toList()
                .sortedBy { typeOrder.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
                .map { (type, rows) ->
                    val names = rows.map { it.name }.distinct()
                    StatGroupDto(type, names.map { name ->
                        val s1 = rows.firstOrNull { it.side == 1 && it.name == name }
                        val s2 = rows.firstOrNull { it.side == 2 && it.name == name }
                        StatRowDto(name, StatCellDto(s1?.value, s1?.won, s1?.total), StatCellDto(s2?.value, s2?.won, s2?.total))
                    })
                }
        }
        return MatchStatsDto(p1, p2, periods, byPeriod)
    }

    private companion object {
        const val EMPTY = " " // sentinel: upstream returned no detail for this match
    }
}
