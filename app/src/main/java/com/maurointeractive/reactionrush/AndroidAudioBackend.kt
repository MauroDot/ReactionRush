package com.maurointeractive.reactionrush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Foreground-only platform playback. All native state is confined to Main; neither asset
 * decoding nor audio readiness ever drives a gameplay clock or a gameplay transition.
 */
class AndroidAudioBackend(context: Context) : AudioBackend {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val effectAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        appContext.getSystemService(Vibrator::class.java)
    }

    @Volatile private var released = false
    private var focusListener: (Boolean) -> Unit = {}
    private var focusRequested = false
    private var hasFocus = false
    private var receiverRegistered = false
    private var player: MediaPlayer? = null
    private var track: MusicTrack? = null
    private var prepared = false
    private var musicVolume = 0f
    private var musicGeneration = 0L
    private var pool: SoundPool? = null
    private val sampleIds = mutableMapOf<Int, Int>()
    private val loadedSamples = mutableSetOf<Int>()
    private val streams = mutableListOf<PlayingStream>()
    private val celebrationToken = Any()
    private var celebrationGeneration = 0L
    private var celebrating = false

    private data class PlayingStream(
        val id: Int,
        val priority: Int,
        val expiresAt: Long,
        val celebration: Boolean
    )

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(musicAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (!released && focusRequested) {
                if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    hasFocus = true
                    focusListener(true)
                } else if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    hasFocus = false
                    stopAllInternal()
                    focusListener(false)
                }
            }
        }, main)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!released && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                stopAllInternal()
                abandonFocusInternal()
                focusListener(false)
            }
        }
    }

    init {
        onMain {
            preloadEffects()
            try {
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(noisyReceiver, filter)
                }
                receiverRegistered = true
            } catch (_: Exception) {
                // Audio is optional; a platform restriction must not prevent gameplay.
            }
        }
    }

    override fun setFocusListener(listener: (Boolean) -> Unit) {
        onMain { focusListener = listener }
    }

    override fun requestFocus(): Boolean {
        // The controller calls on Main. Never synchronously block another thread for audio.
        if (released || Looper.myLooper() != Looper.getMainLooper()) return false
        if (hasFocus) return true
        focusRequested = true
        hasFocus = try {
            audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
        if (!hasFocus) focusRequested = false
        return hasFocus
    }

    override fun abandonFocus() {
        onMain {
            // Audio channels may both be muted while touch feedback remains enabled.
            // Lifecycle/competitive interruption uses stopAll explicitly.
            clearMusic()
            stopEffectsInternal()
            abandonFocusInternal()
        }
    }

    override fun setMusic(track: MusicTrack?, volume: Float) {
        onMain {
            musicVolume = safeVolume(volume)
            if (track == null || musicVolume == 0f || !hasFocus) {
                clearMusic()
                return@onMain
            }
            if (this.track == track && player != null) {
                applyMusicVolume()
                return@onMain
            }
            clearMusic()
            val generation = musicGeneration
            val next = try { MediaPlayer() } catch (_: Exception) { return@onMain }
            this.track = track
            player = next
            try {
                next.setAudioAttributes(musicAttributes)
                next.setOnPreparedListener { ready ->
                    if (released || generation != musicGeneration || player !== ready || !hasFocus) {
                        releasePlayer(ready)
                    } else {
                        prepared = true
                        applyMusicVolume()
                        try { ready.start() } catch (_: Exception) { clearMusic() }
                    }
                }
                next.setOnErrorListener { failed, _, _ ->
                    if (player === failed && generation == musicGeneration) clearMusic()
                    else releasePlayer(failed)
                    true
                }
                appContext.resources.openRawResourceFd(AudioAssets.music.getValue(track).resourceId).use { file ->
                    next.setDataSource(file.fileDescriptor, file.startOffset, file.length)
                }
                next.isLooping = true
                next.setVolume(musicVolume, musicVolume)
                next.prepareAsync()
            } catch (_: Exception) {
                if (player === next) clearMusic() else releasePlayer(next)
            }
        }
    }

    override fun stopAll() {
        onMain { stopAllInternal() }
    }

    override fun stopEffects() {
        onMain { stopEffectsInternal() }
    }

    override fun playEffect(effect: SoundEffect, volume: Float, priority: Int) {
        onMain {
            playSample(AudioAssets.effects.getValue(effect), volume, priority.coerceIn(0, 100), false)
        }
    }

    override fun celebrate(
        leadEffect: SoundEffect?,
        stinger: CelebrationStinger,
        volume: Float,
        priority: Int
    ) {
        onMain {
            cancelCelebration()
            if (!hasFocus || safeVolume(volume) == 0f) return@onMain
            val generation = celebrationGeneration
            val rank = priority.coerceIn(50, 100)
            celebrating = true
            applyMusicVolume()
            val lead = leadEffect?.let { AudioAssets.effects.getValue(it) }
            val leadStream = lead?.let { playSample(it, volume, rank, true) } ?: 0
            val delay = if (leadStream != 0) lead!!.durationMillis + CELEBRATION_GAP_MS else 0L
            main.postAtTime({
                if (!released && hasFocus && generation == celebrationGeneration) {
                    stopCelebrationStreams()
                    val asset = AudioAssets.stingers.getValue(stinger)
                    val stream = playSample(asset, volume, rank, true)
                    val finishAfter = if (stream != 0) asset.durationMillis + CELEBRATION_GAP_MS else 0L
                    main.postAtTime({
                        if (!released && generation == celebrationGeneration) {
                            stopCelebrationStreams()
                            celebrating = false
                            applyMusicVolume()
                        }
                    }, celebrationToken, SystemClock.uptimeMillis() + finishAfter)
                }
            }, celebrationToken, SystemClock.uptimeMillis() + delay)
        }
    }

    override fun haptic(durationMs: Long) {
        onMain {
            if (durationMs <= 0 || vibrator?.hasVibrator() != true) return@onMain
            try {
                val effect = VibrationEffect.createOneShot(durationMs.coerceAtMost(200), VibrationEffect.DEFAULT_AMPLITUDE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, effectAttributes)
                }
            } catch (_: Exception) {
                // Missing hardware or system vibration policy is a supported silent outcome.
            }
        }
    }

    override fun cancelHaptics() {
        onMain { cancelHapticsInternal() }
    }

    override fun release() {
        if (released) return
        released = true // Immediately invalidate any native callbacks already enqueued on Main.
        val dispose = Runnable {
            stopAllInternal()
            abandonFocusInternal()
            focusListener = {}
            if (receiverRegistered) {
                try { appContext.unregisterReceiver(noisyReceiver) } catch (_: Exception) { }
                receiverRegistered = false
            }
            try { pool?.setOnLoadCompleteListener(null); pool?.release() } catch (_: Exception) { }
            pool = null
            sampleIds.clear()
            loadedSamples.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) dispose.run() else main.post(dispose)
    }

    private fun preloadEffects() {
        try {
            val created = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(effectAttributes).build()
            pool = created
            created.setOnLoadCompleteListener { _, sample, status ->
                if (!released && status == 0) loadedSamples += sample
            }
            // GO is deliberately absent from both playable maps and from SoundPool preloading.
            (AudioAssets.effects.values + AudioAssets.stingers.values).forEach { asset ->
                val sample = created.load(appContext, asset.resourceId, 1)
                if (sample != 0) sampleIds[asset.resourceId] = sample
            }
        } catch (_: Exception) {
            try { pool?.release() } catch (_: Exception) { }
            pool = null
            sampleIds.clear()
            loadedSamples.clear()
        }
    }

    private fun playSample(asset: AudioAsset, volume: Float, priority: Int, celebration: Boolean): Int {
        if (released || !hasFocus) return 0
        val amplitude = safeVolume(volume)
        if (amplitude == 0f) return 0
        val soundPool = pool ?: return 0
        val sample = sampleIds[asset.resourceId] ?: return 0
        if (sample !in loadedSamples) return 0 // Never defer an effect beyond its verified event.
        val now = SystemClock.uptimeMillis()
        // SoundPool has no completion callback. Explicitly stop expired IDs before forgetting
        // them, with a short output-buffer allowance, so stopAll never loses a live stream.
        streams.filter { it.expiresAt <= now }.forEach { try { soundPool.stop(it.id) } catch (_: Exception) { } }
        streams.removeAll { it.expiresAt <= now }
        if (streams.size >= MAX_STREAMS) {
            val victim = streams.minWithOrNull(compareBy<PlayingStream> { it.priority }.thenBy { it.expiresAt })!!
            if (victim.priority > priority || (victim.celebration && !celebration)) return 0
            try { soundPool.stop(victim.id) } catch (_: Exception) { }
            streams.remove(victim)
        }
        val id = try { soundPool.play(sample, amplitude, amplitude, priority, 0, 1f) } catch (_: Exception) { 0 }
        if (id != 0) streams += PlayingStream(id, priority, now + asset.durationMillis + STREAM_RELEASE_MARGIN_MS, celebration)
        return id
    }

    private fun clearMusic() {
        musicGeneration++
        val previous = player
        val wasPrepared = prepared
        player = null
        track = null
        prepared = false
        if (previous != null) {
            if (wasPrepared) try { previous.setVolume(0f, 0f) } catch (_: Exception) { }
            releasePlayer(previous)
        }
    }

    private fun releasePlayer(previous: MediaPlayer) {
        try { previous.setOnPreparedListener(null); previous.setOnErrorListener(null) } catch (_: Exception) { }
        try { previous.release() } catch (_: Exception) { }
    }

    private fun applyMusicVolume() {
        if (!prepared) return
        val volume = musicVolume * if (celebrating) 0.35f else 1f
        try { player?.setVolume(volume, volume) } catch (_: Exception) { }
    }

    private fun cancelCelebration() {
        celebrationGeneration++
        main.removeCallbacksAndMessages(celebrationToken)
        stopCelebrationStreams()
        celebrating = false
        applyMusicVolume()
    }

    private fun stopCelebrationStreams() {
        streams.filter { it.celebration }.forEach { try { pool?.stop(it.id) } catch (_: Exception) { } }
        streams.removeAll { it.celebration }
    }

    private fun stopAllInternal() {
        clearMusic()
        stopEffectsInternal()
        cancelHapticsInternal()
    }

    private fun stopEffectsInternal() {
        cancelCelebration()
        streams.forEach { try { pool?.stop(it.id) } catch (_: Exception) { } }
        streams.clear()
    }

    private fun abandonFocusInternal() {
        val requested = focusRequested
        focusRequested = false
        hasFocus = false
        if (requested) try { audioManager?.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) { }
    }

    private fun cancelHapticsInternal() {
        try { vibrator?.cancel() } catch (_: Exception) { }
    }

    private fun onMain(action: () -> Unit) {
        if (released) return
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else main.post { if (!released) action() }
    }

    private fun safeVolume(volume: Float): Float = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 0f

    private companion object {
        const val MAX_STREAMS = 4
        const val CELEBRATION_GAP_MS = 40L
        const val STREAM_RELEASE_MARGIN_MS = 100L
    }
}
