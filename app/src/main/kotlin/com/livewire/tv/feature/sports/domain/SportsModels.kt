package com.livewire.tv.feature.sports.domain

/**
 * Sports domain models — provider-agnostic types the UI consumes. Each SportsProvider
 * (ESPN, TheSportsDB, …) maps its wire format into these. Kotlin port of the Flutter
 * sports_models.dart (shapes originally recovered from the app binary).
 */
enum class GameState { PRE, IN_PROGRESS, FINAL, UNKNOWN }

data class GameStatus(
    val state: GameState,
    val displayClock: String? = null,
    val period: Int? = null,
    val detail: String? = null,
)

data class TeamSide(
    val id: String,
    val name: String,
    val abbreviation: String,
    val logoUrl: String? = null,
    val score: Int? = null,
    val isHome: Boolean = false,
)

data class SportsGame(
    val id: String,
    val leagueId: String,
    val startTimeMs: Long,
    val status: GameStatus,
    val home: TeamSide,
    val away: TeamSide,
    val broadcastNetworks: List<String> = emptyList(), // used for channel fusion
)

data class SportsLeague(
    val id: String,     // provider league id (e.g. "nfl")
    val name: String,
    val sport: String,  // e.g. "football"
)

data class SportsScoreboard(
    val league: SportsLeague,
    val games: List<SportsGame> = emptyList(),
)

data class StandingRow(
    val teamAbbrev: String,
    val wins: Int,
    val losses: Int,
    val rank: String? = null,
)

data class SportsStandings(
    val league: SportsLeague,
    val rows: List<StandingRow> = emptyList(),
)
