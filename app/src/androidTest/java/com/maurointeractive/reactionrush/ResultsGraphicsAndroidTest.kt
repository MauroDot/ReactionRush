package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.TypedValue
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Approved graphics rendered through real UI; production clocks and rewards remain authoritative. */
@RunWith(AndroidJUnit4::class)
class ResultsGraphicsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var prefs: SharedPreferences
    private lateinit var original: Map<String, *>
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val screenshots by lazy {
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val parent = supplied?.let(::File) ?: requireNotNull(context.getExternalFilesDir("rr006b"))
        File(parent, "rr006b-${System.currentTimeMillis()}").also {
            assertTrue("Could not create screenshot output: $it", it.isDirectory || it.mkdirs())
        }
    }

    @Before fun prepareFreshRecords() {
        awaitHome()
        prefs = context.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
        awaitSaves()
        original = prefs.all.toMap()
        assertTrue(prefs.edit().clear().commit())
        compose.activityRule.scenario.recreate()
        awaitHome()
    }

    @After fun restorePlayerSave() {
        if (!::original.isInitialized) return
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
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

    @Test fun sixPackagedImagesKeepApprovedHashesDimensionsDensityAndMedalTransparency() {
        val images = listOf(
            ExpectedImage(R.drawable.rr_results_background, "webp", 1440, 2560, 512982,
                "84b3784c8652cddfa1d1f25f29a81e3a72d1db9ce98011965cd17a1a56793f9f"),
            ExpectedImage(R.drawable.rr_medal_bronze, "png", 512, 512, 302555,
                "e41d40a6081e8a4104d4c7b1b4f6d396996edc523470d5aaa9ff01a22a2e6391", 123903),
            ExpectedImage(R.drawable.rr_medal_silver, "png", 512, 512, 300831,
                "cdc15168dbf67530dd200048d89efbb4cc4ad3b4d26b6cc2add7c54ca079bbd6", 122356),
            ExpectedImage(R.drawable.rr_medal_gold, "png", 512, 512, 282103,
                "cd65da184afd01abb31478e145526101590ef8e337ffeb7f38322bcde61b5c6f", 119686),
            ExpectedImage(R.drawable.rr_medal_platinum, "png", 512, 512, 310216,
                "881cceeb4c4f71f7ea065b0ce86b13ad64ae2121d5cc671ffdc0f8f21958f8a3", 120866),
            ExpectedImage(R.drawable.rr_medal_elite, "png", 512, 512, 284234,
                "4c614d05baefe276f06477c5ed2938f7f3159ff4653bdf45cbbfdd6f77a2cd7b", 110567)
        )
        images.forEach { expected ->
            val value = TypedValue()
            context.resources.getValue(expected.resourceId, value, true)
            assertEquals(TypedValue.DENSITY_NONE, value.density)
            assertTrue(value.string.toString().endsWith(".${expected.extension}"))
            val bytes = context.resources.openRawResource(expected.resourceId).use { it.readBytes() }
            assertEquals(expected.bytes, bytes.size)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(expected.sha256, hash)
            val options = BitmapFactory.Options().apply { inScaled = false }
            val bitmap = BitmapFactory.decodeResource(context.resources, expected.resourceId, options)
            assertNotNull(bitmap)
            requireNotNull(bitmap).let {
                try {
                    assertEquals(expected.width, it.width)
                    assertEquals(expected.height, it.height)
                    assertEquals("image/${expected.extension}", options.outMimeType)
                    if (expected.alphaNonzero == null) {
                        assertFalse(it.hasAlpha())
                    } else {
                        assertTrue(it.hasAlpha())
                        val pixels = IntArray(it.width * it.height)
                        it.getPixels(pixels, 0, it.width, 0, 0, it.width, it.height)
                        assertEquals(expected.alphaNonzero, pixels.count { pixel -> pixel ushr 24 != 0 })
                        assertTrue(pixels.any { pixel -> pixel ushr 24 in 1..254 })
                        assertEquals(0, pixels.first() ushr 24)
                        assertEquals(0, pixels.last() ushr 24)
                    }
                } finally {
                    it.recycle()
                }
            }
        }
    }

    @Test fun everyExistingMedalKeepsItsTextAndDecorativeImageWithoutInventingAnUnrankedAward() {
        val selected = mutableStateOf(PerformanceMedal.BRONZE)
        replaceContent {
            RushTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MedalBadge(selected.value, Modifier.fillMaxWidth().padding(24.dp))
                }
            }
        }
        PerformanceMedal.entries.forEach { medal ->
            compose.runOnUiThread { selected.value = medal }
            compose.waitForIdle()
            compose.onNodeWithText("MEDAL: ${medal.title}", useUnmergedTree = true).assertIsDisplayed()
            PerformanceMedal.entries.forEach { other ->
                if (other == medal && medalArtwork(medal) != null) assertDecorative(artwork(other))
                else artwork(other).assertDoesNotExist()
            }
            saveScreenshot("medal-${medal.name.lowercase()}")
        }
        assertNoEarnedProgress()
    }

    @Test fun completedResultsInEveryModeUseVerifiedSceneActualMedalRecordsAndAchievementFeedback() {
        assertTrue(prefs.edit().putLong("total_xp", 95).commit())
        compose.activityRule.scenario.recreate()
        awaitHome()
        GameMode.entries.forEach { mode ->
            val before = records()
            completeToFinalRound(mode)
            val completed = records()
            val entry = completed.history.sessions.first()
            assertEquals(mode, entry.mode)
            assertEquals(before.achievements.completedSessions + 1, completed.achievements.completedSessions)
            assertEquals(before.totalXp + entry.xpEarned, completed.totalXp)
            assertEquals(PlayerProgression.medal(entry.averageTimeMs), entry.medal)
            assertTrue(completed.bestFor(mode) > 0)
            background(ArcadeBackdrop.RESULTS).assertDoesNotExist()

            gameTap()

            assertDecorative(background(ArcadeBackdrop.RESULTS))
            background(gameBackdrop(mode)).assertDoesNotExist()
            compose.onNodeWithText("NEW PERSONAL RECORD!", substring = true).assertExists()
            scrollTo("MEDAL: ${entry.medal.title}")
            if (medalArtwork(entry.medal) != null) assertDecorative(artwork(entry.medal))
            else PerformanceMedal.entries.forEach { artwork(it).assertDoesNotExist() }
            saveScreenshot("${mode.name.lowercase()}-results")
            scrollTo("+${entry.xpEarned} XP")
            val newlyUnlocked = completed.achievements.unlocked - before.achievements.unlocked
            if (newlyUnlocked.isNotEmpty()) {
                scrollTo("UNLOCKED THIS SESSION")
                newlyUnlocked.forEach { achievement ->
                    assertTrue(compose.onAllNodesWithText(achievement.title, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty())
                }
                saveScreenshot("${mode.name.lowercase()}-achievements")
            }
            if (before.totalXp == 95L) {
                compose.onNodeWithText("LEVEL UP! LEVEL 2", useUnmergedTree = true).assertExists()
            }
            assertEquals(completed, records())
            gameTap() // Existing results action returns to READY without another reward.
            background(gameBackdrop(mode)).assertIsDisplayed()
            background(ArcadeBackdrop.RESULTS).assertDoesNotExist()
            assertEquals(completed, records())
            goHome()
            assertEquals(completed, records())
        }
    }

    @Test fun abandonedFalseStartsAndInterruptedRoundsNeverShowVictoryArtOrAwardProgress() {
        GameMode.entries.forEach { mode ->
            selectMode(mode)
            gameTap()
            background(ArcadeBackdrop.RESULTS).assertDoesNotExist()
            background(gameBackdrop(mode)).assertIsDisplayed()
            gameTap()
            compose.onNodeWithText("TOO SOON!").assertIsDisplayed()
            background(ArcadeBackdrop.RESULTS).assertDoesNotExist()
            gameTap()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitForeground()
            settle()
            compose.onNodeWithText("SESSION INTERRUPTED").assertIsDisplayed()
            background(ArcadeBackdrop.RESULTS).assertDoesNotExist()
            background(gameBackdrop(mode)).assertIsDisplayed()
            assertNoEarnedProgress()
            goHome()
        }
    }

    @Test fun completedSceneSurvivesForegroundReturnAndHistoryRetainsItsEarnedMedalAfterRecreation() {
        completeToFinalRound(GameMode.QUICK_DRAW)
        gameTap()
        val completed = records()
        val entry = completed.history.sessions.single()
        background(ArcadeBackdrop.RESULTS).assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitForeground()
        settle()
        background(ArcadeBackdrop.RESULTS).assertIsDisplayed()
        assertEquals(completed, records())
        compose.activityRule.scenario.recreate()
        awaitHome()
        background(ArcadeBackdrop.RESULTS).assertDoesNotExist()
        assertEquals(completed, records())
        compose.onNodeWithText("STATISTICS").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("session-${entry.sessionId}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SESSION DETAILS").assertExists()
        scrollTo("MEDAL: ${entry.medal.title}")
        if (medalArtwork(entry.medal) != null) assertDecorative(artwork(entry.medal))
        else PerformanceMedal.entries.forEach { artwork(it).assertDoesNotExist() }
        saveScreenshot("retained-history-medal")
        assertEquals(completed, records())
    }

    @Test fun resultsScrimAdjustsWithoutReplacingArtworkOrBlockingSmallLargeFontControls() {
        val alpha = mutableStateOf(ARCADE_BACKDROP_SCRIM_ALPHA)
        var taps = 0
        replaceContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                RushTheme {
                    Box(Modifier.fillMaxSize()) {
                        CyberArcadeBackground(
                            ArcadeBackdrop.RESULTS,
                            Modifier.requiredSize(300.dp, 400.dp).testTag("results-viewport"),
                            scrimAlpha = alpha.value
                        ) {
                            MedalBadge(PerformanceMedal.SILVER,
                                Modifier.align(Alignment.Center).padding(12.dp))
                            Button(onClick = { taps++ },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                                    .testTag("results-home")) { Text("HOME") }
                        }
                    }
                }
            }
        }
        val image = background(ArcadeBackdrop.RESULTS).fetchSemanticsNode()
        val frame = compose.onNodeWithTag("results-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.width, image.boundsInRoot.width, 1f)
        assertEquals(frame.height, image.boundsInRoot.height, 1f)
        assertDecorative(background(ArcadeBackdrop.RESULTS))
        compose.onNodeWithText("MEDAL: SILVER", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("results-home").performClick()
        assertEquals(1, taps)
        saveScreenshot("small-results-large-font")
        compose.runOnUiThread { alpha.value = 1f }
        compose.waitForIdle()
        assertEquals(image.id, background(ArcadeBackdrop.RESULTS).fetchSemanticsNode().id)
        val pixels = background(ArcadeBackdrop.RESULTS).captureToImage().toPixelMap()
        assertEquals(RushColors.Background.toArgb(), pixels[1, pixels.height / 2].toArgb())
        compose.onNodeWithTag("results-home").performClick()
        assertEquals(2, taps)
        assertNoEarnedProgress()
    }

    private fun completeToFinalRound(mode: GameMode) {
        val xpBefore = records().totalXp
        selectMode(mode)
        gameTap()
        repeat(mode.rounds) { round ->
            background(ArcadeBackdrop.RESULTS).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(4_600)
            compose.waitForIdle()
            compose.onNodeWithText("TAP!").assertExists()
            gameTap()
            background(ArcadeBackdrop.RESULTS).assertDoesNotExist()
            if (round < mode.rounds - 1) {
                assertEquals(xpBefore, records().totalXp)
                gameTap()
            }
        }
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

    private fun scrollTo(text: String) {
        compose.mainClock.autoAdvance = true
        try {
            compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = false
        }
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
    }

    private fun records(): PersonalRecords = RecordsRepository(prefs).load()

    private fun assertNoEarnedProgress() {
        val records = records()
        assertEquals(0, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        assertEquals(0L, records.achievements.completedSessions)
        assertTrue(records.achievements.unlocked.isEmpty())
        assertTrue(records.history.sessions.isEmpty())
    }

    private fun replaceContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent(content = content)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun artwork(medal: PerformanceMedal) =
        compose.onNodeWithTag("medal-art-${medal.name}", useUnmergedTree = true)

    private fun background(asset: ArcadeBackdrop) =
        compose.onNodeWithTag("background-${asset.tag}", useUnmergedTree = true)

    private fun assertDecorative(node: SemanticsNodeInteraction) {
        val semantics = node.assertIsDisplayed().fetchSemanticsNode()
        assertFalse(semantics.config.contains(SemanticsActions.OnClick))
        assertFalse(semantics.config.contains(SemanticsProperties.ContentDescription))
        assertFalse(semantics.config.contains(SemanticsProperties.Focused))
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(screenshots, "$name.png").outputStream().use {
            assertTrue("Could not encode screenshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private data class ExpectedImage(
        val resourceId: Int,
        val extension: String,
        val width: Int,
        val height: Int,
        val bytes: Int,
        val sha256: String,
        val alphaNonzero: Int? = null
    )
}
