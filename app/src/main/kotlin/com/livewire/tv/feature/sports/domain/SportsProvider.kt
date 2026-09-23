package com.livewire.tv.feature.sports.domain

/**
 * Swappable sports source. EspnSportsProvider is primary (no key). A future
 * TheSportsDbProvider / proxied provider implements the same interface without
 * touching the repository or UI. Kotlin port of the Flutter SportsProvider.
 */
interface SportsProvider {
    suspend fun leagues(): List<SportsLeague>
    suspend fun scoreboard(leagueId: String): SportsScoreboard
    suspend fun standings(leagueId: String): SportsStandings
}
