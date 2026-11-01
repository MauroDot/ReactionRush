package com.maurointeractive.reactionrush

enum class MusicTrack { MENU, QUICK_DRAW, CLASSIC, ENDURANCE, RESULTS }

/** GO is intentionally absent: these are visual-only competitive modes. */
enum class SoundEffect {
    MENU_NAVIGATE, BUTTON_SELECT, ROUND_PREPARE, REACTION_SUCCESS, FALSE_START,
    NEW_RECORD, MEDAL_AWARDED, ACHIEVEMENT_UNLOCK, LEVEL_UP
}

enum class CelebrationStinger { ACHIEVEMENT, LEVEL_UP, PERSONAL_RECORD, VICTORY, MILESTONE }

/** Main-thread port. Implementations invalidate their asynchronous work when stopped/released. */
interface AudioBackend {
    fun setFocusListener(listener: (Boolean) -> Unit)
    fun requestFocus(): Boolean
    fun abandonFocus()
    fun setMusic(track: MusicTrack?, volume: Float)
    fun stopAll()
    fun stopEffects()
    fun playEffect(effect: SoundEffect, volume: Float, priority: Int)
    fun celebrate(leadEffect: SoundEffect?, stinger: CelebrationStinger, volume: Float, priority: Int)
    fun haptic(durationMs: Long)
    fun cancelHaptics()
    fun release()
}

/**
 * Audio policy only: it never reads a clock, changes a score, or schedules a GO signal.
 * The Compose owner and persisted session token must both match before a game cue is accepted.
 * Background/focus return restores only eligible music; one-shot cues are never replayed.
 */
