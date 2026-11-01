package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
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

@RunWith(AndroidJUnit4::class)
class AudioSettingsGameplayTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var prefs: SharedPreferences
    private lateinit var original: Map<String, *>

    @Before fun setUp() {
        awaitHome()
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
        awaitSaves()
        original = prefs.all.toMap()
        assertTrue(prefs.edit().clear().commit())
        recreate()
    }

    @After fun restorePlayerSave() {
        if (!::original.isInitialized) return
        // Flush a possibly unfinished slider before restoring the user's original save.
        // Later Activity disposal must not write test settings over that restored data.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitSaves()
        // Let deferred Compose/pointer cancellation finish before restoration, and leave
        // a live frame clock for the Compose rule's own final cleanup.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        awaitSaves()
        val editor = prefs.edit().clear()
        original.forEach { (key, value) ->
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

    @Test fun togglesPersistAfterRecreationWithoutChangingPlayerRecords() {
        assertTrue(prefs.edit().putInt("best", 222).putInt("attempts", 7)
            .putLong("total_xp", 100).putInt(GameMode.QUICK_DRAW.recordKey, 234).commit())
        recreate()
        openSettings()
        listOf("audio-music-enabled", "audio-sfx-enabled", "audio-haptics-enabled").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsOn().performClick().assertIsOff()
        }
        awaitSaves()
        recreate()
        openSettings()
        listOf("audio-music-enabled", "audio-sfx-enabled", "audio-haptics-enabled").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsOff()
        }
        assertEquals(AudioSettings(musicEnabled = false, sfxEnabled = false, hapticsEnabled = false),
            RecordsRepository(prefs).loadAudioSettings())
        assertEquals(222, prefs.getInt("best", 0))
        assertEquals(7, prefs.getInt("attempts", 0))
        assertEquals(100L, prefs.getLong("total_xp", 0))
        assertEquals(234, prefs.getInt(GameMode.QUICK_DRAW.recordKey, 0))
    }

    @Test fun accessibleVolumeChangesAreSavedAndRestoredIndependently() {
        openSettings()
        compose.onNodeWithTag("audio-music-volume").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.onNodeWithTag("audio-sfx-volume").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.85f) }
        awaitSaves()
        assertEquals(0.25f, prefs.getFloat("audio_music_volume", -1f), 0.001f)
        assertEquals(0.85f, prefs.getFloat("audio_sfx_volume", -1f), 0.001f)
        recreate()
        openSettings()
        compose.onNodeWithText("Music volume: 25%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sound-effects volume: 85%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("audio-haptics-enabled").performScrollTo().assertIsOn()
    }

    @Test fun repeatedSettingsNavigationAndBackgroundingDoNotAwardProgress() {
        repeat(3) {
            openSettings()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.onNode(hasClickAction() and hasText("HOME", substring = true)).performClick()
            awaitHome()
        }
        val records = RecordsRepository(prefs).load()
        assertEquals(0, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        assertEquals(0L, records.achievements.completedSessions)
        assertTrue(records.achievements.unlocked.isEmpty())
        assertTrue(records.history.sessions.isEmpty())
        assertFalse(prefs.contains("best"))
    }

    @Test fun mutedAudioAndHapticsPreserveCompletionAndFalseStartsInEveryMode() {
        RecordsRepository(prefs).saveAudioSettings(
            AudioSettings(musicEnabled = false, sfxEnabled = false, hapticsEnabled = false)
        )
        awaitSaves()
        recreate()
        compose.mainClock.autoAdvance = false
        GameMode.entries.forEach { mode ->
            scrollTo(mode.title)
            compose.onNode(hasClickAction() and hasAnyDescendant(hasText(mode.title)),
                useUnmergedTree = true).performClick()
            settle()
            gameTap("Tap to begin.")
            gameTap("Don't tap until it turns green.")
            assertEquals(mode.ordinal.toLong(), RecordsRepository(prefs).load().achievements.completedSessions)
            gameTap("Tap to retry.")
            repeat(mode.rounds) { round ->
                compose.mainClock.advanceTimeBy(4_600)
                compose.waitForIdle()
                gameTap("TAP!")
                if (round < mode.rounds - 1) gameTap("Round ${round + 1} complete.")
            }
            compose.onNode(hasClickAction() and hasText("HOME", substring = true)).performClick()
            settle()
        }
        val records = RecordsRepository(prefs).load()
        assertEquals(3L, records.achievements.completedSessions)
        assertEquals(GameMode.entries.sumOf { it.rounds }, records.lifetimeReactions)
        assertEquals(3, records.history.sessions.size)
        assertTrue(records.totalXp > 0)
        GameMode.entries.forEach { mode ->
            assertTrue(records.bestFor(mode) > 0)
            assertEquals(1L, records.completedByMode[mode])
        }
        assertTrue(records.history.sessions.all { it.falseStartCount == 1 })
        assertEquals(0, records.achievements.cleanSessionStreak)
    }

    private fun openSettings() {
        compose.onNodeWithText("AUDIO & HAPTICS").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("audio-music-enabled").assertExists()
    }

    private fun recreate() {
        compose.activityRule.scenario.recreate()
        awaitHome()
    }

    private fun awaitHome() {
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("REACTION RUSH")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED && compose.activity.hasWindowFocus()
            }
        }
    }

    private fun awaitSaves() {
        compose.waitUntil(10_000) { CompletionPersistence.shared(prefs).status.value != SaveStatus.SAVING }
    }

    private fun scrollTo(text: String) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(text).performScrollTo()
        compose.mainClock.autoAdvance = false
    }

    private fun gameTap(text: String) {
        compose.onNode(hasClickAction() and hasAnyDescendant(hasText(text, substring = true)),
            useUnmergedTree = true).performClick()
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
}
