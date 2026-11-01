package com.maurointeractive.reactionrush

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real packaged resources and native Android decoders; these tests never start playback. */
@RunWith(AndroidJUnit4::class)
class AudioAssetsAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun allTwentyPackagedAssetsMatchTheirApprovedBytesAndPlayableMappings() {
        assertEquals(20, AudioAssets.all.size)
        assertEquals(20, AudioAssets.all.map { it.resourceId }.toSet().size)
        assertEquals(MusicTrack.entries.toSet(), AudioAssets.music.keys)
        assertEquals(SoundEffect.entries.toSet(), AudioAssets.effects.keys)
        assertEquals(CelebrationStinger.entries.toSet(), AudioAssets.stingers.keys)
        assertEquals(5, AudioAssets.music.size)
        assertEquals(9, AudioAssets.effects.size)
        assertEquals(5, AudioAssets.stingers.size)
        val activeResources = AudioAssets.music.values + AudioAssets.effects.values + AudioAssets.stingers.values
        assertFalse(activeResources.any { it.resourceId == AudioAssets.reservedGoSignal.resourceId })

        AudioAssets.all.forEach { asset ->
            assertEquals(asset.fileName.substringBeforeLast('.'), context.resources.getResourceEntryName(asset.resourceId))
            val digest = MessageDigest.getInstance("SHA-256")
            context.resources.openRawResource(asset.resourceId).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    digest.update(buffer, 0, length)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(asset.fileName, asset.sha256, actual)
        }
    }

    @Test
    fun nativeMediaPlayerCanPrepareEveryPackagedAssetWithoutPlaying() {
        // Synchronous prepare is confined to the instrumentation worker, never the UI.
        AudioAssets.all.forEach { asset ->
            val player = MediaPlayer()
            try {
                context.resources.openRawResourceFd(asset.resourceId).use { file ->
                    player.setDataSource(file.fileDescriptor, file.startOffset, file.length)
                }
                player.prepare()
                assertTrue("No native duration for ${asset.fileName}", player.duration > 0)
                assertFalse("Validation must not play ${asset.fileName}", player.isPlaying)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun nativeSoundPoolPreloadsAllFourteenPlayableShortAssets() {
        val assets = AudioAssets.effects.values + AudioAssets.stingers.values
        val completed = CountDownLatch(assets.size)
        val statuses = ConcurrentHashMap<Int, Int>()
        val expected = mutableSetOf<Int>()
        var pool: SoundPool? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val created = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                ).build()
                pool = created
                created.setOnLoadCompleteListener { _, sample, status ->
                    statuses[sample] = status
                    completed.countDown()
                }
                assets.forEach { asset ->
                    val sample = created.load(context, asset.resourceId, 1)
                    assertTrue("Could not queue ${asset.fileName}", sample != 0)
                    expected += sample
                }
            }
            assertEquals(14, expected.size)
            assertTrue("Native sample decoding timed out", completed.await(15, TimeUnit.SECONDS))
            assertEquals(expected, statuses.keys)
            assertTrue("Native sample decoding failed: $statuses", statuses.values.all { it == 0 })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                pool?.setOnLoadCompleteListener(null)
                pool?.release()
            }
        }
    }

    @Test
    fun platformBackendReleaseIsTerminalAndIdempotent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val backend = AndroidAudioBackend(context)
            backend.stopAll()
            backend.cancelHaptics()
            backend.release()
            backend.release()
            assertFalse(backend.requestFocus())
            // Public calls after disposal must remain harmless and must not recreate players.
            backend.setMusic(MusicTrack.MENU, 1f)
            backend.playEffect(SoundEffect.BUTTON_SELECT, 1f, 1)
            backend.celebrate(SoundEffect.LEVEL_UP, CelebrationStinger.LEVEL_UP, 1f, 100)
            backend.haptic(20)
            backend.stopAll()
        }
    }
}