class AudioController(
    private val backend: AudioBackend,
    initialSettings: AudioSettings = AudioSettings()
) {
    private var settings = initialSettings.normalized()
    private var foreground = false
    private var released = false
    private var hasFocus = false
    private var focusRequested = false
    private var requestingFocus = false
    private var abandoningFocus = false
    private var focusSuspended = false
    private var owner: Any? = null
    private var mode: GameMode? = null
    private var phase = Phase.NONE
    private var desiredMusic: MusicTrack? = null
    private var appliedMusic: Pair<MusicTrack, Float>? = null
    private var intervalValid = false
    private var goHapticPlayed = false
    private var activeSession: SessionEvents? = null
    private val retiredSessions = ArrayDeque<String>()
    private val retiredOwners = ArrayDeque<Any>()

    init {
        safely { backend.setFocusListener(::focusChanged) }
    }

    fun updateSettings(value: AudioSettings) {
        if (released) return
        val next = value.normalized()
        if (settings == next) return
        val previous = settings
        settings = next
        // Existing SoundPool streams have no group-volume setter. Cancel only that channel
        // (including queued celebrations); music retains its playback position.
        if (previous.sfxEnabled != next.sfxEnabled || previous.sfxVolume != next.sfxVolume) {
            safely { backend.stopEffects() }
        }
        if (!next.hapticsEnabled) safely { backend.cancelHaptics() }
        val musicChanged = previous.musicEnabled != next.musicEnabled || previous.musicVolume != next.musicVolume
        // A deliberate enable/unmute/positive-volume change is a new playback intent.
        // Background and interruption gating still prevent focus requests or playback.
        if (musicChanged && musicAudible()) focusSuspended = false
        if (musicChanged) applyMusic()
        if (!musicAudible() && !sfxAudible()) dropFocus()
    }

    fun setForeground(value: Boolean) {
        if (released || foreground == value) return
        foreground = value
        if (!value) {
            intervalValid = false
            if (phase == Phase.COMPETITIVE || activeSession?.completed == false) {
                retireSession()
                phase = Phase.INTERRUPTED
            }
            stopPlayback()
            dropFocus()
        } else {
            focusSuspended = false
            applyMusic()
        }
    }

    fun showMenu() {
        if (released) return
        if (owner == null && desiredMusic == MusicTrack.MENU && phase == Phase.NONE) return
        invalidateOwner()
        phase = Phase.NONE
        desiredMusic = MusicTrack.MENU
        focusSuspended = false
        applyMusic()
    }

    fun enterGame(gameOwner: Any, gameMode: GameMode) {
        if (released || retiredOwners.any { it === gameOwner }) return
        if (owner === gameOwner) {
            if (mode == gameMode) return
            // An owner is tied to one mode; a new composition must use a new identity.
            return
        }
        invalidateOwner()
        owner = gameOwner
        mode = gameMode
        phase = Phase.READY
        desiredMusic = musicFor(gameMode)
        focusSuspended = false
        applyMusic()
        effect(SoundEffect.ROUND_PREPARE, NAVIGATION_PRIORITY)
    }

    fun leaveGame(gameOwner: Any) {
        if (!owns(gameOwner)) return
        invalidateOwner()
        desiredMusic = null
        phase = Phase.NONE
    }

    /** Keeps the screen owner, but requires a fresh deliberate session before any audio. */
    fun interrupt(gameOwner: Any) {
        if (!owns(gameOwner)) return
        retireSession()
        phase = Phase.INTERRUPTED
        intervalValid = false
        stopPlayback()
        dropFocus()
    }

    /** Clears transient cues before WAITING while keeping the music route unchanged at GO. */
    fun beginCompetitive(gameOwner: Any, sessionId: String = "") {
        if (!owns(gameOwner)) return
        val previousPhase = phase
        if (sessionId.isNotBlank()) {
            if (sessionId in retiredSessions) return
            val current = activeSession
            if (current?.id != sessionId) {
                if (current != null || previousPhase !in setOf(Phase.READY, Phase.INTERRUPTED)) return
                activeSession = SessionEvents(sessionId)
            }
            if (activeSession?.completed == true) return
        } else if (activeSession?.completed == true) return
        if (phase == Phase.COMPETITIVE && intervalValid) return
        intervalValid = false
        goHapticPlayed = false
        phase = Phase.COMPETITIVE
        safely { backend.stopEffects() }
        safely { backend.cancelHaptics() }
        applyMusic()
        if (!musicAudible()) dropFocus()
        intervalValid = foreground
    }

    /** Preserves the existing 35 ms GO haptic after the caller has captured its timestamp. */
    fun goHaptic(gameOwner: Any) {
        if (!owns(gameOwner) || phase != Phase.COMPETITIVE || !intervalValid || goHapticPlayed) return
        goHapticPlayed = true
        haptic(35)
    }

    fun falseStart(gameOwner: Any, sessionId: String, falseStartCount: Int) {
        if (!acceptsInterval(gameOwner) || falseStartCount <= 0) return
        val session = sessionForEvent(sessionId) ?: return
        if (session.completed || falseStartCount <= session.falseStarts) return
        session.falseStarts = falseStartCount
        intervalValid = false
        phase = Phase.FALSE_START
        desiredMusic = mode?.let(::musicFor)
        applyMusic()
        haptic(90)
        effect(SoundEffect.FALSE_START, FALSE_START_PRIORITY)
    }

    /** Called after timestamp capture and the existing repository's reaction transaction. */
    fun reaction(gameOwner: Any, sessionId: String, round: Int, update: ReactionUpdate) {
        if (!acceptsInterval(gameOwner)) return
        val gameMode = mode ?: return
        val session = sessionForEvent(sessionId) ?: return
        if (session.completed || round != session.rounds + 1 || round !in 1..gameMode.rounds) return
        val reward = update.reward
        // A final round without a repository reward is not a verified completion.
        if ((round == gameMode.rounds) != (reward != null)) return
        session.rounds = round
        intervalValid = false
        phase = if (reward == null) Phase.ROUND_RESULT else Phase.COMPLETED
        session.completed = reward != null
        desiredMusic = if (reward == null) musicFor(gameMode) else MusicTrack.RESULTS
        applyMusic()
        haptic(45)
        effect(SoundEffect.REACTION_SUCCESS, REACTION_PRIORITY)
        if (reward != null) {
            val cue = completionCue(update.isNewRecord, reward)
            if (canPlaySound()) safely {
                backend.celebrate(cue.first, cue.second, settings.sfxVolume, CELEBRATION_PRIORITY)
            }
        } else if (update.isNewRecord) effect(SoundEffect.NEW_RECORD, RECORD_PRIORITY)
    }

    fun showResults(gameOwner: Any) {
        if (!owns(gameOwner) || activeSession?.completed != true || transientsBlocked()) return
        phase = Phase.COMPLETED
        desiredMusic = MusicTrack.RESULTS
        applyMusic()
    }

    fun ready(gameOwner: Any, gameMode: GameMode) {
        if (!owns(gameOwner) || mode != gameMode || phase == Phase.READY) return
        retireSession()
        intervalValid = false
        phase = Phase.READY
        desiredMusic = musicFor(gameMode)
        focusSuspended = false
        safely { backend.stopEffects() }
        safely { backend.cancelHaptics() }
        applyMusic()
        effect(SoundEffect.ROUND_PREPARE, NAVIGATION_PRIORITY)
    }

    fun navigate() = navigationEffect(SoundEffect.MENU_NAVIGATE)
    fun select() = navigationEffect(SoundEffect.BUTTON_SELECT)

    fun release() {
        if (released) return
        released = true
        intervalValid = false
        owner = null
        activeSession = null
        retiredOwners.clear()
        retiredSessions.clear()
        desiredMusic = null
        stopPlayback()
        dropFocus()
        safely { backend.release() }
    }

    private fun navigationEffect(sound: SoundEffect) {
        if (released || !foreground || transientsBlocked()) return
        focusSuspended = false // A new deliberate user action may request focus again.
        effect(sound, NAVIGATION_PRIORITY)
        applyMusic()
    }

    private fun owns(gameOwner: Any): Boolean = !released && owner === gameOwner

    private fun acceptsInterval(gameOwner: Any): Boolean =
        owns(gameOwner) && foreground && phase == Phase.COMPETITIVE && intervalValid

    private fun sessionForEvent(id: String): SessionEvents? {
        if (id.isBlank() || id in retiredSessions) return null
        val current = activeSession
        if (current != null) return current.takeIf { it.id == id }
        // Compatibility for callers omitting beginCompetitive's token. Production passes it.
        return SessionEvents(id).also { activeSession = it }
    }

    private fun retireSession() {
        activeSession?.id?.let { boundedAdd(retiredSessions, it) }
        activeSession = null
    }

    private fun invalidateOwner() {
        owner?.let { boundedAdd(retiredOwners, it) }
        owner = null
        mode = null
        activeSession = null
        retiredSessions.clear()
        intervalValid = false
        stopPlayback()
        dropFocus()
    }

    private fun stopPlayback() {
        appliedMusic = null
        safely { backend.stopAll() }
    }

    private fun transientsBlocked(): Boolean = phase == Phase.COMPETITIVE || phase == Phase.INTERRUPTED
    private fun musicBlocked(): Boolean = phase == Phase.INTERRUPTED
    private fun musicAudible(): Boolean = settings.musicEnabled && settings.musicVolume > 0f
    private fun sfxAudible(): Boolean = settings.sfxEnabled && settings.sfxVolume > 0f

    private fun applyMusic() {
        if (released || !foreground || musicBlocked() || !musicAudible() || desiredMusic == null) {
            if (appliedMusic != null) safely { backend.setMusic(null, 0f) }
            appliedMusic = null
            return
        }
        if (!ensureFocus()) return
        val target = checkNotNull(desiredMusic) to settings.musicVolume
        if (appliedMusic == target) return
        appliedMusic = target
        safely { backend.setMusic(target.first, target.second) }
    }

    private fun canPlaySound(): Boolean =
        !released && foreground && !transientsBlocked() && sfxAudible() && ensureFocus()

    private fun effect(sound: SoundEffect, priority: Int) {
        if (canPlaySound()) safely { backend.playEffect(sound, settings.sfxVolume, priority) }
    }

    private fun haptic(duration: Long) {
        if (!released && foreground && settings.hapticsEnabled) safely { backend.haptic(duration) }
    }

    private fun ensureFocus(): Boolean {
        if (released || !foreground || musicBlocked() || focusSuspended) return false
        if (hasFocus) return true
        if (requestingFocus) return false
        requestingFocus = true
        focusRequested = true
        val granted = try { backend.requestFocus() } catch (_: Exception) { false }
        requestingFocus = false
        if (released || !foreground || musicBlocked()) {
            dropFocus()
            return false
        }
        hasFocus = granted
        if (!granted) {
            focusRequested = false
            focusSuspended = true
        }
        return granted
    }

    private fun focusChanged(gained: Boolean) {
        if (released || abandoningFocus) return
        hasFocus = gained
        focusSuspended = !gained
        if (!gained) {
            stopPlayback()
            return
        }
        focusRequested = true
        if (!foreground || musicBlocked()) {
            stopPlayback()
            dropFocus()
        } else if (!requestingFocus) applyMusic()
    }

    private fun dropFocus() {
        hasFocus = false
        if (!focusRequested) return
        focusRequested = false
        abandoningFocus = true
        try { safely { backend.abandonFocus() } } finally { abandoningFocus = false }
    }

    /** Exactly one lead/stinger pair; lower-priority simultaneous celebrations are superseded. */
    private fun completionCue(newRecord: Boolean, reward: SessionReward): Pair<SoundEffect?, CelebrationStinger> = when {
        reward.newLevel > reward.previousLevel -> SoundEffect.LEVEL_UP to CelebrationStinger.LEVEL_UP
        reward.newlyUnlocked.any { it in MILESTONES } -> SoundEffect.ACHIEVEMENT_UNLOCK to CelebrationStinger.MILESTONE
        reward.newlyUnlocked.isNotEmpty() -> SoundEffect.ACHIEVEMENT_UNLOCK to CelebrationStinger.ACHIEVEMENT
        newRecord -> SoundEffect.NEW_RECORD to CelebrationStinger.PERSONAL_RECORD
        reward.medal != PerformanceMedal.UNRANKED -> SoundEffect.MEDAL_AWARDED to CelebrationStinger.VICTORY
        else -> null to CelebrationStinger.VICTORY
    }

    private inline fun safely(action: () -> Unit) {
        try { action() } catch (_: Exception) { /* Audio failure must not break gameplay. */ }
    }

    private fun <T> boundedAdd(queue: ArrayDeque<T>, value: T) {
        queue.addLast(value)
        if (queue.size > RETIRED_LIMIT) queue.removeFirst()
    }

    private data class SessionEvents(val id: String, var rounds: Int = 0, var falseStarts: Int = 0, var completed: Boolean = false)
    private enum class Phase { NONE, READY, COMPETITIVE, FALSE_START, ROUND_RESULT, COMPLETED, INTERRUPTED }

    private companion object {
        const val RETIRED_LIMIT = 32
        const val NAVIGATION_PRIORITY = 1
        const val REACTION_PRIORITY = 2
        const val FALSE_START_PRIORITY = 4
        const val RECORD_PRIORITY = 5
        const val CELEBRATION_PRIORITY = 10
        val MILESTONES = setOf(
            Achievement.GETTING_STARTED, Achievement.DEDICATED, Achievement.VETERAN,
            Achievement.RISING_STAR, Achievement.REFLEX_MASTER, Achievement.LEGEND
        )

        fun musicFor(mode: GameMode): MusicTrack = when (mode) {
            GameMode.QUICK_DRAW -> MusicTrack.QUICK_DRAW
            GameMode.CLASSIC_RUSH -> MusicTrack.CLASSIC
            GameMode.ENDURANCE_RUSH -> MusicTrack.ENDURANCE
        }
    }
}
