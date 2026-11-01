package com.maurointeractive.reactionrush

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class PersonalRecords(
    val lifetimeBestMs: Int = 0,
    val lifetimeReactions: Int = 0,
    val modeBestMs: Map<GameMode, Int> = emptyMap(),
    val totalXp: Long = 0,
    val achievements: AchievementState = AchievementState(),
    val history: SessionHistory = SessionHistory(),
    val completedByMode: Map<GameMode, Long> = emptyMap()
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
    val newLevel: Int,
    val newlyUnlocked: Set<Achievement> = emptySet()
)

class RecordsRepository(
    private val prefs: SharedPreferences,
    private val completionPersistence: CompletionPersistence = CompletionPersistence.shared(prefs),
    private val clock: () -> Long = System::currentTimeMillis
) {
    val saveStatus: StateFlow<SaveStatus> = completionPersistence.status

    fun retrySave() = completionPersistence.retry()

    /** Read after preferences hydration, using the same file/lock as player records. */
    fun loadAudioSettings(): AudioSettings = synchronized(prefs) {
        AudioSettings.fromPreferences(prefs.all)
    }

    /** Settings use the existing asynchronous durability checkpoint; no gameplay is replayed. */
    fun saveAudioSettings(settings: AudioSettings): AudioSettings {
        val normalized = settings.normalized()
        val changed = synchronized(prefs) {
            val saved = prefs.all
            if (normalized.savedValues().all { (key, value) -> saved[key] == value }) {
                false
            } else {
                prefs.edit {
                    putBoolean(AudioSettings.MUSIC_ENABLED, normalized.musicEnabled)
                    putFloat(AudioSettings.MUSIC_VOLUME, normalized.musicVolume)
                    putBoolean(AudioSettings.SFX_ENABLED, normalized.sfxEnabled)
                    putFloat(AudioSettings.SFX_VOLUME, normalized.sfxVolume)
                    putBoolean(AudioSettings.HAPTICS_ENABLED, normalized.hapticsEnabled)
                }
                true
            }
        }
        if (changed) completionPersistence.checkpoint()
        return normalized
    }

    private var cachedHistorySource: Any? = null
    private var cachedHistoryHadKey = false
    private var cachedHistory: SessionHistory? = null

    fun load(): PersonalRecords = synchronized(prefs) {
        val records = recordsFrom(prefs.all)
        val reconciled = reconcileAchievements(records)
        if (reconciled.unlocked != records.achievements.unlocked) {
            prefs.edit { putStringSet(UNLOCKED_ACHIEVEMENT_IDS, reconciled.unlocked.map { it.name }.toSet()) }
        }
        records.copy(achievements = reconciled)
    }

    private fun recordsFrom(saved: Map<String, *>): PersonalRecords {
        val history = historyFrom(saved)
        return PersonalRecords(
            // Keep the original keys: an upgrade must not reset lifetime statistics.
            lifetimeBestMs = saved.nonNegativeInt("best"),
            lifetimeReactions = saved.nonNegativeInt("attempts"),
            modeBestMs = GameMode.entries.associateWith { saved.nonNegativeInt(it.recordKey) },
            // XP is written as Long. A missing, negative, or wrongly typed value starts at zero.
            totalXp = (saved[TOTAL_XP] as? Long)?.coerceAtLeast(0L) ?: 0L,
            achievements = achievementsFrom(saved),
            history = history,
            // Retained sessions prove a minimum when the new per-mode counter is damaged.
            // Pre-statistics history is absent, so older unknown sessions are not inferred.
            completedByMode = GameMode.entries.associateWith { mode ->
                maxOf(saved.nonNegativeLong(modeCompletionKey(mode)), history.sessions.count { it.mode == mode }.toLong())
            }
        )
    }

    /** Accessed under the shared preferences lock; unchanged history is decoded only once. */
    private fun historyFrom(saved: Map<String, *>): SessionHistory {
        val source = saved[SESSION_HISTORY]
        val hadKey = saved.containsKey(SESSION_HISTORY)
        cachedHistory?.let {
            if (cachedHistorySource == source && cachedHistoryHadKey == hadKey) return it
        }
        val decoded = if (hadKey && source !is String) SessionHistory(hadUnreadableEntries = true)
            else SessionHistoryCodec.decode(source as? String)
        cachedHistorySource = source
        cachedHistoryHadKey = hadKey
        cachedHistory = decoded
        return decoded
    }

    private fun achievementsFrom(saved: Map<String, *>): AchievementState {
        val storedIds = saved[UNLOCKED_ACHIEVEMENT_IDS] as? Set<*> ?: emptySet<Any?>()
        val completed = (saved[COMPLETED_SESSIONS] as? Long)?.coerceAtLeast(0L) ?: 0L
        return AchievementState(
            unlocked = Achievement.entries.filterTo(mutableSetOf()) { it.name in storedIds },
            completedSessions = completed,
            cleanSessionStreak = minOf(saved.nonNegativeInt(CLEAN_SESSION_STREAK).toLong(), completed).toInt()
        )
    }

    private fun reconcileAchievements(records: PersonalRecords): AchievementState =
        Achievements.reconcile(
            records.achievements,
            PlayerProgression.levelProgress(records.totalXp).level,
            records.modeBestMs
        ).state

    /** Replaces any abandoned session; starting a session never awards XP. */
    fun startSession(mode: GameMode): String = synchronized(prefs) {
        val sessionId = UUID.randomUUID().toString()
        prefs.edit {
            putString(ACTIVE_SESSION_ID, sessionId)
            putString(ACTIVE_SESSION_MODE, mode.name)
            putInt(ACTIVE_SESSION_ROUNDS, 0)
            putBoolean(ACTIVE_SESSION_HAD_FALSE_START, false)
            putInt(ACTIVE_SESSION_FALSE_START_COUNT, 0)
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
                remove(ACTIVE_SESSION_HAD_FALSE_START)
                remove(ACTIVE_SESSION_FALSE_START_COUNT)
            }
        }
    }

    /** Startup cleanup invalidates orphan metadata without replaying any progression. */
    fun recoverInterruptedSession(): Boolean = synchronized(prefs) {
        val saved = prefs.all
        val activeKeys = listOf(
            ACTIVE_SESSION_ID, ACTIVE_SESSION_MODE, ACTIVE_SESSION_ROUNDS,
            ACTIVE_SESSION_HAD_FALSE_START, ACTIVE_SESSION_FALSE_START_COUNT
        )
        if (activeKeys.none { saved.containsKey(it) }) return@synchronized false
        prefs.edit { activeKeys.forEach { remove(it) } }
        true
    }

    /** Marks the current session dirty; a false start itself never changes earned progress. */
    fun recordFalseStart(sessionId: String) {
        synchronized(prefs) {
            val saved = prefs.all
            if (sessionId.isBlank() || saved[ACTIVE_SESSION_ID] != sessionId) {
                return@synchronized
            }
            val previousCount = falseStartCountFrom(saved)
            prefs.edit {
                putBoolean(ACTIVE_SESSION_HAD_FALSE_START, true)
                putInt(
                    ACTIVE_SESSION_FALSE_START_COUNT,
                    if (previousCount == Int.MAX_VALUE) Int.MAX_VALUE else previousCount + 1
                )
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
            val newLevel = PlayerProgression.levelProgress(totalXp).level
            val falseStartCount = falseStartCountFrom(saved)
            val achievementUpdate = if (earnedXp != null) Achievements.completeSession(
                previous = reconcileAchievements(previous),
                mode = mode,
                scores = sessionScores,
                // Legacy or malformed metadata cannot establish that a session was clean.
                hadFalseStart = falseStartCount > 0,
                playerLevel = newLevel
            ) else null
            val reward = if (earnedXp != null) SessionReward(
                xp = earnedXp,
                awardedXp = (totalXp - previous.totalXp).toInt(),
                medal = PlayerProgression.medal(checkNotNull(sessionScore)),
                previousLevel = PlayerProgression.levelProgress(previous.totalXp).level,
                newLevel = newLevel,
                newlyUnlocked = checkNotNull(achievementUpdate).newlyUnlocked
            ) else null
            val history = if (reward != null) SessionHistoryCodec.prepend(
                previous.history,
                SessionHistoryEntry(
                    sessionId = sessionId,
                    mode = mode,
                    completedAtMillis = clock().coerceAtLeast(1L),
                    roundScoresMs = sessionScores.toList(),
                    bestTimeMs = checkNotNull(sessionScores.minOrNull()),
                    averageTimeMs = checkNotNull(sessionScore),
                    spreadMs = checkNotNull(sessionScores.maxOrNull()) - checkNotNull(sessionScores.minOrNull()),
                    falseStartCount = falseStartCount,
                    xpEarned = reward.awardedXp,
                    medal = reward.medal
                )
            ) else previous.history
            val completedByMode = if (reward != null) {
                val previousCount = previous.completedByMode[mode] ?: 0L
                previous.completedByMode + (mode to if (previousCount == Long.MAX_VALUE)
                    Long.MAX_VALUE else previousCount + 1)
            } else previous.completedByMode
            val updated = previous.copy(
                lifetimeBestMs = lifetimeBest,
                lifetimeReactions = if (previous.lifetimeReactions == Int.MAX_VALUE)
                    Int.MAX_VALUE else previous.lifetimeReactions + 1,
                modeBestMs = if (isNewRecord)
                    previous.modeBestMs + (mode to checkNotNull(sessionScore))
                else previous.modeBestMs,
                totalXp = totalXp,
                achievements = achievementUpdate?.state ?: previous.achievements,
                history = history,
                completedByMode = completedByMode
            )
            val encodedHistory = if (reward != null) SessionHistoryCodec.encode(updated.history.sessions) else null

            // Completion consumes the token in the same edit as its record and reward.
            prefs.edit {
                putInt("best", updated.lifetimeBestMs)
                putInt("attempts", updated.lifetimeReactions)
                if (isNewRecord) putInt(mode.recordKey, checkNotNull(sessionScore))
                if (reward != null) {
                    putLong(TOTAL_XP, totalXp)
                    putStringSet(
                        UNLOCKED_ACHIEVEMENT_IDS,
                        updated.achievements.unlocked.map { it.name }.toSet()
                    )
                    putLong(COMPLETED_SESSIONS, updated.achievements.completedSessions)
                    putInt(CLEAN_SESSION_STREAK, updated.achievements.cleanSessionStreak)
                    putString(SESSION_HISTORY, checkNotNull(encodedHistory))
                    updated.completedByMode.forEach { (completedMode, count) ->
                        val counterKey = modeCompletionKey(completedMode)
                        if (completedMode == mode || count > 0L && saved[counterKey] != count) {
                            // Preserve recovered lower bounds before later sessions prune their evidence.
                            putLong(counterKey, count)
                        }
                    }
                    remove(ACTIVE_SESSION_ID)
                    remove(ACTIVE_SESSION_MODE)
                    remove(ACTIVE_SESSION_ROUNDS)
                    remove(ACTIVE_SESSION_HAD_FALSE_START)
                    remove(ACTIVE_SESSION_FALSE_START_COUNT)
                } else {
                    putInt(ACTIVE_SESSION_ROUNDS, sessionScores.size)
                }
            }
            if (encodedHistory != null) {
                cachedHistorySource = encodedHistory
                cachedHistoryHadKey = true
                cachedHistory = updated.history
            }
            if (reward != null) completionPersistence.checkpoint()

            ReactionUpdate(updated, isNewRecord, reward)
        }
    }

    private fun Map<String, *>.nonNegativeInt(key: String): Int =
        (this[key] as? Int)?.coerceAtLeast(0) ?: 0

    private fun Map<String, *>.nonNegativeLong(key: String): Long =
        (this[key] as? Long)?.coerceAtLeast(0L) ?: 0L

    private fun modeCompletionKey(mode: GameMode): String = when (mode) {
        GameMode.QUICK_DRAW -> "completed_sessions_quick_draw"
        GameMode.CLASSIC_RUSH -> "completed_sessions_classic_rush"
        GameMode.ENDURANCE_RUSH -> "completed_sessions_endurance_rush"
    }

    private fun falseStartCountFrom(saved: Map<String, *>): Int {
        val count = saved[ACTIVE_SESSION_FALSE_START_COUNT] as? Int
        return if (count == 0 && saved[ACTIVE_SESSION_HAD_FALSE_START] == false) 0
        else (count ?: 1).coerceAtLeast(1)
    }

    private companion object {
        const val TOTAL_XP = "total_xp"
        const val UNLOCKED_ACHIEVEMENT_IDS = "unlocked_achievement_ids"
        const val COMPLETED_SESSIONS = "completed_sessions"
        const val CLEAN_SESSION_STREAK = "clean_session_streak"
        const val SESSION_HISTORY = "session_history"
        const val ACTIVE_SESSION_ID = "active_session_id"
        const val ACTIVE_SESSION_MODE = "active_session_mode"
        const val ACTIVE_SESSION_ROUNDS = "active_session_rounds"
        const val ACTIVE_SESSION_HAD_FALSE_START = "active_session_had_false_start"
        const val ACTIVE_SESSION_FALSE_START_COUNT = "active_session_false_start_count"
    }
}
