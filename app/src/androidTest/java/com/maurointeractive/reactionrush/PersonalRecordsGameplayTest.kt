package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PersonalRecordsGameplayTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var prefs: SharedPreferences
    private lateinit var originalPreferences: Map<String, *>

    @Before
    fun prepareFreshRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
        originalPreferences = prefs.all.toMap()
        assertTrue(prefs.edit().clear().commit())
        compose.activityRule.scenario.recreate()
        compose.mainClock.autoAdvance = false
        settle()
    }

    @After
    fun restoreExistingRecords() {
        // Preserve any personal records already present on the connected device.
        if (!::originalPreferences.isInitialized) return
        val editor = prefs.edit().clear()
        originalPreferences.forEach { (key, value) ->
            when (value) {
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(editor.commit())
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun freshModesAndFalseStartDoNotCreateRecords() {
        compose.onAllNodesWithText("BEST TIME: —").assertCountEquals(1)
        compose.onAllNodesWithText("BEST AVERAGE: —").assertCountEquals(2)

        selectMode(GameMode.QUICK_DRAW)
        clickGameText("Tap to begin.")
        clickGameText("Don't tap until it turns green.")
        compose.onNodeWithText("TOO SOON!").assertExists()
        assertEquals(0, prefs.getInt("attempts", 0))
        assertFalse(prefs.contains("best"))
        assertFalse(prefs.contains(GameMode.QUICK_DRAW.recordKey))
        assertEquals(0L, prefs.getLong("total_xp", 0L))
        recordBanner().assertDoesNotExist()
    }

    @Test
    fun unfinishedClassicKeepsLifetimeReactionWithoutCreatingModeRecord() {
        selectMode(GameMode.CLASSIC_RUSH)
        clickGameText("Tap to begin.")
        reactWhenGreen()
        assertEquals(1, prefs.getInt("attempts", 0))
        assertTrue(prefs.getInt("best", 0) > 0)
        assertFalse(prefs.contains(GameMode.CLASSIC_RUSH.recordKey))
        assertEquals(0L, prefs.getLong("total_xp", 0L))
        recordBanner().assertDoesNotExist()

        goHome()
        compose.onAllNodesWithText("BEST AVERAGE: —").assertCountEquals(2)
        compose.activityRule.scenario.recreate()
        settle()
        assertEquals(1, prefs.getInt("attempts", 0))
        assertFalse(prefs.contains(GameMode.CLASSIC_RUSH.recordKey))
        assertEquals(0L, prefs.getLong("total_xp", 0L))
    }

    @Test
    fun quickDrawRecordIsSavedOnFinalReactionAndSurvivesRecreation() {
        selectMode(GameMode.QUICK_DRAW)
        clickGameText("Tap to begin.")
        reactWhenGreen()
        val record = prefs.getInt(GameMode.QUICK_DRAW.recordKey, 0)
        assertTrue(record > 0)
        assertEquals(record, prefs.getInt("best", 0))
        recordBanner().assertExists()

        clickGameText("Round complete. Tap for results.")
        compose.onNodeWithText("DRAW COMPLETE").assertExists()
        recordBanner().assertExists()
        clickGameText("Tap to play this mode again.")
        recordBanner().assertDoesNotExist()

        goHome()
        compose.onNodeWithText("BEST TIME: $record ms").assertExists()
        compose.activityRule.scenario.recreate()
        settle()
        compose.onNodeWithText("BEST TIME: $record ms").assertExists()
        selectMode(GameMode.QUICK_DRAW)
        recordBanner().assertDoesNotExist()
        assertEquals(record, prefs.getInt(GameMode.QUICK_DRAW.recordKey, 0))
    }

    @Test
    fun rushModesSaveCompletedAverageAndClearFeedbackForReplay() {
        var successfulReactions = 0
        for (mode in listOf(GameMode.CLASSIC_RUSH, GameMode.ENDURANCE_RUSH)) {
            selectMode(mode)
            clickGameText("Tap to begin.")
            val scores = mutableListOf<Int>()
            repeat(mode.rounds) { round ->
                reactWhenGreen()
                scores += displayedRoundScore()
                successfulReactions++
                assertEquals(successfulReactions, prefs.getInt("attempts", 0))
                if (round < mode.rounds - 1) {
                    assertFalse(prefs.contains(mode.recordKey))
                    recordBanner().assertDoesNotExist()
                    clickGameText("Round ${round + 1} complete. Tap for the next round.")
                }
            }

            val expectedAverage = scores.average().roundToInt()
            assertEquals(expectedAverage, prefs.getInt(mode.recordKey, 0))
            recordBanner().assertExists()
            clickGameText("Round complete. Tap for results.")
            compose.onNodeWithText("RUSH COMPLETE").assertExists()
            recordBanner().assertExists()
            // The long Endurance result remains reachable within its scroll area.
            scrollToText("Round ${mode.rounds}: ${scores.last()} ms")
            clickGameText("Tap to play this mode again.")
            recordBanner().assertDoesNotExist()
            goHome()
            assertTrue(compose.onAllNodesWithText("BEST AVERAGE: $expectedAverage ms")
                .fetchSemanticsNodes().isNotEmpty())
        }
        assertTrue(prefs.contains(GameMode.CLASSIC_RUSH.recordKey))
        assertTrue(prefs.contains(GameMode.ENDURANCE_RUSH.recordKey))
        assertFalse(prefs.contains(GameMode.QUICK_DRAW.recordKey))
    }

    @Test
    fun completedModesAwardExactXpAndHomeProgressSurvivesRecreation() {
        var totalXp = 0L
        assertProgressCard(totalXp)

        for (mode in GameMode.entries) {
            val scores = completeSession(mode, totalXp)
            val expectedReward = PlayerProgression.reward(mode, scores)
            totalXp += expectedReward.totalXp
            assertEquals(totalXp, prefs.getLong("total_xp", 0L))

            clickGameText("Round complete. Tap for results.")
            scrollToText("+${expectedReward.totalXp} XP")
            scrollToText("MEDAL: ${PlayerProgression.medal(scores.average().roundToInt()).title}")
            assertProgressCard(totalXp)
            assertEquals(totalXp, prefs.getLong("total_xp", 0L))

            clickGameText("Tap to play this mode again.")
            goHome()
            assertProgressCard(totalXp)
            compose.activityRule.scenario.recreate()
            settle()
            assertProgressCard(totalXp)
            assertEquals(totalXp, prefs.getLong("total_xp", 0L))
        }
    }

    @Test
    fun falseStartRetryEarnsNormalXpAndNavigationNeverRepeatsTheAward() {
        val mode = GameMode.QUICK_DRAW
        selectMode(mode)
        clickGameText("Tap to begin.")
        clickGameText("Don't tap until it turns green.")
        assertEquals(0L, prefs.getLong("total_xp", 0L))
        assertEquals(0, prefs.getInt("attempts", 0))

        clickGameText("False start! This round doesn't count.")
        reactWhenGreen()
        val firstXp = PlayerProgression.reward(mode, listOf(displayedRoundScore())).totalXp
        assertEquals(firstXp.toLong(), prefs.getLong("total_xp", 0L))
        assertEquals(1, prefs.getInt("attempts", 0))
        clickGameText("Round complete. Tap for results.")
        compose.onNodeWithText("+$firstXp XP").assertExists()
        assertEquals(firstXp.toLong(), prefs.getLong("total_xp", 0L))

        // Further result-area taps reset and start another session, but award
        // nothing until another successful final reaction is recorded.
        clickGameText("Tap to play this mode again.")
        compose.onNodeWithText("+$firstXp XP").assertDoesNotExist()
        clickGameText("Tap to begin.")
        goHome()
        assertProgressCard(firstXp.toLong())

        val replayScores = completeSession(mode, firstXp.toLong())
        val replayXp = PlayerProgression.reward(mode, replayScores).totalXp
        val totalXp = firstXp.toLong() + replayXp
        assertEquals(totalXp, prefs.getLong("total_xp", 0L))
        assertEquals(2, prefs.getInt("attempts", 0))
        clickGameText("Round complete. Tap for results.")
        clickGameText("Tap to play this mode again.")
        goHome()
        assertProgressCard(totalXp)
        selectMode(mode)
        assertEquals(totalXp, prefs.getLong("total_xp", 0L))
        compose.onNodeWithText("+$replayXp XP").assertDoesNotExist()
    }

    @Test
    fun levelUpFeedbackClearsForReplayWhileProgressPersists() {
        assertTrue(prefs.edit().putLong("total_xp", 99L).commit())
        compose.activityRule.scenario.recreate()
        settle()
        assertProgressCard(99L)

        val mode = GameMode.QUICK_DRAW
        val scores = completeSession(mode, 99L)
        val totalXp = 99L + PlayerProgression.reward(mode, scores).totalXp
        val expectedLevel = PlayerProgression.levelProgress(totalXp).level
        assertTrue(expectedLevel > 1)
        compose.onNodeWithText("LEVEL UP! LEVEL $expectedLevel", useUnmergedTree = true)
            .assertIsDisplayed()
        clickGameText("Round complete. Tap for results.")
        scrollToText("LEVEL UP! LEVEL $expectedLevel")
        assertProgressCard(totalXp)

        clickGameText("Tap to play this mode again.")
        compose.onNodeWithText("LEVEL UP!", substring = true).assertDoesNotExist()
        goHome()
        assertProgressCard(totalXp)
        compose.activityRule.scenario.recreate()
        settle()
        assertProgressCard(totalXp)
        selectMode(mode)
        compose.onNodeWithText("LEVEL UP!", substring = true).assertDoesNotExist()
    }

    @Test
    fun invalidXpLoadsSafelyAndCappedRewardsShowOnlyCreditedXp() {
        assertTrue(prefs.edit().putString("total_xp", "invalid").commit())
        compose.activityRule.scenario.recreate()
        settle()
        assertProgressCard(0L)
        assertTrue(prefs.edit().putLong("total_xp", -10L).commit())
        compose.activityRule.scenario.recreate()
        settle()
        assertProgressCard(0L)

        assertTrue(prefs.edit().putLong("total_xp", Long.MAX_VALUE - 1L).commit())
        compose.activityRule.scenario.recreate()
        settle()
        assertProgressCard(Long.MAX_VALUE - 1L)
        completeSession(GameMode.QUICK_DRAW, Long.MAX_VALUE - 1L)
        assertEquals(Long.MAX_VALUE, prefs.getLong("total_xp", 0L))
        clickGameText("Round complete. Tap for results.")
        scrollToText("+1 XP")
        scrollToText("XP limit reached")
        assertProgressCard(Long.MAX_VALUE)

        clickGameText("Tap to play this mode again.")
        goHome()
        completeSession(GameMode.QUICK_DRAW, Long.MAX_VALUE)
        clickGameText("Round complete. Tap for results.")
        scrollToText("+0 XP")
        scrollToText("XP limit reached")
        assertEquals(Long.MAX_VALUE, prefs.getLong("total_xp", 0L))
        assertProgressCard(Long.MAX_VALUE)
    }

    private fun completeSession(mode: GameMode, previousXp: Long): List<Int> {
        selectMode(mode)
        clickGameText("Tap to begin.")
        val scores = mutableListOf<Int>()
        repeat(mode.rounds) { round ->
            reactWhenGreen()
            scores += displayedRoundScore()
            if (round < mode.rounds - 1) {
                assertEquals(previousXp, prefs.getLong("total_xp", 0L))
                clickGameText("Round ${round + 1} complete. Tap for the next round.")
            }
        }
        return scores
    }

    private fun assertProgressCard(totalXp: Long) {
        val progress = PlayerProgression.levelProgress(totalXp)
        compose.onNodeWithText("LEVEL ${progress.level}").assertExists()
        compose.onNodeWithText("TOTAL XP: $totalXp").assertExists()
        scrollToText(if (progress.isMaxLevel) "MAX LEVEL" else
            "${progress.xpIntoLevel} / ${progress.xpToNextLevel} XP TO LEVEL ${progress.level + 1}")
        val bar = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true
        ).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(progress.fraction, bar.current, 0.0001f)
        assertEquals(0f..1f, bar.range)
    }

    private fun selectMode(mode: GameMode) {
        scrollToText(mode.title)
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText(mode.title)),
            useUnmergedTree = true
        ).performClick()
        settle()
    }

    private fun clickGameText(text: String) {
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText(text, substring = true)),
            useUnmergedTree = true
        ).performClick()
        settle()
    }

    private fun reactWhenGreen() {
        // The production wait is random in [1500, 4500] ms. Advance virtual Compose
        // time instead of introducing real multi-second waits into gameplay tests.
        compose.mainClock.advanceTimeBy(4_600)
        compose.waitForIdle()
        compose.onNodeWithText("TAP!").assertExists()
        clickGameText("TAP!")
    }

    private fun displayedRoundScore(): Int {
        val text = compose.onNode(
            SemanticsMatcher("round score") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.any { it.text.matches(Regex("[0-9]+ ms")) } == true
            } and hasAnyAncestor(hasClickAction()),
            useUnmergedTree = true
        ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        return text.first { it.text.matches(Regex("[0-9]+ ms")) }.text.removeSuffix(" ms").toInt()
    }

    private fun recordBanner() = compose.onNodeWithText("NEW PERSONAL RECORD!", substring = true)

    private fun goHome() {
        compose.onNode(hasClickAction() and hasText("HOME", substring = true)).performClick()
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun scrollToText(text: String) {
        // Scrolling can animate when a result extends beyond a small display.
        // Keep reaction waits virtual, but let these UI-only animations finish.
        compose.mainClock.autoAdvance = true
        try {
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = false
        }
    }

}
