package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertCountEquals
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
        recordBanner().assertDoesNotExist()

        goHome()
        compose.onAllNodesWithText("BEST AVERAGE: —").assertCountEquals(2)
        compose.activityRule.scenario.recreate()
        settle()
        assertEquals(1, prefs.getInt("attempts", 0))
        assertFalse(prefs.contains(GameMode.CLASSIC_RUSH.recordKey))
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
            compose.onNodeWithText("Round ${mode.rounds}: ${scores.last()} ms", useUnmergedTree = true)
                .performScrollTo().assertExists()
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

    private fun selectMode(mode: GameMode) {
        compose.onNodeWithText(mode.title).performScrollTo()
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

}
