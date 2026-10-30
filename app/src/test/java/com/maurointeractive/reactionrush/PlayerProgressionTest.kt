package com.maurointeractive.reactionrush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProgressionTest {
    @Test
    fun sessionScoresUseEachModesFullRoundedAverage() {
        assertEquals(251, PlayerProgression.sessionScore(GameMode.QUICK_DRAW, listOf(251)))
        assertEquals(300, PlayerProgression.sessionScore(
            GameMode.CLASSIC_RUSH, listOf(100, 200, 300, 400, 502)
        ))
        assertEquals(201, PlayerProgression.sessionScore(
            GameMode.ENDURANCE_RUSH, List(9) { 200 } + 205
        ))
    }

    @Test
    fun scoresAndRewardsRejectIncompleteOverlongAndInvalidSessions() {
        GameMode.entries.forEach { mode ->
            val invalidSessions = listOf(
                emptyList(),
                List(mode.rounds - 1) { 200 },
                List(mode.rounds + 1) { 200 },
                List(mode.rounds - 1) { 200 } + 0,
                List(mode.rounds - 1) { 200 } + -1
            )
            invalidSessions.forEach { scores ->
                assertThrows(IllegalArgumentException::class.java) {
                    PlayerProgression.sessionScore(mode, scores)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    PlayerProgression.reward(mode, scores)
                }
            }
        }
    }

    @Test
    fun medalsIncludeEveryExactBoundary() {
        val cases = mapOf(
            1 to PerformanceMedal.ELITE,
            199 to PerformanceMedal.ELITE,
            200 to PerformanceMedal.PLATINUM,
            249 to PerformanceMedal.PLATINUM,
            250 to PerformanceMedal.GOLD,
            299 to PerformanceMedal.GOLD,
            300 to PerformanceMedal.SILVER,
            349 to PerformanceMedal.SILVER,
            350 to PerformanceMedal.BRONZE,
            449 to PerformanceMedal.BRONZE,
            450 to PerformanceMedal.UNRANKED,
            Int.MAX_VALUE to PerformanceMedal.UNRANKED
        )
        cases.forEach { (score, medal) -> assertEquals(medal, PlayerProgression.medal(score)) }
        assertThrows(IllegalArgumentException::class.java) { PlayerProgression.medal(0) }
        assertThrows(IllegalArgumentException::class.java) { PlayerProgression.medal(-1) }
    }

    @Test
    fun baseRewardsApplyToEveryModeEvenWhenNoBonusesAreEarned() {
        val quick = PlayerProgression.reward(GameMode.QUICK_DRAW, listOf(450))
        val classic = PlayerProgression.reward(GameMode.CLASSIC_RUSH, listOf(400, 450, 450, 450, 500))
        val endurance = PlayerProgression.reward(GameMode.ENDURANCE_RUSH, List(8) { 450 } + listOf(400, 500))

        assertEquals(XpReward(10, 0, 0), quick)
        assertEquals(XpReward(40, 0, 0), classic)
        assertEquals(XpReward(100, 0, 0), endurance)
        assertEquals(10, quick.totalXp)
        assertEquals(40, classic.totalXp)
        assertEquals(100, endurance.totalXp)
    }

    @Test
    fun fastestConsistentSessionsReachEachModesBonusCaps() {
        val cases = mapOf(
            GameMode.QUICK_DRAW to XpReward(10, 5, 0),
            GameMode.CLASSIC_RUSH to XpReward(40, 20, 10),
            GameMode.ENDURANCE_RUSH to XpReward(100, 50, 25)
        )
        cases.forEach { (mode, expected) ->
            assertEquals(expected, PlayerProgression.reward(mode, List(mode.rounds) { 150 }))
            assertEquals(expected, PlayerProgression.reward(mode, List(mode.rounds) { 1 }))
        }
        assertEquals(15, cases.getValue(GameMode.QUICK_DRAW).totalXp)
        assertEquals(70, cases.getValue(GameMode.CLASSIC_RUSH).totalXp)
        assertEquals(175, cases.getValue(GameMode.ENDURANCE_RUSH).totalXp)
    }

    @Test
    fun speedBonusesUseIntegerFloorAndRoundedSessionScore() {
        assertEquals(4, PlayerProgression.reward(GameMode.QUICK_DRAW, listOf(151)).speedBonusXp)
        assertEquals(0, PlayerProgression.reward(GameMode.QUICK_DRAW, listOf(449)).speedBonusXp)
        assertEquals(0, PlayerProgression.reward(GameMode.QUICK_DRAW, listOf(450)).speedBonusXp)
        assertEquals(0, PlayerProgression.reward(GameMode.QUICK_DRAW, listOf(Int.MAX_VALUE)).speedBonusXp)
        assertEquals(19, PlayerProgression.reward(GameMode.CLASSIC_RUSH, List(5) { 151 }).speedBonusXp)
        // 150.5 rounds to 151, so this earns floor(100 * 299 / 600) = 49 XP.
        assertEquals(49, PlayerProgression.reward(
            GameMode.ENDURANCE_RUSH, List(5) { 150 } + List(5) { 151 }
        ).speedBonusXp)
    }

    @Test
    fun consistencyUsesTheWholeSpreadAndDoesNotApplyToQuickDraw() {
        assertEquals(0, PlayerProgression.reward(GameMode.QUICK_DRAW, listOf(200)).consistencyBonusXp)
        assertEquals(10, PlayerProgression.reward(GameMode.CLASSIC_RUSH, List(5) { 500 }).consistencyBonusXp)
        assertEquals(9, PlayerProgression.reward(
            GameMode.CLASSIC_RUSH, listOf(200, 200, 200, 200, 201)
        ).consistencyBonusXp)
        assertEquals(5, PlayerProgression.reward(
            GameMode.CLASSIC_RUSH, listOf(200, 200, 200, 200, 250)
        ).consistencyBonusXp)
        assertEquals(12, PlayerProgression.reward(
            GameMode.ENDURANCE_RUSH, List(9) { 200 } + 250
        ).consistencyBonusXp)
        listOf(100, 101, 1_000).forEach { spread ->
            assertEquals(0, PlayerProgression.reward(
                GameMode.CLASSIC_RUSH, List(4) { 200 } + (200 + spread)
            ).consistencyBonusXp)
        }
    }

    @Test
    fun extremePositiveTimesCannotOverflowScoringOrRewardArithmetic() {
        GameMode.entries.forEach { mode ->
            val scores = List(mode.rounds) { Int.MAX_VALUE }
            assertEquals(Int.MAX_VALUE, PlayerProgression.sessionScore(mode, scores))
            val reward = PlayerProgression.reward(mode, scores)
            assertEquals(0, reward.speedBonusXp)
            assertEquals(if (mode.rounds == 1) 0 else reward.baseXp / 4, reward.consistencyBonusXp)
        }
        val mixed = List(9) { Int.MAX_VALUE } + 1
        assertEquals(1_932_735_282, PlayerProgression.sessionScore(GameMode.ENDURANCE_RUSH, mixed))
        assertEquals(XpReward(100, 0, 0), PlayerProgression.reward(GameMode.ENDURANCE_RUSH, mixed))
    }

    @Test
    fun rewardsStayWithinCapsForAllModesAndRepresentativeScores() {
        GameMode.entries.forEach { mode ->
            listOf(1, 149, 150, 151, 199, 250, 350, 449, 450, 1_000, Int.MAX_VALUE).forEach { score ->
                val reward = PlayerProgression.reward(mode, List(mode.rounds) { score })
                assertTrue(reward.speedBonusXp in 0..reward.baseXp / 2)
                assertTrue(reward.consistencyBonusXp in 0..reward.baseXp / 4)
                assertEquals(reward.baseXp + reward.speedBonusXp + reward.consistencyBonusXp, reward.totalXp)
            }
        }
    }

    @Test
    fun newPlayersAndNegativeTotalsStartAtLevelOne() {
        listOf(Long.MIN_VALUE, -1L, 0L).forEach { total ->
            val progress = PlayerProgression.levelProgress(total)
            assertEquals(LevelProgress(1, 0L, 0L, 100L, false), progress)
            assertEquals(0f, progress.fraction, 0f)
        }
    }

    @Test
    fun levelsChangeAtExactIntegerThresholds() {
        val cases = listOf(
            0L to LevelProgress(1, 0L, 0L, 100L, false),
            99L to LevelProgress(1, 99L, 99L, 100L, false),
            100L to LevelProgress(2, 100L, 0L, 200L, false),
            299L to LevelProgress(2, 299L, 199L, 200L, false),
            300L to LevelProgress(3, 300L, 0L, 300L, false),
            599L to LevelProgress(3, 599L, 299L, 300L, false),
            600L to LevelProgress(4, 600L, 0L, 400L, false)
        )
        cases.forEach { (total, expected) -> assertEquals(expected, PlayerProgression.levelProgress(total)) }
        assertEquals(0.5f, PlayerProgression.levelProgress(200L).fraction, 0f)
    }

    @Test
    fun largeLevelThresholdsRemainExactWithoutFloatingPointEstimation() {
        listOf(2, 10, 1_000, 1_000_000, 400_000_000, 429_496_729).forEach { level ->
            val threshold = 50L * level * (level - 1L)
            assertEquals(level - 1, PlayerProgression.levelProgress(threshold - 1).level)
            val atThreshold = PlayerProgression.levelProgress(threshold)
            assertEquals(level, atThreshold.level)
            assertEquals(0L, atThreshold.xpIntoLevel)
            assertEquals(100L * level, atThreshold.xpToNextLevel)
            assertFalse(atThreshold.isMaxLevel)
            assertEquals(level, PlayerProgression.levelProgress(threshold + 1).level)
        }
    }

    @Test
    fun maximumRepresentableLevelHasFiniteCompleteProgress() {
        val maximumLevelThreshold = 9_223_372_032_559_808_500L
        val before = PlayerProgression.levelProgress(maximumLevelThreshold - 1)
        assertEquals(429_496_729, before.level)
        assertFalse(before.isMaxLevel)
        listOf(maximumLevelThreshold, maximumLevelThreshold + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE).forEach { total ->
            val progress = PlayerProgression.levelProgress(total)
            assertEquals(429_496_730, progress.level)
            assertEquals(total, progress.totalXp)
            assertEquals(total - maximumLevelThreshold, progress.xpIntoLevel)
            assertEquals(0L, progress.xpToNextLevel)
            assertTrue(progress.isMaxLevel)
            assertEquals(1f, progress.fraction, 0f)
        }
    }

    @Test
    fun levelProgressIsMonotonicAndFractionsStayFinite() {
        val totals = listOf(
            Long.MIN_VALUE, -1L, 0L, 1L, 99L, 100L, 299L, 300L,
            10_000L, 1_000_000_000L, Long.MAX_VALUE / 2,
            9_223_372_032_559_808_499L, 9_223_372_032_559_808_500L, Long.MAX_VALUE
        )
        var previousLevel = 1
        totals.forEach { total ->
            val progress = PlayerProgression.levelProgress(total)
            assertTrue(progress.level >= previousLevel)
            assertTrue(progress.fraction.isFinite())
            assertTrue(progress.fraction in 0f..1f)
            assertTrue(progress.xpIntoLevel >= 0L)
            assertTrue(progress.xpToNextLevel >= 0L)
            previousLevel = progress.level
        }
    }

    @Test
    fun addingXpClampsCorruptNegativeTotalsAndSaturatesAtLongMaximum() {
        assertEquals(10L, PlayerProgression.addXp(-1L, 10))
        assertEquals(0L, PlayerProgression.addXp(Long.MIN_VALUE, 0))
        assertEquals(150L, PlayerProgression.addXp(100L, 50))
        assertEquals(Long.MAX_VALUE, PlayerProgression.addXp(Long.MAX_VALUE, 0))
        assertEquals(Long.MAX_VALUE, PlayerProgression.addXp(Long.MAX_VALUE, 1))
        assertEquals(Long.MAX_VALUE, PlayerProgression.addXp(Long.MAX_VALUE - 10, 10))
        assertEquals(Long.MAX_VALUE, PlayerProgression.addXp(Long.MAX_VALUE - 10, 11))
        assertEquals(Long.MAX_VALUE, PlayerProgression.addXp(Long.MAX_VALUE - 1, Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { PlayerProgression.addXp(100L, -1) }
    }
}
