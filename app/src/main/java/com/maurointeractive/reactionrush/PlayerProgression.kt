package com.maurointeractive.reactionrush

import kotlin.math.roundToInt

enum class PerformanceMedal(val title: String) {
    ELITE("ELITE"),
    PLATINUM("PLATINUM"),
    GOLD("GOLD"),
    SILVER("SILVER"),
    BRONZE("BRONZE"),
    UNRANKED("UNRANKED")
}

data class XpReward(
    val baseXp: Int,
    val speedBonusXp: Int,
    val consistencyBonusXp: Int
) {
    val totalXp: Int
        get() = baseXp + speedBonusXp + consistencyBonusXp
}

data class LevelProgress(
    val level: Int,
    val totalXp: Long,
    val xpIntoLevel: Long,
    val xpToNextLevel: Long,
    val isMaxLevel: Boolean
) {
    val fraction: Float
        get() = when {
            isMaxLevel -> 1f
            xpToNextLevel <= 0L -> 0f
            else -> (xpIntoLevel.toDouble() / xpToNextLevel).toFloat().coerceIn(0f, 1f)
        }
}

object PlayerProgression {
    // The next level's threshold exceeds Long.MAX_VALUE.
    private const val MAX_LEVEL = 429_496_730

    fun sessionScore(mode: GameMode, scores: List<Int>): Int {
        require(scores.size == mode.rounds) { "A completed session is required." }
        require(scores.all { it > 0 }) { "Reaction times must be positive." }
        return scores.average().roundToInt()
    }

    fun medal(scoreMs: Int): PerformanceMedal {
        require(scoreMs > 0) { "Reaction time must be positive." }
        return when {
            scoreMs < 200 -> PerformanceMedal.ELITE
            scoreMs < 250 -> PerformanceMedal.PLATINUM
            scoreMs < 300 -> PerformanceMedal.GOLD
            scoreMs < 350 -> PerformanceMedal.SILVER
            scoreMs < 450 -> PerformanceMedal.BRONZE
            else -> PerformanceMedal.UNRANKED
        }
    }

    fun reward(mode: GameMode, scores: List<Int>): XpReward {
        val score = sessionScore(mode, scores)
        val base = when (mode) {
            GameMode.QUICK_DRAW -> 10
            GameMode.CLASSIC_RUSH -> 40
            GameMode.ENDURANCE_RUSH -> 100
        }
        val speedBonus = base * (450L - score).coerceIn(0L, 300L) / 600L
        val consistencyBonus = if (mode.rounds > 1) {
            val spread = checkNotNull(scores.maxOrNull()).toLong() -
                checkNotNull(scores.minOrNull()).toLong()
            base * (100L - spread).coerceIn(0L, 100L) / 400L
        } else 0L

        return XpReward(base, speedBonus.toInt(), consistencyBonus.toInt())
    }

    fun levelProgress(totalXp: Long): LevelProgress {
        val safeTotal = totalXp.coerceAtLeast(0L)
        var lower = 1
        var upper = MAX_LEVEL
        while (lower < upper) {
            val middle = lower + (upper - lower + 1) / 2
            if (levelThreshold(middle) <= safeTotal) lower = middle
            else upper = middle - 1
        }

        val isMaxLevel = lower == MAX_LEVEL
        return LevelProgress(
            level = lower,
            totalXp = safeTotal,
            xpIntoLevel = safeTotal - levelThreshold(lower),
            // This is the full XP requirement for the next level, not XP remaining.
            xpToNextLevel = if (isMaxLevel) 0L else 100L * lower,
            isMaxLevel = isMaxLevel
        )
    }

    fun addXp(totalXp: Long, rewardXp: Int): Long {
        require(rewardXp >= 0) { "An XP reward must not be negative." }
        val safeTotal = totalXp.coerceAtLeast(0L)
        return if (safeTotal > Long.MAX_VALUE - rewardXp) Long.MAX_VALUE
            else safeTotal + rewardXp
    }

    private fun levelThreshold(level: Int): Long = 50L * level * (level - 1L)
}
