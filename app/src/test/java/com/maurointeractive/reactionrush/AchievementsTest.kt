package com.maurointeractive.reactionrush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {
    @Test
    fun achievementIdsAndMetadataAreStableAndComplete() {
        assertEquals(
            listOf(
                "GOLD_REFLEX", "PLATINUM_REFLEX", "ELITE_REFLEX", "STEADY_HANDS", "IRON_FOCUS",
                "CLEAN_SWEEP", "GETTING_STARTED", "DEDICATED", "VETERAN", "RISING_STAR",
                "REFLEX_MASTER", "LEGEND"
            ),
            Achievement.entries.map { it.name }
        )
        Achievement.entries.forEach {
            assertTrue(it.title.isNotBlank())
            assertTrue(it.description.isNotBlank())
        }
    }

    @Test
    fun medalAchievementsIncludeEveryThresholdAndAllLowerTiers() {
        val gold = setOf(Achievement.GOLD_REFLEX)
        val platinum = gold + Achievement.PLATINUM_REFLEX
        val elite = platinum + Achievement.ELITE_REFLEX
        val cases = mapOf(
            1 to elite, 198 to elite, 199 to elite,
            200 to platinum, 201 to platinum, 248 to platinum, 249 to platinum,
            250 to gold, 251 to gold, 298 to gold, 299 to gold,
            300 to emptySet(), 301 to emptySet(), Int.MAX_VALUE to emptySet()
        )
        cases.forEach { (score, expected) ->
            val update = finish(GameMode.QUICK_DRAW, listOf(score))
            assertEquals(expected, update.state.unlocked)
            assertEquals(expected, update.newlyUnlocked)
        }
    }

    @Test
    fun rushMedalAchievementsUseTheRoundedAverageRatherThanFastestReaction() {
        val classic = finish(GameMode.CLASSIC_RUSH, listOf(100, 350, 350, 350, 350))
        assertFalse(Achievement.GOLD_REFLEX in classic.state.unlocked)
        val endurance = finish(GameMode.ENDURANCE_RUSH, List(5) { 199 } + List(5) { 200 })
        assertTrue(Achievement.PLATINUM_REFLEX in endurance.state.unlocked)
        assertFalse(Achievement.ELITE_REFLEX in endurance.state.unlocked)
    }

    @Test
    fun classicSpreadAchievementIncludesFortyButNotFortyOneMilliseconds() {
        listOf(39, 40, 41).forEach { spread ->
            val update = finish(GameMode.CLASSIC_RUSH, List(4) { 500 } + (500 + spread))
            assertEquals(spread <= 40, Achievement.STEADY_HANDS in update.state.unlocked)
            assertFalse(Achievement.IRON_FOCUS in update.state.unlocked)
        }
    }

    @Test
    fun enduranceSpreadAchievementIncludesSixtyButNotSixtyOneMilliseconds() {
        listOf(59, 60, 61).forEach { spread ->
            val update = finish(GameMode.ENDURANCE_RUSH, List(9) { 500 } + (500 + spread))
            assertEquals(spread <= 60, Achievement.IRON_FOCUS in update.state.unlocked)
            assertFalse(Achievement.STEADY_HANDS in update.state.unlocked)
        }
    }

    @Test
    fun spreadAchievementsAreModeSpecificAndDoNotRequireACleanSession() {
        val quick = finish(GameMode.QUICK_DRAW, listOf(500))
        assertFalse(Achievement.STEADY_HANDS in quick.state.unlocked)
        assertFalse(Achievement.IRON_FOCUS in quick.state.unlocked)
        assertTrue(Achievement.STEADY_HANDS in finish(
            GameMode.CLASSIC_RUSH, List(5) { 500 }, hadFalseStart = true
        ).state.unlocked)
        assertTrue(Achievement.IRON_FOCUS in finish(
            GameMode.ENDURANCE_RUSH, List(10) { 500 }, hadFalseStart = true
        ).state.unlocked)
    }

    @Test
    fun extremeValidTimesCannotOverflowSpreadIntoAnUnlock() {
        listOf(GameMode.CLASSIC_RUSH, GameMode.ENDURANCE_RUSH).forEach { mode ->
            val update = finish(mode, List(mode.rounds - 1) { Int.MAX_VALUE } + 1)
            assertFalse(Achievement.STEADY_HANDS in update.state.unlocked)
            assertFalse(Achievement.IRON_FOCUS in update.state.unlocked)
        }
    }

    @Test
    fun invalidSessionsCannotProduceAnyAchievementUpdate() {
        val previous = AchievementState(setOf(Achievement.GOLD_REFLEX), 9, 4)
        GameMode.entries.forEach { mode ->
            listOf(
                emptyList(), List(mode.rounds - 1) { 500 }, List(mode.rounds + 1) { 500 },
                List(mode.rounds - 1) { 500 } + 0, List(mode.rounds - 1) { 500 } + -1
            ).forEach { scores ->
                assertThrows(IllegalArgumentException::class.java) {
                    Achievements.completeSession(previous, mode, scores, false, 25)
                }
            }
        }
        assertEquals(AchievementState(setOf(Achievement.GOLD_REFLEX), 9, 4), previous)
    }

    @Test
    fun countAchievementsUnlockAtTenFiftyAndOneHundredSessions() {
        val thresholds = mapOf(
            Achievement.GETTING_STARTED to 10L,
            Achievement.DEDICATED to 50L,
            Achievement.VETERAN to 100L
        )
        thresholds.forEach { (achievement, threshold) ->
            listOf(threshold - 1, threshold, threshold + 1).forEach { completed ->
                val update = finish(
                    GameMode.QUICK_DRAW, listOf(500),
                    previous = AchievementState(completedSessions = completed - 1), hadFalseStart = true
                )
                assertEquals(completed, update.state.completedSessions)
                assertEquals(completed >= threshold, achievement in update.state.unlocked)
            }
        }
    }

    @Test
    fun levelAchievementsUnlockAtFiveTenAndTwentyFive() {
        val thresholds = mapOf(
            Achievement.RISING_STAR to 5,
            Achievement.REFLEX_MASTER to 10,
            Achievement.LEGEND to 25
        )
        thresholds.forEach { (achievement, threshold) ->
            listOf(threshold - 1, threshold, threshold + 1).forEach { level ->
                val update = finish(GameMode.QUICK_DRAW, listOf(500), playerLevel = level)
                assertEquals(level >= threshold, achievement in update.state.unlocked)
            }
        }
    }

    @Test
    fun fiveCleanCompletedSessionsUnlockCleanSweepAndDirtyCompletionResetsStreak() {
        var state = AchievementState()
        repeat(4) {
            val update = finish(GameMode.QUICK_DRAW, listOf(500), previous = state)
            state = update.state
            assertFalse(Achievement.CLEAN_SWEEP in state.unlocked)
        }
        assertEquals(4, state.cleanSessionStreak)
        val fifth = finish(GameMode.QUICK_DRAW, listOf(500), previous = state)
        assertEquals(5, fifth.state.cleanSessionStreak)
        assertEquals(setOf(Achievement.CLEAN_SWEEP), fifth.newlyUnlocked)
        val sixth = finish(GameMode.QUICK_DRAW, listOf(500), previous = fifth.state)
        assertEquals(6, sixth.state.cleanSessionStreak)
        assertTrue(sixth.newlyUnlocked.isEmpty())
        val dirty = finish(GameMode.QUICK_DRAW, listOf(500), previous = sixth.state, hadFalseStart = true)
        assertEquals(7L, dirty.state.completedSessions)
        assertEquals(0, dirty.state.cleanSessionStreak)
        assertTrue(Achievement.CLEAN_SWEEP in dirty.state.unlocked)
        assertTrue(dirty.newlyUnlocked.isEmpty())
    }

    @Test
    fun falseStartsBeforeCleanSweepRequireFiveNewCleanCompletions() {
        val dirty = finish(
            GameMode.QUICK_DRAW, listOf(500),
            previous = AchievementState(completedSessions = 4, cleanSessionStreak = 4), hadFalseStart = true
        )
        assertEquals(0, dirty.state.cleanSessionStreak)
        assertFalse(Achievement.CLEAN_SWEEP in dirty.state.unlocked)
        val next = finish(GameMode.QUICK_DRAW, listOf(500), previous = dirty.state)
        assertEquals(1, next.state.cleanSessionStreak)
        assertFalse(Achievement.CLEAN_SWEEP in next.state.unlocked)
    }

    @Test
    fun abandonedSessionsDoNotChangeCountersBecauseNoCompletionIsSubmitted() {
        val previous = AchievementState(completedSessions = 4, cleanSessionStreak = 4)
        val reloaded = Achievements.reconcile(previous, 1, emptyMap())
        assertEquals(previous, reloaded.state)
        assertTrue(reloaded.newlyUnlocked.isEmpty())
        val nextCompletion = finish(GameMode.QUICK_DRAW, listOf(500), previous = reloaded.state)
        assertEquals(5L, nextCompletion.state.completedSessions)
        assertTrue(Achievement.CLEAN_SWEEP in nextCompletion.newlyUnlocked)
    }

    @Test
    fun countersSaturateAndImpossibleStateValuesAreNormalized() {
        val capped = finish(
            GameMode.QUICK_DRAW, listOf(500),
            previous = AchievementState(completedSessions = Long.MAX_VALUE, cleanSessionStreak = Int.MAX_VALUE)
        )
        assertEquals(Long.MAX_VALUE, capped.state.completedSessions)
        assertEquals(Int.MAX_VALUE, capped.state.cleanSessionStreak)
        val dirty = finish(GameMode.QUICK_DRAW, listOf(500), previous = capped.state, hadFalseStart = true)
        assertEquals(Long.MAX_VALUE, dirty.state.completedSessions)
        assertEquals(0, dirty.state.cleanSessionStreak)

        val negative = finish(
            GameMode.QUICK_DRAW, listOf(500),
            previous = AchievementState(completedSessions = Long.MIN_VALUE, cleanSessionStreak = Int.MIN_VALUE)
        )
        assertEquals(1L, negative.state.completedSessions)
        assertEquals(1, negative.state.cleanSessionStreak)
        val impossibleStreak = finish(
            GameMode.QUICK_DRAW, listOf(500),
            previous = AchievementState(completedSessions = 2, cleanSessionStreak = Int.MAX_VALUE)
        )
        assertEquals(3L, impossibleStreak.state.completedSessions)
        assertEquals(3, impossibleStreak.state.cleanSessionStreak)
        assertFalse(Achievement.CLEAN_SWEEP in impossibleStreak.state.unlocked)
    }

    @Test
    fun migrationRecoversOnlyKnownMedalAndLevelFacts() {
        val update = Achievements.reconcile(
            AchievementState(), playerLevel = 10,
            modeRecords = mapOf(GameMode.QUICK_DRAW to 199, GameMode.CLASSIC_RUSH to 250, GameMode.ENDURANCE_RUSH to 350)
        )
        assertEquals(
            setOf(
                Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX, Achievement.ELITE_REFLEX,
                Achievement.RISING_STAR, Achievement.REFLEX_MASTER
            ),
            update.state.unlocked
        )
        assertEquals(update.state.unlocked, update.newlyUnlocked)
        assertEquals(0L, update.state.completedSessions)
        assertEquals(0, update.state.cleanSessionStreak)
        assertFalse(Achievement.STEADY_HANDS in update.state.unlocked)
        assertFalse(Achievement.IRON_FOCUS in update.state.unlocked)
    }

    @Test
    fun reconciliationIgnoresAbsentAndInvalidModeRecords() {
        listOf(
            emptyMap(),
            mapOf(GameMode.QUICK_DRAW to 0, GameMode.CLASSIC_RUSH to -1, GameMode.ENDURANCE_RUSH to Int.MIN_VALUE),
            mapOf(GameMode.QUICK_DRAW to 450, GameMode.CLASSIC_RUSH to Int.MAX_VALUE)
        ).forEach { records ->
            val update = Achievements.reconcile(AchievementState(), -1, records)
            assertEquals(AchievementState(), update.state)
            assertTrue(update.newlyUnlocked.isEmpty())
        }
    }

    @Test
    fun reconciliationRecoversCounterStreakAndLevelThresholdsWithoutIncrementing() {
        val previous = AchievementState(completedSessions = 100, cleanSessionStreak = 5)
        val update = Achievements.reconcile(previous, 25, emptyMap())
        assertEquals(100L, update.state.completedSessions)
        assertEquals(5, update.state.cleanSessionStreak)
        assertEquals(
            setOf(
                Achievement.CLEAN_SWEEP, Achievement.GETTING_STARTED, Achievement.DEDICATED,
                Achievement.VETERAN, Achievement.RISING_STAR, Achievement.REFLEX_MASTER, Achievement.LEGEND
            ),
            update.newlyUnlocked
        )
        val repeated = Achievements.reconcile(update.state, 25, emptyMap())
        assertEquals(update.state, repeated.state)
        assertTrue(repeated.newlyUnlocked.isEmpty())
    }

    @Test
    fun reconciliationNormalizesCountersWithoutRevokingAnyUnlocks() {
        val previous = AchievementState(Achievement.entries.toSet(), -1, Int.MAX_VALUE)
        val update = Achievements.reconcile(previous, -1, emptyMap())
        assertEquals(Achievement.entries.toSet(), update.state.unlocked)
        assertEquals(0L, update.state.completedSessions)
        assertEquals(0, update.state.cleanSessionStreak)
        assertTrue(update.newlyUnlocked.isEmpty())
        val bounded = Achievements.reconcile(AchievementState(completedSessions = 3, cleanSessionStreak = 8), 1, emptyMap())
        assertEquals(3, bounded.state.cleanSessionStreak)
        assertFalse(Achievement.CLEAN_SWEEP in bounded.state.unlocked)
    }

    @Test
    fun lowerPerformancesNeverRevokeOrRepeatPriorUnlocks() {
        val first = finish(GameMode.QUICK_DRAW, listOf(199), playerLevel = 25)
        val slower = finish(GameMode.QUICK_DRAW, listOf(500), previous = first.state, playerLevel = 1)
        assertEquals(first.state.unlocked, slower.state.unlocked)
        assertTrue(slower.newlyUnlocked.isEmpty())
        val repaired = Achievements.reconcile(slower.state, 1, emptyMap())
        assertEquals(first.state.unlocked, repaired.state.unlocked)
        assertTrue(repaired.newlyUnlocked.isEmpty())
    }

    @Test
    fun higherMedalsAnnounceOnlyTheNewTier() {
        val gold = finish(GameMode.QUICK_DRAW, listOf(250))
        val platinum = finish(GameMode.QUICK_DRAW, listOf(200), previous = gold.state)
        assertEquals(setOf(Achievement.PLATINUM_REFLEX), platinum.newlyUnlocked)
        val elite = finish(GameMode.QUICK_DRAW, listOf(199), previous = platinum.state)
        assertEquals(setOf(Achievement.ELITE_REFLEX), elite.newlyUnlocked)
        assertTrue(finish(GameMode.QUICK_DRAW, listOf(199), previous = elite.state).newlyUnlocked.isEmpty())
    }

    @Test
    fun permanentReflexIdsRecoverLowerTiersWithoutTheirOldScores() {
        assertHierarchyClosure(
            Achievement.ELITE_REFLEX,
            setOf(Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX, Achievement.ELITE_REFLEX)
        )
        assertHierarchyClosure(
            Achievement.PLATINUM_REFLEX,
            setOf(Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX)
        )
    }

    @Test
    fun permanentLevelIdsRecoverLowerTiersWithoutIncreasingTheLevel() {
        assertHierarchyClosure(
            Achievement.LEGEND,
            setOf(Achievement.RISING_STAR, Achievement.REFLEX_MASTER, Achievement.LEGEND)
        )
        assertHierarchyClosure(
            Achievement.REFLEX_MASTER,
            setOf(Achievement.RISING_STAR, Achievement.REFLEX_MASTER)
        )
    }

    @Test
    fun permanentSessionIdsRecoverLowerTiersWithoutInventingCompletions() {
        assertHierarchyClosure(
            Achievement.VETERAN,
            setOf(Achievement.GETTING_STARTED, Achievement.DEDICATED, Achievement.VETERAN)
        )
        assertHierarchyClosure(
            Achievement.DEDICATED,
            setOf(Achievement.GETTING_STARTED, Achievement.DEDICATED)
        )
    }

    @Test
    fun progressReportsSevenMeasurableAchievementsAndClampsLockedValues() {
        val state = AchievementState(completedSessions = 7, cleanSessionStreak = 3)
        val expected = mapOf(
            Achievement.CLEAN_SWEEP to AchievementProgress(3, 5),
            Achievement.GETTING_STARTED to AchievementProgress(7, 10),
            Achievement.DEDICATED to AchievementProgress(7, 50),
            Achievement.VETERAN to AchievementProgress(7, 100),
            Achievement.RISING_STAR to AchievementProgress(4, 5),
            Achievement.REFLEX_MASTER to AchievementProgress(4, 10),
            Achievement.LEGEND to AchievementProgress(4, 25)
        )
        Achievement.entries.forEach { achievement ->
            assertEquals(expected[achievement], Achievements.progress(achievement, state, 4))
        }
        assertEquals(7, expected.size)
        expected.forEach { (achievement, progress) ->
            val overTarget = AchievementState(completedSessions = Long.MAX_VALUE, cleanSessionStreak = Int.MAX_VALUE)
            assertEquals(AchievementProgress(progress.target, progress.target), Achievements.progress(achievement, overTarget, Int.MAX_VALUE))
            assertEquals(AchievementProgress(0, progress.target), Achievements.progress(achievement, AchievementState(completedSessions = -1, cleanSessionStreak = -1), -1))
        }
    }

    @Test
    fun unlockedProgressStaysCompleteEvenAfterCountersOrLevelFall() {
        val state = AchievementState(Achievement.entries.toSet(), 0, 0)
        Achievement.entries.forEach { achievement ->
            val progress = Achievements.progress(achievement, state, 1)
            if (achievement in setOf(
                    Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX, Achievement.ELITE_REFLEX,
                    Achievement.STEADY_HANDS, Achievement.IRON_FOCUS
                )) assertNull(progress)
            else {
                assertTrue(progress != null)
                assertEquals(progress!!.target, progress.current)
            }
        }
    }

    private fun assertHierarchyClosure(higher: Achievement, expected: Set<Achievement>) {
        val previous = AchievementState(setOf(higher), completedSessions = -1, cleanSessionStreak = -1)
        val recovered = Achievements.reconcile(previous, -1, emptyMap())
        assertEquals(expected, recovered.state.unlocked)
        assertEquals(expected - higher, recovered.newlyUnlocked)
        assertEquals(0L, recovered.state.completedSessions)
        assertEquals(0, recovered.state.cleanSessionStreak)
        val repeated = Achievements.reconcile(recovered.state, -1, emptyMap())
        assertEquals(recovered.state, repeated.state)
        assertTrue(repeated.newlyUnlocked.isEmpty())

        val completed = finish(
            GameMode.QUICK_DRAW, listOf(500), previous = previous, hadFalseStart = true, playerLevel = -1
        )
        assertEquals(expected, completed.state.unlocked)
        assertEquals(expected - higher, completed.newlyUnlocked)
        assertEquals(1L, completed.state.completedSessions)
        assertEquals(0, completed.state.cleanSessionStreak)
    }

    private fun finish(
        mode: GameMode,
        scores: List<Int>,
        previous: AchievementState = AchievementState(),
        hadFalseStart: Boolean = false,
        playerLevel: Int = 1
    ): AchievementUpdate = Achievements.completeSession(previous, mode, scores, hadFalseStart, playerLevel)
}
