package com.maurointeractive.reactionrush

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Looper
import android.os.VibratorManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidAudioBackendTest {
    private lateinit var context: Context
    private lateinit var backend: AndroidAudioBackend
    private val players = mutableListOf<MediaPlayer>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        ShadowMediaPlayer.setMediaInfoProvider {
            ShadowMediaPlayer.MediaInfo().apply {
                duration = 65_000
                preparationDelay = 1_000
            }
        }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        backend = AndroidAudioBackend(context)
        assertTrue(backend.requestFocus())
    }

    @After
    fun tearDown() {
        backend.release()
        ShadowMediaPlayer.resetStaticState()
    }

    @Test
    fun stopDuringPreparationRejectsAnAlreadyQueuedPreparedCallback() {
        backend.setMusic(MusicTrack.MENU, 0.4f)
        val old = players.single()
        val queuedCallback = shadowOf(old).onPreparedListener!!
        assertEquals(ShadowMediaPlayer.State.PREPARING, shadowOf(old).state)

        backend.stopAll()
        queuedCallback.onPrepared(old)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(old).state)
        assertFalse(shadowOf(old).isReallyPlaying)
        assertEquals(1, players.size)
    }

    @Test
    fun anObsoletePreparedCallbackCannotReplaceTheNewMusic() {
        backend.setMusic(MusicTrack.MENU, 0.4f)
        val old = players.single()
        val oldCallback = shadowOf(old).onPreparedListener!!

        backend.setMusic(MusicTrack.CLASSIC, 0.2f)
        val current = players.last()
        oldCallback.onPrepared(old)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(2, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(old).state)
        assertTrue(shadowOf(current).isReallyPlaying)
        assertTrue(current.isLooping)
        assertEquals(0.2f, shadowOf(current).leftVolume, 0.0001f)
    }

    @Test
    fun releaseRejectsPreparedCallbacksAndDoesNotRecreatePlayers() {
        backend.setMusic(MusicTrack.ENDURANCE, 0.5f)
        val old = players.single()
        val queuedCallback = shadowOf(old).onPreparedListener!!

        backend.release()
        queuedCallback.onPrepared(old)
        backend.setMusic(MusicTrack.RESULTS, 0.5f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertFalse(backend.requestFocus())
        assertEquals(1, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(old).state)
        assertFalse(shadowOf(old).isReallyPlaying)
    }

    @Test
    fun noisyRouteChangeStopsPreparingMusicAndAbandonsFocus() {
        val focusEvents = mutableListOf<Boolean>()
        backend.setFocusListener { focusEvents += it }
        backend.setMusic(MusicTrack.MENU, 0.5f)
        val old = players.single()
        val queuedCallback = shadowOf(old).onPreparedListener!!

        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        queuedCallback.onPrepared(old)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(listOf(false), focusEvents)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(old).state)
        assertFalse(shadowOf(old).isReallyPlaying)
        assertNotNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun focusLossStopsPlaybackAndLateGainAfterAbandonCannotRestartIt() {
        val focusEvents = mutableListOf<Boolean>()
        backend.setFocusListener { focusEvents += it }
        backend.setMusic(MusicTrack.QUICK_DRAW, 0.5f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val old = players.single()
        assertTrue(shadowOf(old).isReallyPlaying)
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest.listener

        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        backend.abandonFocus()
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(listOf(false), focusEvents)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(old).state)
        assertFalse(shadowOf(old).isReallyPlaying)
        assertEquals(1, players.size)
    }

    @Test
    fun stoppingEffectsCancelsQueuedStingersWithoutRestartingMusic() {
        backend.setMusic(MusicTrack.MENU, 0.4f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val music = players.single()
        val soundPool = ReflectionHelpers.getField<SoundPool>(backend, "pool")
        val effects = shadowOf(soundPool)
        (AudioAssets.effects.values + AudioAssets.stingers.values).forEach {
            effects.notifyResourceLoaded(it.resourceId, true)
        }
        backend.celebrate(SoundEffect.NEW_RECORD, CelebrationStinger.PERSONAL_RECORD, 0.7f, 100)
        assertTrue(effects.wasResourcePlayed(AudioAssets.effects.getValue(SoundEffect.NEW_RECORD).resourceId))
        val position = music.currentPosition

        backend.stopEffects()

        assertTrue(shadowOf(music).isReallyPlaying)
        assertEquals(position, music.currentPosition)
        assertEquals(0.4f, shadowOf(music).leftVolume, 0.0001f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(1, players.size)
        assertTrue(shadowOf(music).isReallyPlaying)
        assertFalse(effects.wasResourcePlayed(AudioAssets.stingers.getValue(CelebrationStinger.PERSONAL_RECORD).resourceId))
    }

    @Test
    fun abandoningAudioFocusPreservesHapticsButExplicitStopCancelsThem() {
        val vibration = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)
        vibration.setHasVibrator(true)
        backend.haptic(200)
        assertTrue(vibration.isVibrating)

        backend.abandonFocus()

        assertTrue(vibration.isVibrating)
        backend.stopAll()
        assertFalse(vibration.isVibrating)
        assertTrue(vibration.isCancelled)
    }

    @Test
    fun quickDrawKeepsThePreparedPlayerAndPlayheadUntilVerifiedCompletion() {
        assertContinuousSession(GameMode.QUICK_DRAW)
    }

    @Test
    fun classicKeepsThePreparedPlayerAndPlayheadAcrossEveryRound() {
        assertContinuousSession(GameMode.CLASSIC_RUSH)
    }

    @Test
    fun enduranceKeepsThePreparedPlayerAndPlayheadAcrossEveryRound() {
        assertContinuousSession(GameMode.ENDURANCE_RUSH)
    }

    @Test
    fun preparationMayFinishDuringWaitingWithoutBeingRestartedAtGo() {
        val controller = AudioController(backend)
        val owner = Any()
        controller.setForeground(true)
        controller.enterGame(owner, GameMode.CLASSIC_RUSH)
        val music = players.single()
        assertEquals(ShadowMediaPlayer.State.PREPARING, shadowOf(music).state)

        controller.beginCompetitive(owner, "preparing")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_400))

        assertTrue(shadowOf(music).isReallyPlaying)
        assertTrue(music.currentPosition > 0)
        val position = music.currentPosition
        controller.goHaptic(owner)
        assertSame(music, players.single())
        assertEquals(position, music.currentPosition)
        assertEquals(AudioSettings.DEFAULT_MUSIC_VOLUME, shadowOf(music).leftVolume, 0.0001f)
        controller.release()
    }

    @Test
    fun waitingCancelsNativeCelebrationAndPreparationHapticsButPreservesMusic() {
        val controller = AudioController(backend)
        val owner = Any()
        controller.setForeground(true)
        controller.enterGame(owner, GameMode.ENDURANCE_RUSH)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_700))
        val music = players.single()
        val effects = shadowOf(ReflectionHelpers.getField<SoundPool>(backend, "pool"))
        (AudioAssets.effects.values + AudioAssets.stingers.values).forEach {
            effects.notifyResourceLoaded(it.resourceId, true)
        }
        val vibration = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)
        vibration.setHasVibrator(true)
        backend.haptic(200)
        backend.celebrate(SoundEffect.NEW_RECORD, CelebrationStinger.PERSONAL_RECORD, 0.7f, 100)
        assertTrue(vibration.isVibrating)
        assertTrue(shadowOf(music).leftVolume < AudioSettings.DEFAULT_MUSIC_VOLUME)
        val position = music.currentPosition

        controller.beginCompetitive(owner, "cancel-transients")

        assertSame(music, players.single())
        assertEquals(position, music.currentPosition)
        assertTrue(shadowOf(music).isReallyPlaying)
        assertFalse(vibration.isVibrating)
        assertEquals(AudioSettings.DEFAULT_MUSIC_VOLUME, shadowOf(music).leftVolume, 0.0001f)
        assertTrue(ReflectionHelpers.getField<List<*>>(backend, "streams").isEmpty())
        controller.select()
        controller.navigate()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertSame(music, players.single())
        assertTrue(shadowOf(music).isReallyPlaying)
        assertFalse(effects.wasResourcePlayed(AudioAssets.effects.getValue(SoundEffect.BUTTON_SELECT).resourceId))
        assertFalse(effects.wasResourcePlayed(AudioAssets.effects.getValue(SoundEffect.MENU_NAVIGATE).resourceId))
        assertFalse(effects.wasResourcePlayed(AudioAssets.stingers.getValue(CelebrationStinger.PERSONAL_RECORD).resourceId))
        controller.release()
    }

    @Test
    fun backgroundingDuringPreparationRejectsStaleCallbacksAndRequiresFreshReadiness() {
        val controller = AudioController(backend)
        val owner = Any()
        controller.setForeground(true)
        controller.enterGame(owner, GameMode.CLASSIC_RUSH)
        val old = players.single()
        val oldPrepared = shadowOf(old).onPreparedListener!!
        controller.beginCompetitive(owner, "interrupted")
        controller.falseStart(owner, "interrupted", 1)
        controller.beginCompetitive(owner, "interrupted")

        controller.setForeground(false)
        oldPrepared.onPrepared(old)
        controller.setForeground(true)
        controller.goHaptic(owner)
        controller.reaction(owner, "interrupted", 1, reaction())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(old).state)
        assertFalse(shadowOf(old).isReallyPlaying)
        assertEquals(1, players.size)
        controller.ready(owner, GameMode.CLASSIC_RUSH)
        val fresh = players.last()
        controller.beginCompetitive(owner, "fresh")
        oldPrepared.onPrepared(old)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, players.size)
        assertTrue(shadowOf(fresh).isReallyPlaying)
        assertFalse(shadowOf(old).isReallyPlaying)
        controller.release()
    }

    @Test
    fun explicitVolumeAndSfxChangesKeepThePlayingGameTrackWhileMusicMuteStopsIt() {
        val controller = AudioController(backend)
        val owner = Any()
        controller.setForeground(true)
        controller.enterGame(owner, GameMode.QUICK_DRAW)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_700))
        val music = players.single()
        controller.beginCompetitive(owner, "settings")
        val position = music.currentPosition

        controller.updateSettings(AudioSettings(musicVolume = 0.2f, sfxEnabled = false))
        controller.goHaptic(owner)

        assertSame(music, players.single())
        assertEquals(position, music.currentPosition)
        assertTrue(shadowOf(music).isReallyPlaying)
        assertEquals(0.2f, shadowOf(music).leftVolume, 0.0001f)
        controller.updateSettings(AudioSettings(musicEnabled = false, sfxEnabled = false))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(music).state)
        controller.updateSettings(AudioSettings(musicVolume = 0.2f, sfxEnabled = false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, players.size)
        assertTrue(shadowOf(players.last()).isReallyPlaying)
        assertEquals(0.2f, shadowOf(players.last()).leftVolume, 0.0001f)
        controller.release()
    }

    private fun assertContinuousSession(mode: GameMode) {
        val controller = AudioController(backend)
        val owner = Any()
        controller.setForeground(true)
        controller.enterGame(owner, mode)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_700))
        val music = players.single()
        val track = when (mode) {
            GameMode.QUICK_DRAW -> MusicTrack.QUICK_DRAW
            GameMode.CLASSIC_RUSH -> MusicTrack.CLASSIC
            GameMode.ENDURANCE_RUSH -> MusicTrack.ENDURANCE
        }
        assertEquals(track, ReflectionHelpers.getField<MusicTrack>(backend, "track"))
        assertTrue(music.currentPosition > 0)
        assertTrue(shadowOf(music).isReallyPlaying)
        val volume = shadowOf(music).leftVolume
        val position = music.currentPosition
        fun assertUnchangedPlayer() {
            assertSame(music, players.single())
            assertEquals(position, music.currentPosition)
            assertTrue(shadowOf(music).isReallyPlaying)
            assertEquals(volume, shadowOf(music).leftVolume, 0.0001f)
        }

        controller.ready(owner, mode)
        assertUnchangedPlayer()
        controller.beginCompetitive(owner, "continuous")
        assertUnchangedPlayer()
        controller.falseStart(owner, "continuous", 1)
        assertUnchangedPlayer()
        controller.beginCompetitive(owner, "continuous")
        assertUnchangedPlayer()
        repeat(mode.rounds) { index ->
            controller.goHaptic(owner)
            assertUnchangedPlayer()
            val final = index + 1 == mode.rounds
            if (final) {
                // A final-round callback without the existing repository reward is unverified.
                controller.reaction(owner, "continuous", index + 1, reaction())
                controller.showResults(owner)
                assertUnchangedPlayer()
            }
            controller.reaction(owner, "continuous", index + 1, reaction(completed = final))
            if (!final) {
                assertUnchangedPlayer()
                controller.beginCompetitive(owner, "continuous")
                assertUnchangedPlayer()
            }
        }
        assertEquals(2, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(music).state)
        assertEquals(MusicTrack.RESULTS, ReflectionHelpers.getField<MusicTrack>(backend, "track"))
        val results = players.last()
        controller.showResults(owner)
        controller.beginCompetitive(owner, "continuous")
        controller.reaction(owner, "continuous", mode.rounds, reaction(completed = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, players.size)
        assertTrue(shadowOf(results).isReallyPlaying)
        assertTrue(results.isLooping)
        controller.release()
    }

    private fun reaction(completed: Boolean = false) = ReactionUpdate(
        PersonalRecords(), false,
        if (completed) SessionReward(XpReward(10, 0, 0), 10, PerformanceMedal.GOLD, 1, 1, emptySet()) else null
    )
}
