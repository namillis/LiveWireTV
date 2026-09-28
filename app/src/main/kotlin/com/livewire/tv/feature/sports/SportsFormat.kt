package com.livewire.tv.feature.sports

import com.livewire.tv.feature.sports.domain.GameState
import com.livewire.tv.feature.sports.domain.SportsGame

/**
 * Pure formatting for the Sports scoreboard (design system §7, §9). No Android/Compose
 * calls so it stays unit-testable; the screen renders these strings and picks the colour
 * from [GameStatus.isLive]. A live game's clock is spelled out (never colour-only, §3.3).
 */
object SportsFormat {

    /** True only while a game is actually in progress — the one case that renders red. */
    fun isLive(game: SportsGame): Boolean = game.status.state == GameState.IN_PROGRESS

    /**
     * The status line under the two team rows:
     *  - live  → the clock/period ("10:06 – 1st"), falling back to the detail or "LIVE";
     *  - pre   → the scheduled detail ("Sun, Sep 27 at 1:00 PM"), or "Scheduled";
     *  - final → the detail ("Final"), or "Final";
     *  - unknown → whatever detail exists, else "".
     */
    fun statusLine(game: SportsGame): String = when (game.status.state) {
        GameState.IN_PROGRESS -> game.status.displayClock?.takeIf { it.isNotBlank() }
            ?: game.status.detail?.takeIf { it.isNotBlank() }
            ?: "LIVE"
        GameState.PRE -> game.status.detail?.takeIf { it.isNotBlank() } ?: "Scheduled"
        GameState.FINAL -> game.status.detail?.takeIf { it.isNotBlank() } ?: "Final"
        GameState.UNKNOWN -> game.status.detail?.takeIf { it.isNotBlank() } ?: ""
    }

    /**
     * Whether to render the two teams' scores. A game that has not started (PRE) has no
     * meaningful score, so the scoreboard shows none and puts the start time (the PRE
     * [statusLine]) where the status goes; scores appear only for in-progress and final
     * games. UNKNOWN is treated as not-yet-scored.
     */
    fun showScores(game: SportsGame): Boolean =
        game.status.state == GameState.IN_PROGRESS || game.status.state == GameState.FINAL

    /** A team's score for the scoreboard column, or an em dash before it has one. */
    fun scoreLabel(score: Int?): String = score?.toString() ?: "—"

    /** The "On: FOX, ABC" header line for the channel picker; a plain note when none listed. */
    fun networksLine(game: SportsGame): String =
        if (game.broadcastNetworks.isEmpty()) "No broadcast network listed"
        else "On: ${game.broadcastNetworks.joinToString(", ")}"

    /** The picker's empty-state sentence, naming the network that has no channel (§9.9). */
    fun noChannelMessage(game: SportsGame): String {
        val net = game.broadcastNetworks.firstOrNull()?.takeIf { it.isNotBlank() }
        return if (net != null) "No channel found for $net" else "No channel found for this game"
    }
}
