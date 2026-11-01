package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.TypedValue
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
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

/** Packaged art and real Compose rendering; no production timing or diagnostic hooks. */
@RunWith(AndroidJUnit4::class)
class CyberArcadeBackgroundAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var prefs: SharedPreferences
    private lateinit var original: Map<String, *>
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val screenshots by lazy {
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val parent = supplied?.let(::File) ?: requireNotNull(context.getExternalFilesDir("rr006a"))
        File(parent, "rr006a-${System.currentTimeMillis()}").also {
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
        // Settle pending UI/disposal writes before restoring the player's original values.
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

    @Test fun fourPackagedWebpsKeepApprovedHashesDimensionsAndNoDensityScaling() {
        val images = listOf(
            ExpectedImage(ArcadeBackdrop.HOME, 941, 1672,
                "6ab7f12816c2f99feb8c7044c3bc62c76e53c1268a61cdee1a197ef29e4e39ea"),
            ExpectedImage(ArcadeBackdrop.QUICK_DRAW, 1440, 2560,
                "0436bea722002c3b43401562c03aa22227dfe9f44f00a0115aac5e751c899d3e"),
            ExpectedImage(ArcadeBackdrop.CLASSIC_RUSH, 1440, 2560,
                "dc9dcf7f6097bc7dc64b04c013f6b0f224bbbaabbad18c1df0c96ba357af183b"),
            ExpectedImage(ArcadeBackdrop.ENDURANCE_RUSH, 1440, 2560,
                "9bbb9724df3b64eef501d941ef6c39706dcf7443d149d388e0150596f08fca42")
        )
        assertEquals(4, images.map { it.asset.resourceId }.toSet().size)
        images.forEach { expected ->
            val value = TypedValue()
            context.resources.getValue(expected.asset.resourceId, value, true)
            assertEquals(TypedValue.DENSITY_NONE, value.density)
            assertTrue(value.string.toString().endsWith(".webp"))
            val digest = MessageDigest.getInstance("SHA-256")
            context.resources.openRawResource(expected.asset.resourceId).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    digest.update(buffer, 0, length)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(expected.asset.tag, expected.sha256, hash)
            val options = BitmapFactory.Options().apply { inScaled = false }
            val bitmap = BitmapFactory.decodeResource(context.resources, expected.asset.resourceId, options)
            assertNotNull("Native decoder rejected ${expected.asset.tag}", bitmap)
            requireNotNull(bitmap).let {
                try {
                    assertEquals(expected.width, it.width)
                    assertEquals(expected.height, it.height)
                    assertEquals("image/webp", options.outMimeType)
                    assertFalse("Background must be opaque", it.hasAlpha())
                } finally {
                    it.recycle()
                }
            }
        }
    }

    @Test fun homeAndEveryModeUseDecorativeBackgroundsWithoutChangingNavigationOrProgress() {
        assertDecorative(ArcadeBackdrop.HOME)
        saveScreenshot("home")
        GameMode.entries.forEach { mode ->
            selectMode(mode)
            assertDecorative(gameBackdrop(mode))
            background(ArcadeBackdrop.HOME).assertDoesNotExist()
            compose.onNodeWithText("Tap to begin.", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("game-action").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("game-home").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            saveScreenshot("${gameBackdrop(mode).tag}-ready")
            goHome()
            assertDecorative(ArcadeBackdrop.HOME)
        }
        assertNoEarnedProgress()
    }

    @Test fun everyModeKeepsOneBackgroundAcrossTimedStatesAndShowsGoColorImmediately() {
        GameMode.entries.forEach { mode ->
            selectMode(mode)
            val asset = gameBackdrop(mode)
            val imageIdentity = background(asset).fetchSemanticsNode().id
            gameTap()
            compose.onNodeWithText("WAIT…").assertIsDisplayed()
            assertEquals(imageIdentity, background(asset).fetchSemanticsNode().id)
            gameTap()
            compose.onNodeWithText("TOO SOON!").assertIsDisplayed()
            assertBackdropEdgeColor(asset, Color(0xFF502235))
            assertEquals(imageIdentity, background(asset).fetchSemanticsNode().id)
            gameTap()
            compose.onNodeWithText("WAIT…").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(4_600)
            compose.waitForIdle()
            compose.onNodeWithText("TAP!").assertIsDisplayed()
            assertEquals(imageIdentity, background(asset).fetchSemanticsNode().id)
            // The first rendered GO frame already has the original solid signal color.
            // No additional clock advance is used to wait for a fade or image replacement.
            assertBackdropEdgeColor(asset, RushColors.Success)
            saveScreenshot("${asset.tag}-go")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) {
                compose.runOnUiThread { compose.activity.hasWindowFocus() }
            }
            settle()
            compose.onNodeWithText("SESSION INTERRUPTED").assertIsDisplayed()
            assertEquals(imageIdentity, background(asset).fetchSemanticsNode().id)
            assertNoEarnedProgress()
            goHome()
        }
    }

    @Test fun smallPortraitAndLandscapeViewportsCropArtWithoutClippingControlsOrInterceptingTaps() {
        val viewport = mutableStateOf(280.dp to 360.dp)
        val selected = mutableStateOf(ArcadeBackdrop.HOME)
        var taps = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    RushTheme {
                        Box(Modifier.fillMaxSize()) {
                            CyberArcadeBackground(
                                selected.value,
                                Modifier.requiredSize(viewport.value.first, viewport.value.second)
                                    .testTag("small-viewport")
                            ) {
                                Text("REACTION RUSH", color = RushColors.Text,
                                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
                                Button(
                                    onClick = { taps++ },
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                                        .testTag("small-play")
                                ) { Text("PLAY") }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = true
        ArcadeBackdrop.entries.forEachIndexed { index, asset ->
            compose.runOnUiThread {
                selected.value = asset
                viewport.value = if (index % 2 == 0) 280.dp to 360.dp else 360.dp to 280.dp
            }
            compose.waitForIdle()
            val frame = compose.onNodeWithTag("small-viewport").fetchSemanticsNode().boundsInRoot
            val image = background(asset).fetchSemanticsNode().boundsInRoot
            assertEquals(frame.width, image.width, 1f)
            assertEquals(frame.height, image.height, 1f)
            assertDecorative(asset)
            compose.onNodeWithTag("small-play").assertIsDisplayed().performClick()
            assertEquals(index + 1, taps)
            if (index < 2) saveScreenshot(if (index == 0) "small-portrait-large-font" else "small-landscape-large-font")
        }
        assertNoEarnedProgress()
    }

    @Test fun scrimKeepsExistingTextAndAccentColorsReadableOverEvenWhiteArtwork() {
        val brightestBackground = RushColors.Background.copy(alpha = ARCADE_BACKDROP_SCRIM_ALPHA)
            .compositeOver(Color.White)
        val foregrounds = listOf(RushColors.Text, RushColors.Muted, RushColors.Subtle,
            RushColors.Cyan, RushColors.Mint, RushColors.Gold, RushColors.Danger)
        foregrounds.forEach { color ->
            val contrast = (color.luminance() + 0.05f) / (brightestBackground.luminance() + 0.05f)
            assertTrue("Insufficient text contrast $contrast for $color", contrast >= 4.5f)
        }
    }

    private fun background(asset: ArcadeBackdrop): SemanticsNodeInteraction =
        compose.onNodeWithTag("background-${asset.tag}", useUnmergedTree = true)

    private fun assertDecorative(asset: ArcadeBackdrop) {
        val node = background(asset).assertIsDisplayed().fetchSemanticsNode()
        assertFalse(node.config.contains(SemanticsActions.OnClick))
        assertFalse(node.config.contains(SemanticsProperties.ContentDescription))
        assertFalse(node.config.contains(SemanticsProperties.Focused))
    }

    private fun assertBackdropEdgeColor(asset: ArcadeBackdrop, expected: Color) {
        val pixels = background(asset).captureToImage().toPixelMap()
        // Inside the viewport but outside the padded interactive panel and text.
        assertEquals(expected.toArgb(), pixels[1, pixels.height / 2].toArgb())
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
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED && compose.activity.hasWindowFocus()
            }
        }
    }

    private fun awaitSaves() {
        compose.waitUntil(10_000) { CompletionPersistence.shared(prefs).status.value != SaveStatus.SAVING }
    }

    private fun assertNoEarnedProgress() {
        val records = RecordsRepository(prefs).load()
        assertEquals(0, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        assertEquals(0L, records.achievements.completedSessions)
        assertTrue(records.achievements.unlocked.isEmpty())
        assertTrue(records.history.sessions.isEmpty())
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(screenshots, "$name.png").outputStream().use {
            assertTrue("Could not encode $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private data class ExpectedImage(val asset: ArcadeBackdrop, val width: Int, val height: Int, val sha256: String)
}
