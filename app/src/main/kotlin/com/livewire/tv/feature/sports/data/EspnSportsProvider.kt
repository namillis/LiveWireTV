package com.livewire.tv.feature.sports.data

import android.annotation.SuppressLint
import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.GameStatus
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.SportsProvider
import com.livewire.tv.feature.sports.domain.SportsScoreboard
import com.livewire.tv.feature.sports.domain.TeamSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ESPN hidden-API sports provider (PRIMARY, no key). Unofficial/undocumented — fine
 * for a personal build; swap to a licensed feed behind a proxy if this ever goes
 * commercial (see ADR-0001). Kotlin port of the Flutter EspnSportsProvider; endpoints
 * and field mappings verified against live ESPN responses.
 */
@Singleton
class EspnSportsProvider @Inject constructor(
    private val http: OkHttpClient,
) : SportsProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Path(val sport: String, val league: String, val calendar: EspnCalendar)

    private val leaguePaths = linkedMapOf(
        "nfl" to Path("football", "nfl", EspnCalendar.BY_WEEK),
        "ncaaf" to Path("football", "college-football", EspnCalendar.BY_WEEK),
        "nba" to Path("basketball", "nba", EspnCalendar.BY_DAY),
        "ncaab" to Path("basketball", "mens-college-basketball", EspnCalendar.BY_DAY),
        "mlb" to Path("baseball", "mlb", EspnCalendar.BY_DAY),
        "nhl" to Path("hockey", "nhl", EspnCalendar.BY_DAY),
        "epl" to Path("soccer", "eng.1", EspnCalendar.BY_WEEK),
    )

    override suspend fun leagues(): List<SportsLeague> =
        leaguePaths.map { (id, p) -> SportsLeague(id = id, name = id.uppercase(), sport = p.sport) }

    override suspend fun scoreboard(leagueId: String): SportsScoreboard = withContext(Dispatchers.IO) {
        val p = leaguePaths[leagueId] ?: throw IllegalArgumentException("Unknown league: $leagueId")
        val url = "https://site.api.espn.com/apis/site/v2/sports/${p.sport}/${p.league}/scoreboard"
        val roots = when (p.calendar) {
            EspnCalendar.BY_DAY -> coroutineScope {
                EspnSchedule.dayQueries(Instant.now()).map { q -> async { fetch("$url?$q") } }.awaitAll()
            }
            EspnCalendar.BY_WEEK -> {
                val current = fetch(url)
                listOfNotNull(current, EspnSchedule.nextWeekQuery(current)?.let { fetch("$url?$it") })
            }
        }
        val events = roots.flatMap { it["events"]?.jsonArray ?: JsonArray(emptyList()) }
        SportsScoreboard(
            league = SportsLeague(leagueId, leagueId.uppercase(), p.sport),
            games = events.mapNotNull { mapGame(it.jsonObject, leagueId) }.distinctBy { it.id },
        )
    }

    private fun fetch(url: String): JsonObject = json.parseToJsonElement(get(url) ?: "{}").jsonObject

    // --- mapping ---------------------------------------------------------------

    private fun mapGame(ev: JsonObject, leagueId: String): SportsGame? {
        val comp = ev["competitions"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val competitors = comp["competitors"]?.jsonArray ?: return null

        fun side(c: JsonObject): TeamSide {
            val t = c["team"]?.jsonObject ?: JsonObject(emptyMap())
            return TeamSide(
                id = t["id"].str().orEmpty(),
                name = t["displayName"].str().orEmpty(),
                abbreviation = t["abbreviation"].str().orEmpty(),
                logoUrl = t["logo"].str(),
                score = c["score"].str()?.toIntOrNull(),
                isHome = c["homeAway"].str() == "home",
                shortName = t["shortDisplayName"].str().orEmpty(),
            )
        }

        val home = competitors.map { it.jsonObject }.firstOrNull { it["homeAway"].str() == "home" }
        val away = competitors.map { it.jsonObject }.firstOrNull { it["homeAway"].str() == "away" }

        val networks = buildList {
            comp["broadcasts"]?.jsonArray?.forEach { b ->
                b.jsonObject["names"]?.jsonArray?.forEach { n -> n.jsonPrimitive.contentOrNull?.let { add(it) } }
            }
        }

        val typeObj = (comp["status"]?.jsonObject ?: ev["status"]?.jsonObject)?.get("type")?.jsonObject
        val state = when (typeObj?.get("state").str()) {
            "pre" -> GameState.PRE
            "in" -> GameState.IN_PROGRESS
            "post" -> GameState.FINAL
            else -> GameState.UNKNOWN
        }

        return SportsGame(
            id = ev["id"].str().orEmpty(),
            leagueId = leagueId,
            startTimeMs = parseIso(ev["date"].str()),
            status = GameStatus(
                state = state,
                displayClock = typeObj?.get("shortDetail").str(),
                detail = typeObj?.get("detail").str(),
            ),
            home = home?.let(::side) ?: TeamSide("", "", "", isHome = true),
            away = away?.let(::side) ?: TeamSide("", "", ""),
            broadcastNetworks = networks,
        )
    }

    private fun parseIso(s: String?): Long = parseEspnDate(s) ?: System.currentTimeMillis()

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.str(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/**
 * How a league's default ESPN scoreboard is paged. A [BY_DAY] league's default is one day, and
 * ESPN keeps serving yesterday's (all finished) until late morning Eastern, so it is asked for
 * each day by date instead. A [BY_WEEK] league's default is its current week or matchday.
 */
internal enum class EspnCalendar { BY_DAY, BY_WEEK }

internal object EspnSchedule {
    /** ESPN's scoreboard days run on US Eastern time. */
    private val espnZone = java.time.ZoneId.of("America/New_York")

    /** How many days a [EspnCalendar.BY_DAY] league shows, today first. */
    const val DAYS_SHOWN = 3

    /** The `dates=` query for each day shown, from today in ESPN's time zone. */
    fun dayQueries(now: Instant): List<String> {
        val today = now.atZone(espnZone).toLocalDate()
        return (0 until DAYS_SHOWN).map { offset ->
            val day = today.plusDays(offset.toLong())
            "dates=%04d%02d%02d".format(java.util.Locale.US, day.year, day.monthValue, day.dayOfMonth)
        }
    }

    /**
     * The query for the week after [root]'s when every game in it is over (the NFL's week stays
     * on the finished one until Wednesday), else null. Null too when [root] has no week number.
     */
    fun nextWeekQuery(root: JsonObject): String? {
        val events = root["events"]?.jsonArray ?: return null
        val allOver = events.isNotEmpty() && events.all { ev ->
            val o = ev.jsonObject
            val comp = o["competitions"]?.jsonArray?.firstOrNull()?.jsonObject
            val status = comp?.get("status")?.jsonObject ?: o["status"]?.jsonObject
            status?.get("type")?.jsonObject?.get("state").str() == "post"
        }
        if (!allOver) return null
        val week = root["week"]?.jsonObject?.get("number").str()?.toIntOrNull() ?: return null
        val seasonType = root["season"]?.jsonObject?.get("type").str() ?: return null
        return "week=${week + 1}&seasontype=$seasonType"
    }
}

/**
 * ESPN's scoreboard dates omit seconds ("2026-09-29T00:15Z"), which `Instant.parse`
 * rejects on Android's runtime, so every game fell back to "now" as its start time.
 * This accepts the value with or without seconds, and with `Z` or a numeric offset.
 *
 * Safe below API 26: core library desugaring rewrites these calls to the bundled
 * `j$.time` copy (checked in the dex). Lint's desugared-API list names the builder's
 * methods but not its constructor, so it reports a NewApi error that cannot happen.
 */
@SuppressLint("NewApi")
internal fun parseEspnDate(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    val formatter = java.time.format.DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .optionalStart().appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .appendOffset("+HH:MM", "Z")
        .toFormatter(java.util.Locale.US)
    return runCatching { java.time.OffsetDateTime.parse(s.trim(), formatter).toInstant().toEpochMilli() }.getOrNull()
}
