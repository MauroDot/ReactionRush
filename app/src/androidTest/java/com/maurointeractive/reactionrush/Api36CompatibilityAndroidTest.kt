package com.maurointeractive.reactionrush

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real Activity/window regression coverage for the target-36 APK on each selected emulator.
 * Dispatcher Back is not a physical predictive gesture, recreation is not process death,
 * and advancing Compose's scheduler does not simulate elapsedRealtime or a long real pause.
 */
@RunWith(AndroidJUnit4::class)
class Api36CompatibilityAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var prefs: SharedPreferences
    private lateinit var original: Map<String, *>
    private var interruptionDialog: AlertDialog? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val screenshots by lazy {
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val parent = supplied?.let(::File) ?: requireNotNull(context.getExternalFilesDir("rr007a"))
        File(parent, "rr007a-${System.currentTimeMillis()}").also {
            assertTrue("Could not create test output: $it", it.isDirectory || it.mkdirs())
        }
    }

    @Before fun prepareDisposableFixture() {
        awaitHome()
        prefs = context.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
        awaitSaves()
        original = prefs.all.toMap()
        assertTrue(prefs.edit().clear().commit())
        compose.activityRule.scenario.recreate()
        awaitHome()
    }

    @After fun restoreOriginalPreferences() {
        if (!::original.isInitialized) return
        compose.runOnUiThread { interruptionDialog?.dismiss() }
        interruptionDialog = null
        compose.mainClock.autoAdvance = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        // End an unfinished fixture before waiting for its final preference checkpoints.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitSaves()
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
    }

    @Test fun migratedPackageKeepsIdentityMinimumApiGameCategoryAndForegroundLaunch() {
        val info = context.applicationInfo
        assertEquals("com.maurointeractive.reactionrush", context.packageName)
        assertEquals(36, info.targetSdkVersion)
        assertEquals(26, info.minSdkVersion)
        assertEquals(ApplicationInfo.CATEGORY_GAME, info.category)
        assertTrue(Build.VERSION.SDK_INT >= info.minSdkVersion)
        compose.onNodeWithText("REACTION RUSH").assertIsDisplayed()
        assertTrue(compose.runOnUiThread { compose.activity.hasWindowFocus() })
        assertNoEarnedProgress()
        val frame = windowFrame()
        File(screenshots, "environment.json").writeText(JSONObject().apply {
            put("runtimeApi", Build.VERSION.SDK_INT)
            put("targetApi", info.targetSdkVersion)
            put("minimumApi", info.minSdkVersion)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("windowWidthPx", frame.width)
            put("windowHeightPx", frame.height)
            put("safeLeftPx", frame.left)
            put("safeTopPx", frame.top)
            put("safeRightPx", frame.right)
            put("safeBottomPx", frame.bottom)
            put("smallestWidthDp", context.resources.configuration.smallestScreenWidthDp)
            put("fontScale", context.resources.configuration.fontScale)
            put("densityDpi", context.resources.configuration.densityDpi)
        }.toString(2))
        saveScreenshot("launch-home")
    }

    @Test fun actualSystemInsetsKeepHomeAndEveryModeControlsInsideTheVisibleWindow() {
        assertWithinSafeWindow(compose.onNodeWithText("CHOOSE A MODE  ↓").assertHasClickAction())
        GameMode.entries.forEach { mode ->
            val card = compose.onNodeWithTag("mode-${mode.name}").performScrollTo()
            assertWithinSafeWindow(card.assertHasClickAction())
            card.performClick()
            compose.waitForIdle()
            assertWithinSafeWindow(compose.onNodeWithTag("game-home").assertHasClickAction())
            assertWithinSafeWindow(compose.onNodeWithTag("game-action").assertHasClickAction())
            compose.onNodeWithText("Tap to begin.", substring = true).assertIsDisplayed()
            saveScreenshot("${mode.name.lowercase()}-ready-insets")
            goHome()
        }
        assertNoEarnedProgress()
    }

    @Test fun secondaryScreensExposeReachableControlsAndSupportedDispatcherBack() {
        compose.onNodeWithText("ACHIEVEMENTS", substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertWithinSafeWindow(compose.onNodeWithText("‹ HOME").assertHasClickAction())
        compose.onNodeWithText(Achievement.LEGEND.title, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        saveScreenshot("achievements-last-milestone")
        dispatchBackToHome()

        compose.onNodeWithText("STATISTICS").performScrollTo().performClick()
        compose.waitForIdle()
        assertWithinSafeWindow(compose.onNodeWithText("‹ HOME").assertHasClickAction())
        compose.onNodeWithText("No completed sessions in history yet.")
            .performScrollTo().assertIsDisplayed()
        saveScreenshot("statistics-empty-history")
        dispatchBackToHome()

        compose.onNodeWithText("AUDIO & HAPTICS").performScrollTo().performClick()
        compose.waitForIdle()
        assertWithinSafeWindow(compose.onNodeWithText("‹ HOME").assertHasClickAction())
        val haptics = compose.onNodeWithTag("audio-haptics-enabled").performScrollTo()
        assertWithinSafeWindow(haptics.assertHasClickAction())
        assertEquals(Role.Switch, haptics.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertTrue(haptics.fetchSemanticsNode().config.contains(SemanticsProperties.ToggleableState))
        saveScreenshot("audio-haptics-controls")
        dispatchBackToHome()
        assertNoEarnedProgress()
    }

    @Test fun falseStartRetryInterruptedDuringWaitingCannotEmitQueuedGoInAnyMode() {
        GameMode.entries.forEach { mode ->
            selectMode(mode)
            gameTap()
            compose.onNodeWithText("Don't tap until it turns green.").assertExists()
            gameTap()
            compose.onNodeWithText("TOO SOON!").assertIsDisplayed()
            gameTap()
            backgroundAndReturn()
            // Exercises stale queued work; this is scheduler time, not a physical long pause.
            compose.mainClock.advanceTimeBy(60_000)
            settle()
            compose.onNodeWithText("SESSION INTERRUPTED").assertIsDisplayed()
            compose.onNodeWithText("TAP!").assertDoesNotExist()
            compose.onNodeWithTag("background-results", useUnmergedTree = true).assertDoesNotExist()
            assertNoEarnedProgress()
            goHome()
        }
    }

    @Test fun backgroundingDuringGoAbandonsMeasurementWithoutAnyCompletedProgress() {
        GameMode.entries.forEach { mode ->
            selectMode(mode)
            gameTap()
            compose.mainClock.advanceTimeBy(4_600)
            compose.waitForIdle()
            compose.onNodeWithText("TAP!").assertIsDisplayed()
            backgroundAndReturn()
            compose.mainClock.advanceTimeBy(60_000)
            settle()
            compose.onNodeWithText("SESSION INTERRUPTED").assertIsDisplayed()
            compose.onNodeWithText("TAP!").assertDoesNotExist()
            assertNoEarnedProgress()
            goHome()
        }
    }

    @Test fun activityRecreationReturnsHomeAndAbandonsEveryActiveModeWithoutRewards() {
        GameMode.entries.forEach { mode ->
            selectMode(mode)
            gameTap()
            compose.activityRule.scenario.recreate()
            awaitHome()
            compose.onNodeWithText(
                "An unfinished session was interrupted. Choose a mode to start again."
            ).assertExists()
            compose.onNodeWithText("TAP!").assertDoesNotExist()
            assertNoEarnedProgress()
        }
    }

    @Test fun completedSessionsStayReadablePersistAfterRecreationAndAreNeverRewardedTwice() {
        GameMode.entries.forEach { mode ->
            val before = records()
            selectMode(mode)
            gameTap()
            repeat(mode.rounds) { index ->
                compose.mainClock.advanceTimeBy(4_600)
                compose.waitForIdle()
                compose.onNodeWithText("TAP!").assertExists()
                gameTap()
                if (index < mode.rounds - 1) {
                    assertEquals(before.totalXp, records().totalXp)
                    gameTap()
                }
            }
            val completed = records()
            val entry = completed.history.sessions.first()
            assertEquals(mode, entry.mode)
            assertEquals(before.achievements.completedSessions + 1, completed.achievements.completedSessions)
            assertEquals(before.totalXp + entry.xpEarned, completed.totalXp)
            assertEquals(mode.rounds, entry.roundScoresMs.size)
            assertEquals(0, entry.falseStartCount)
            gameTap()
            compose.onNodeWithTag("background-results", useUnmergedTree = true).assertIsDisplayed()
            assertWithinSafeWindow(compose.onNodeWithTag("game-home").assertHasClickAction())
            assertWithinSafeWindow(scrollTo("MEDAL: ${entry.medal.title}"))
            assertWithinSafeWindow(scrollTo("+${entry.xpEarned} XP"))
            saveScreenshot("${mode.name.lowercase()}-completed")
            if (mode.rounds > 1) {
                assertWithinSafeWindow(scrollTo("Round ${mode.rounds}: ${entry.roundScoresMs.last()} ms"))
            }
            assertEquals(completed, records())
            gameTap() // Existing replay action returns to READY without another completion.
            assertEquals(completed, records())
            goHome()
            assertEquals(completed, records())
        }
        awaitSaves()
        val completed = records()
        assertEquals(3L, completed.achievements.completedSessions)
        assertEquals(3, completed.history.sessions.size)
        compose.activityRule.scenario.recreate()
        awaitHome()
        assertEquals(completed, records())
        compose.onNodeWithText("STATISTICS").performScrollTo().performClick()
        compose.waitForIdle()
        scrollTo("Total completed sessions: 3")
        val entry = completed.history.sessions.first()
        compose.onNodeWithTag("session-${entry.sessionId}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SESSION DETAILS").assertIsDisplayed()
        assertWithinSafeWindow(scrollTo("MEDAL: ${entry.medal.title}"))
        saveScreenshot("persisted-session-details")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("STATISTICS").assertExists()
        dispatchBackToHome()
        assertEquals(completed, records())
    }

    @Test fun actualDialogWindowFocusLossInterruptsWithoutPausingOrAwardingAReaction() {
        selectMode(GameMode.CLASSIC_RUSH)
        gameTap()
        compose.runOnUiThread {
            interruptionDialog = AlertDialog.Builder(compose.activity)
                .setTitle("RR-007A focus fixture")
                .setMessage("A second window owns focus during this disposable test.")
                .setPositiveButton("Close", null)
                .create().also { it.show() }
        }
        compose.waitUntil(10_000) {
            compose.runOnUiThread { !compose.activity.hasWindowFocus() }
        }
        assertEquals(Lifecycle.State.RESUMED, compose.runOnUiThread { compose.activity.lifecycle.currentState })
        compose.runOnUiThread { interruptionDialog?.dismiss() }
        interruptionDialog = null
        awaitForeground()
        settle()
        compose.mainClock.advanceTimeBy(60_000)
        settle()
        compose.onNodeWithText("SESSION INTERRUPTED").assertIsDisplayed()
        compose.onNodeWithText("TAP!").assertDoesNotExist()
        assertNoEarnedProgress()
        saveScreenshot("window-focus-interruption")
    }

    private fun selectMode(mode: GameMode) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("mode-${mode.name}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
    }

    private fun gameTap() {
        compose.onNodeWithTag("game-action").performClick()
        settle()
    }

    private fun goHome() {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("game-home").performClick()
        awaitHome()
    }

    private fun dispatchBackToHome() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitHome()
    }

    private fun backgroundAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitForeground()
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun awaitHome() {
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("REACTION RUSH")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        awaitForeground()
    }

    private fun awaitForeground() {
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED && compose.activity.hasWindowFocus()
            }
        }
    }

    private fun awaitSaves() {
        compose.waitUntil(10_000) { CompletionPersistence.shared(prefs).status.value != SaveStatus.SAVING }
        assertFalse("A test checkpoint failed", CompletionPersistence.shared(prefs).status.value == SaveStatus.FAILED)
    }

    private fun records(): PersonalRecords = RecordsRepository(prefs).load()

    private fun assertNoEarnedProgress() {
        val saved = records()
        assertEquals(0, saved.lifetimeBestMs)
        assertEquals(0, saved.lifetimeReactions)
        GameMode.entries.forEach { assertEquals(0, saved.bestFor(it)) }
        assertEquals(0L, saved.totalXp)
        assertEquals(0L, saved.achievements.completedSessions)
        assertEquals(0, saved.achievements.cleanSessionStreak)
        assertTrue(saved.achievements.unlocked.isEmpty())
        assertTrue(saved.history.sessions.isEmpty())
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        val wasAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = true
        return try {
            compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = wasAutoAdvance
        }
    }

    private fun windowFrame(): SafeWindow = compose.runOnUiThread {
        val decor = compose.activity.window.decorView
        val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor)) {
            "Attached foreground window did not expose root insets"
        }.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        SafeWindow(decor.width, decor.height, insets.left, insets.top, insets.right, insets.bottom)
    }

    private fun assertWithinSafeWindow(node: SemanticsNodeInteraction) {
        val semantic = node.assertIsDisplayed().fetchSemanticsNode()
        // boundsInWindow clips to ancestors/window and could hide an obscured part of a control.
        val position = semantic.positionInWindow
        val size = semantic.size
        val bounds = Rect(position.x, position.y, position.x + size.width, position.y + size.height)
        val frame = windowFrame()
        val tolerance = 2f
        assertTrue("Window has no visible area", frame.width > 0 && frame.height > 0)
        assertTrue("Control has no visible area: $bounds", bounds.width > 0 && bounds.height > 0)
        assertTrue("Control obscured at left: $bounds / $frame", bounds.left >= frame.left - tolerance)
        assertTrue("Control obscured at top: $bounds / $frame", bounds.top >= frame.top - tolerance)
        assertTrue("Control obscured at right: $bounds / $frame", bounds.right <= frame.width - frame.right + tolerance)
        assertTrue("Control obscured at bottom: $bounds / $frame", bounds.bottom <= frame.height - frame.bottom + tolerance)
        // The assertion covers supplied controls, not every pixel of text or physical gestures.
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(screenshots, "$name.png").outputStream().use {
            assertTrue("Could not encode $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private data class SafeWindow(
        val width: Int, val height: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int
    )
}
