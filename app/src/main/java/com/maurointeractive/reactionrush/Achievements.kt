package com.maurointeractive.reactionrush

enum class Achievement(val title: String, val description: String) {
    GOLD_REFLEX("Gold Reflex", "Earn a Gold medal or better in a completed session."),
    PLATINUM_REFLEX("Platinum Reflex", "Earn a Platinum medal or better in a completed session."),
    ELITE_REFLEX("Elite Reflex", "Earn an Elite medal in a completed session."),
    STEADY_HANDS("Steady Hands", "Complete Classic Rush with a reaction spread of 40 ms or less."),
    IRON_FOCUS("Iron Focus", "Complete Endurance Rush with a reaction spread of 60 ms or less."),
    CLEAN_SWEEP("Clean Sweep", "Complete 5 sessions in a row without a false start."),
    GETTING_STARTED("Getting Started", "Complete 10 sessions."),
    DEDICATED("Dedicated", "Complete 50 sessions."),
    VETERAN("Veteran", "Complete 100 sessions."),
    RISING_STAR("Rising Star", "Reach level 5."),
    REFLEX_MASTER("Reflex Master", "Reach level 10."),
    LEGEND("Legend", "Reach level 25.")
}

data class AchievementState(
    val unlocked: Set<Achievement> = emptySet(),
    val completedSessions: Long = 0,
    val cleanSessionStreak: Int = 0
)

data class AchievementUpdate(
    val state: AchievementState,
    val newlyUnlocked: Set<Achievement>
)

data class AchievementProgress(val current: Long, val target: Long)

object Achievements {
    fun completeSession(
        previous: AchievementState,
        mode: GameMode,
        scores: List<Int>,
        hadFalseStart: Boolean,
        playerLevel: Int
    ): AchievementUpdate {
        val score = PlayerProgression.sessionScore(mode, scores)
        val normalized = normalize(previous)
        val completed = if (normalized.completedSessions == Long.MAX_VALUE) Long.MAX_VALUE
            else normalized.completedSessions + 1
        val cleanStreak = when {
            hadFalseStart -> 0
            normalized.cleanSessionStreak == Int.MAX_VALUE -> Int.MAX_VALUE
            else -> normalized.cleanSessionStreak + 1
        }
        val updated = normalized.copy(
            completedSessions = completed,
            cleanSessionStreak = cleanStreak
        )
        val earned = measurableAchievements(updated, playerLevel).toMutableSet()
        addReflexAchievements(score, earned)
        val spread = checkNotNull(scores.maxOrNull()).toLong() -
            checkNotNull(scores.minOrNull()).toLong()
        if (mode == GameMode.CLASSIC_RUSH && spread <= 40L) earned += Achievement.STEADY_HANDS
        if (mode == GameMode.ENDURANCE_RUSH && spread <= 60L) earned += Achievement.IRON_FOCUS
        return unlock(updated, earned)
    }

    /** Recovers achievements from known saved facts without inventing past sessions or spreads. */
    fun reconcile(
        previous: AchievementState,
        playerLevel: Int,
        modeRecords: Map<GameMode, Int>
    ): AchievementUpdate {
        val normalized = normalize(previous)
        val earned = measurableAchievements(normalized, playerLevel).toMutableSet()
        modeRecords.values.filter { it > 0 }.minOrNull()?.let { addReflexAchievements(it, earned) }
        return unlock(normalized, earned)
    }

    fun progress(
        achievement: Achievement,
        state: AchievementState,
        playerLevel: Int
    ): AchievementProgress? {
        val normalized = normalize(state)
        val (current, target) = when (achievement) {
            Achievement.CLEAN_SWEEP -> normalized.cleanSessionStreak.toLong() to 5L
            Achievement.GETTING_STARTED -> normalized.completedSessions to 10L
            Achievement.DEDICATED -> normalized.completedSessions to 50L
            Achievement.VETERAN -> normalized.completedSessions to 100L
            Achievement.RISING_STAR -> playerLevel.toLong() to 5L
            Achievement.REFLEX_MASTER -> playerLevel.toLong() to 10L
            Achievement.LEGEND -> playerLevel.toLong() to 25L
            else -> return null
        }
        return AchievementProgress(
            current = if (achievement in normalized.unlocked) target else current.coerceIn(0L, target),
            target = target
        )
    }

    private fun normalize(state: AchievementState): AchievementState {
        val completed = state.completedSessions.coerceAtLeast(0L)
        return state.copy(
            completedSessions = completed,
            cleanSessionStreak = state.cleanSessionStreak.coerceAtLeast(0)
                .toLong().coerceAtMost(completed).toInt()
        )
    }

    private fun measurableAchievements(state: AchievementState, playerLevel: Int): Set<Achievement> =
        Achievement.entries.filterTo(linkedSetOf()) { achievement ->
            progress(achievement, state, playerLevel)?.let { it.current == it.target } == true
        }

    private fun addReflexAchievements(score: Int, earned: MutableSet<Achievement>) {
        when (PlayerProgression.medal(score)) {
            PerformanceMedal.ELITE -> earned += setOf(
                Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX, Achievement.ELITE_REFLEX
            )
            PerformanceMedal.PLATINUM -> earned += setOf(Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX)
            PerformanceMedal.GOLD -> earned += Achievement.GOLD_REFLEX
            else -> Unit
        }
    }

    private fun unlock(state: AchievementState, earned: Set<Achievement>): AchievementUpdate {
        // Permanent higher-tier IDs remain evidence even if their numeric history was lost.
        val implied = (state.unlocked + earned).toMutableSet()
        if (Achievement.ELITE_REFLEX in implied) implied += Achievement.PLATINUM_REFLEX
        if (Achievement.PLATINUM_REFLEX in implied) implied += Achievement.GOLD_REFLEX
        if (Achievement.LEGEND in implied) implied += Achievement.REFLEX_MASTER
        if (Achievement.REFLEX_MASTER in implied) implied += Achievement.RISING_STAR
        if (Achievement.VETERAN in implied) implied += Achievement.DEDICATED
        if (Achievement.DEDICATED in implied) implied += Achievement.GETTING_STARTED
        val unlocked = Achievement.entries.filterTo(linkedSetOf()) { it in implied }
        return AchievementUpdate(state.copy(unlocked = unlocked), unlocked - state.unlocked)
    }
}
