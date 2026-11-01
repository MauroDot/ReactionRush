package com.maurointeractive.reactionrush

/** Independent playback preferences; these never participate in scoring or reward calculation. */
data class AudioSettings(
    val musicEnabled: Boolean = true,
    val musicVolume: Float = DEFAULT_MUSIC_VOLUME,
    val sfxEnabled: Boolean = true,
    val sfxVolume: Float = DEFAULT_SFX_VOLUME,
    val hapticsEnabled: Boolean = true
) {
    fun normalized(): AudioSettings = copy(
        musicVolume = musicVolume.safeVolume(DEFAULT_MUSIC_VOLUME),
        sfxVolume = sfxVolume.safeVolume(DEFAULT_SFX_VOLUME)
    )

    internal fun savedValues(): Map<String, Any> = mapOf(
        MUSIC_ENABLED to musicEnabled,
        MUSIC_VOLUME to musicVolume,
        SFX_ENABLED to sfxEnabled,
        SFX_VOLUME to sfxVolume,
        HAPTICS_ENABLED to hapticsEnabled
    )

    companion object {
        const val DEFAULT_MUSIC_VOLUME = 0.45f
        const val DEFAULT_SFX_VOLUME = 0.7f

        internal const val MUSIC_ENABLED = "audio_music_enabled"
        internal const val MUSIC_VOLUME = "audio_music_volume"
        internal const val SFX_ENABLED = "audio_sfx_enabled"
        internal const val SFX_VOLUME = "audio_sfx_volume"
        internal const val HAPTICS_ENABLED = "audio_haptics_enabled"

        internal fun fromPreferences(saved: Map<String, *>): AudioSettings = AudioSettings(
            musicEnabled = saved[MUSIC_ENABLED] as? Boolean ?: true,
            musicVolume = saved[MUSIC_VOLUME] as? Float ?: DEFAULT_MUSIC_VOLUME,
            sfxEnabled = saved[SFX_ENABLED] as? Boolean ?: true,
            sfxVolume = saved[SFX_VOLUME] as? Float ?: DEFAULT_SFX_VOLUME,
            hapticsEnabled = saved[HAPTICS_ENABLED] as? Boolean ?: true
        ).normalized()

        private fun Float.safeVolume(fallback: Float): Float =
            if (isFinite()) coerceIn(0f, 1f) else fallback
    }
}
