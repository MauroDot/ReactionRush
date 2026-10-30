package com.maurointeractive.reactionrush

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

data class PersonalRecords(
    val lifetimeBestMs: Int = 0,
    val lifetimeReactions: Int = 0,
    val modeBestMs: Map<GameMode, Int> = emptyMap(),
    val totalXp: Long = 0
) {
    fun bestFor(mode: GameMode): Int = modeBestMs[mode] ?: 0
}

data class ReactionUpdate(
    val records: PersonalRecords,
    val isNewRecord: Boolean,
    val reward: SessionReward? = null
)

data class SessionReward(
    val xp: XpReward,
    val awardedXp: Int,
    val medal: PerformanceMedal,
    val previousLevel: Int,
    val newLevel: Int
)

class RecordsRepository(private val prefs: SharedPreferences) {
    fun load(): PersonalRecords = synchronized(prefs) { recordsFrom(prefs.all) }

    private fun recordsFrom(saved: Map<String, *>): PersonalRecords = PersonalRecords(
        // Keep the original keys: an upgrade must not reset lifetime statistics.
        lifetimeBestMs = saved.nonNegativeInt("best"),
        lifetimeReactions = saved.nonNegativeInt("attempts"),
        modeBestMs = GameMode.entries.associateWith { saved.nonNegativeInt(it.recordKey) },
        // XP is written as Long. A missing, negative, or wrongly typed value starts at zero.
        totalXp = (saved[TOTAL_XP] as? Long)?.coerceAtLeast(0L) ?: 0L
    )

    /** Replaces any abandoned session; starting a session never awards XP. */
    fun startSession(mode: GameMode): String = synchronized(prefs) {
        val sessionId = UUID.randomUUID().toString()
        prefs.edit {
            putString(ACTIVE_SESSION_ID, sessionId)
            putString(ACTIVE_SESSION_MODE, mode.name)
            putInt(ACTIVE_SESSION_ROUNDS, 0)
        }
        sessionId
    }

    /** Invalidates an unfinished session without changing earned records or XP. */
    fun abandonSession(sessionId: String) {
        synchronized(prefs) {
            if (sessionId.isBlank() || prefs.all[ACTIVE_SESSION_ID] != sessionId) {
                return@synchronized
            }
            prefs.edit {
                remove(ACTIVE_SESSION_ID)
                remove(ACTIVE_SESSION_MODE)
                remove(ACTIVE_SESSION_ROUNDS)
            }
        }
    }

    /** Accepts only the next successful reaction of the current persisted session. */
    fun recordReaction(
        sessionId: String,
        mode: GameMode,
        scoreMs: Int,
        sessionScores: List<Int>
    ): ReactionUpdate {
        require(scoreMs > 0) { "Reaction time must be positive." }
        require(sessionScores.size in 1..mode.rounds) { "Invalid session length." }
        require(sessionScores.all { it > 0 } && sessionScores.last() == scoreMs) {
            "Session scores must include the current successful reaction."
        }

        // SharedPreferences applies edits in memory immediately. Use the same lock across
        // repository instances so two callbacks cannot both accept the same round.
        return synchronized(prefs) {
            val saved = prefs.all
            val previous = recordsFrom(saved)
            val acceptedRounds = saved[ACTIVE_SESSION_ROUNDS] as? Int
            if (sessionId.isBlank() || saved[ACTIVE_SESSION_ID] != sessionId ||
                saved[ACTIVE_SESSION_MODE] != mode.name || acceptedRounds == null ||
                acceptedRounds !in 0 until mode.rounds ||
                sessionScores.size != acceptedRounds + 1
            ) {
                return@synchronized ReactionUpdate(previous, isNewRecord = false)
            }

            val lifetimeBest = if (previous.lifetimeBestMs == 0) scoreMs
                else minOf(previous.lifetimeBestMs, scoreMs)
            val sessionScore = if (sessionScores.size == mode.rounds)
                PlayerProgression.sessionScore(mode, sessionScores) else null
            val isNewRecord = sessionScore != null &&
                (previous.bestFor(mode) == 0 || sessionScore < previous.bestFor(mode))
            val earnedXp = if (sessionScore != null)
                PlayerProgression.reward(mode, sessionScores) else null
            val totalXp = if (earnedXp != null)
                PlayerProgression.addXp(previous.totalXp, earnedXp.totalXp) else previous.totalXp
            val reward = if (earnedXp != null) SessionReward(
                xp = earnedXp,
                awardedXp = (totalXp - previous.totalXp).toInt(),
                medal = PlayerProgression.medal(checkNotNull(sessionScore)),
                previousLevel = PlayerProgression.levelProgress(previous.totalXp).level,
                newLevel = PlayerProgression.levelProgress(totalXp).level
            ) else null
            val updated = previous.copy(
                lifetimeBestMs = lifetimeBest,
                lifetimeReactions = if (previous.lifetimeReactions == Int.MAX_VALUE)
                    Int.MAX_VALUE else previous.lifetimeReactions + 1,
                modeBestMs = if (isNewRecord)
                    previous.modeBestMs + (mode to checkNotNull(sessionScore))
                else previous.modeBestMs,
                totalXp = totalXp
            )

            // Completion consumes the token in the same edit as its record and reward.
            prefs.edit {
                putInt("best", updated.lifetimeBestMs)
                putInt("attempts", updated.lifetimeReactions)
                if (isNewRecord) putInt(mode.recordKey, checkNotNull(sessionScore))
                if (reward != null) {
                    putLong(TOTAL_XP, totalXp)
                    remove(ACTIVE_SESSION_ID)
                    remove(ACTIVE_SESSION_MODE)
                    remove(ACTIVE_SESSION_ROUNDS)
                } else {
                    putInt(ACTIVE_SESSION_ROUNDS, sessionScores.size)
                }
            }

            ReactionUpdate(updated, isNewRecord, reward)
        }
    }

    private fun Map<String, *>.nonNegativeInt(key: String): Int =
        (this[key] as? Int)?.coerceAtLeast(0) ?: 0

    private companion object {
        const val TOTAL_XP = "total_xp"
        const val ACTIVE_SESSION_ID = "active_session_id"
        const val ACTIVE_SESSION_MODE = "active_session_mode"
        const val ACTIVE_SESSION_ROUNDS = "active_session_rounds"
    }
}
