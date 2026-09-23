package com.livewire.tv.feature.sports.data

import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.GameStatus
import com.livewire.tv.feature.sports.domain.SportsGame
import com.livewire.tv.feature.sports.domain.SportsLeague
import com.livewire.tv.feature.sports.domain.SportsProvider
import com.livewire.tv.feature.sports.domain.SportsScoreboard
import com.livewire.tv.feature.sports.domain.SportsStandings
import com.livewire.tv.feature.sports.domain.StandingRow
import com.livewire.tv.feature.sports.domain.TeamSide
import kotlinx.coroutines.Dispatchers
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

    private data class Path(val sport: String, val league: String)

    private val leaguePaths = linkedMapOf(
        "nfl" to Path("football", "nfl"),
        "ncaaf" to Path("football", "college-football"),
        "nba" to Path("basketball", "nba"),
        "ncaab" to Path("basketball", "mens-college-basketball"),
        "mlb" to Path("baseball", "mlb"),
        "nhl" to Path("hockey", "nhl"),
        "epl" to Path("soccer", "eng.1"),
    )

    override suspend fun leagues(): List<SportsLeague> =
        leaguePaths.map { (id, p) -> SportsLeague(id = id, name = id.uppercase(), sport = p.sport) }

    override suspend fun scoreboard(leagueId: String): SportsScoreboard = withContext(Dispatchers.IO) {
        val p = leaguePaths[leagueId] ?: throw IllegalArgumentException("Unknown league: $leagueId")
        val url = "https://site.api.espn.com/apis/site/v2/sports/${p.sport}/${p.league}/scoreboard"
        val root = json.parseToJsonElement(get(url) ?: "{}").jsonObject
        val events = root["events"]?.jsonArray ?: JsonArray(emptyList())
        SportsScoreboard(
            league = SportsLeague(leagueId, leagueId.uppercase(), p.sport),
            games = events.mapNotNull { mapGame(it.jsonObject, leagueId) },
        )
    }

    override suspend fun standings(leagueId: String): SportsStandings = withContext(Dispatchers.IO) {
        val p = leaguePaths[leagueId] ?: throw IllegalArgumentException("Unknown league: $leagueId")
        val url = "https://site.api.espn.com/apis/v2/sports/${p.sport}/${p.league}/standings?level=1"
        val root = json.parseToJsonElement(get(url) ?: "{}").jsonObject
        val entries = root["standings"]?.jsonObject?.get("entries")?.jsonArray
            ?: flattenChildren(root)
        val rows = entries.mapNotNull { mapStandingRow(it.jsonObject) }
            .sortedWith(compareBy({ it.rank?.toIntOrNull() ?: Int.MAX_VALUE }, { -it.wins }))
        SportsStandings(SportsLeague(leagueId, leagueId.uppercase(), p.sport), rows)
    }

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

    private fun mapStandingRow(entry: JsonObject): StandingRow? {
        val team = entry["team"]?.jsonObject ?: return null
        val stats = entry["stats"]?.jsonArray ?: return null
        fun stat(name: String): Int? {
            val s = stats.map { it.jsonObject }.firstOrNull { it["name"].str() == name } ?: return null
            return (s["value"].str()?.toDoubleOrNull())?.toInt() ?: s["displayValue"].str()?.toIntOrNull()
        }
        val seed = stat("playoffSeed")
        return StandingRow(
            teamAbbrev = team["abbreviation"].str().orEmpty(),
            wins = stat("wins") ?: 0,
            losses = stat("losses") ?: 0,
            rank = if (seed != null && seed > 0) seed.toString() else null,
        )
    }

    private fun flattenChildren(node: JsonObject): JsonArray {
        val out = mutableListOf<kotlinx.serialization.json.JsonElement>()
        node["standings"]?.jsonObject?.get("entries")?.jsonArray?.let { out.addAll(it) }
        node["children"]?.jsonArray?.forEach { out.addAll(flattenChildren(it.jsonObject)) }
        return JsonArray(out)
    }

    private fun parseIso(s: String?): Long =
        s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()

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
