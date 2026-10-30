package com.maurointeractive.reactionrush

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.roundToInt

data class PersonalRecords(
    val lifetimeBestMs: Int = 0,
    val lifetimeReactions: Int = 0,
    val modeBestMs: Map<GameMode, Int> = emptyMap()
) {
    fun bestFor(mode: GameMode): Int = modeBestMs[mode] ?: 0
}

data class ReactionUpdate(
    val records: PersonalRecords,
    val isNewRecord: Boolean
)

class RecordsRepository(private val prefs: SharedPreferences) {
    fun load(): PersonalRecords = PersonalRecords(
        // Keep the original keys: an upgrade must not reset lifetime statistics.
        lifetimeBestMs = prefs.getInt("best", 0),
        lifetimeReactions = prefs.getInt("attempts", 0),
        modeBestMs = GameMode.entries.associateWith { prefs.getInt(it.recordKey, 0) }
    )

    /** Called once for each successful reaction, including the final round. */
    fun recordReaction(
        mode: GameMode,
        scoreMs: Int,
        sessionScores: List<Int>
    ): ReactionUpdate {
        require(scoreMs > 0) { "Reaction time must be positive." }
        require(sessionScores.size in 1..mode.rounds) { "Invalid session length." }
        require(sessionScores.all { it > 0 } && sessionScores.last() == scoreMs) {
            "Session scores must include the current successful reaction."
        }

        val previous = load()
        val lifetimeBest = if (previous.lifetimeBestMs == 0) scoreMs
            else minOf(previous.lifetimeBestMs, scoreMs)
        val sessionScore = if (sessionScores.size == mode.rounds)
            sessionScores.average().roundToInt() else null
        val isNewRecord = sessionScore != null &&
            (previous.bestFor(mode) == 0 || sessionScore < previous.bestFor(mode))
        val updated = previous.copy(
            lifetimeBestMs = lifetimeBest,
            lifetimeReactions = previous.lifetimeReactions + 1,
            modeBestMs = if (isNewRecord)
                previous.modeBestMs + (mode to checkNotNull(sessionScore))
            else previous.modeBestMs
        )

        // One editor persists the reaction and any completed-session record together.
        prefs.edit {
            putInt("best", updated.lifetimeBestMs)
            putInt("attempts", updated.lifetimeReactions)
            if (isNewRecord) putInt(mode.recordKey, checkNotNull(sessionScore))
        }

        return ReactionUpdate(updated, isNewRecord)
    }
}
