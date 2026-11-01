package com.maurointeractive.reactionrush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioControllerTest {
    @Test
    fun constructionAndHydrationDoNotStartPlayback() {
        val backend = FakeBackend()
        val controller = AudioController(backend)
        controller.updateSettings(AudioSettings(musicVolume = 0.8f))
        controller.setForeground(true)
        assertTrue(backend.music.isEmpty())
        assertTrue(backend.effects.isEmpty())
        assertEquals(0, backend.focusRequests)
    }

    @Test
    fun menuAndRepeatedMenuNavigationUseOneMusicRoute() {
        val backend = FakeBackend()
        val controller = AudioController(backend)
        controller.showMenu()
        assertNull(backend.currentMusic)
        controller.setForeground(true)
        repeat(20) { controller.showMenu() }
        assertEquals(listOf(MusicTrack.MENU), backend.music.mapNotNull { it.track })
        assertEquals(AudioSettings.DEFAULT_MUSIC_VOLUME, backend.currentMusic!!.volume, 0f)
    }

    @Test
    fun everyModeHasItsOwnThemeAndPreparationOnlyOnEnteringReady() {
        val expected = listOf(MusicTrack.QUICK_DRAW, MusicTrack.CLASSIC, MusicTrack.ENDURANCE)
        GameMode.entries.forEachIndexed { index, mode ->
            val f = game(mode)
            repeat(10) { f.controller.enterGame(f.owner, mode); f.controller.ready(f.owner, mode) }
            assertEquals(expected[index], f.backend.currentMusic!!.track)
            assertEquals(listOf(SoundEffect.ROUND_PREPARE), f.backend.effects.map { it.sound })
            assertFalse(SoundEffect.entries.any { it.name == "GO" })
        }
    }

    @Test
    fun everyModeKeepsOneMusicRouteThroughReadyWaitingGoAndNonfinalRounds() {
        GameMode.entries.forEach { mode ->
            val f = game(mode)
            val music = f.backend.musicActivity()
            repeat(mode.rounds) { round ->
                val effectStops = f.backend.effectStops
                val hapticCancellations = f.backend.cancelHapticCalls
                f.controller.beginCompetitive(f.owner, "session")
                assertEquals(music, f.backend.musicActivity())
                assertEquals(effectStops + 1, f.backend.effectStops)
                assertEquals(hapticCancellations + 1, f.backend.cancelHapticCalls)
                val effects = f.backend.effects.size
                f.controller.navigate()
                f.controller.select()
                f.controller.showResults(f.owner)
                assertEquals(effects, f.backend.effects.size)
                assertTrue(f.backend.celebrations.isEmpty())

                val goOperations = f.backend.controlOperations()
                val haptics = f.backend.haptics.size
                f.controller.goHaptic(f.owner)
                assertEquals(goOperations, f.backend.controlOperations())
                assertEquals(haptics + 1, f.backend.haptics.size)
                assertEquals(35L, f.backend.haptics.last())
                f.controller.reaction(f.owner, "session", round + 1,
                    update(reward = if (round == mode.rounds - 1) reward() else null))
                if (round < mode.rounds - 1) assertEquals(music, f.backend.musicActivity())
            }
            assertEquals(MusicTrack.RESULTS, f.backend.currentMusic!!.track)
            assertEquals(2, f.backend.music.size)
            assertEquals(1, f.backend.celebrations.size)
        }
    }

    @Test
    fun falseStartRetryAndReadyKeepEveryModeMusicWithoutRestarting() {
        GameMode.entries.forEach { mode ->
            val f = game(mode)
            val music = f.backend.musicActivity()
            f.controller.beginCompetitive(f.owner, "failed")
            f.controller.falseStart(f.owner, "failed", 1)
            assertEquals(music, f.backend.musicActivity())
            f.controller.beginCompetitive(f.owner, "failed")
            assertEquals(music, f.backend.musicActivity())
            val goOperations = f.backend.controlOperations()
            f.controller.goHaptic(f.owner)
            assertEquals(goOperations, f.backend.controlOperations())
            f.controller.ready(f.owner, mode)
            assertEquals(music, f.backend.musicActivity())
            assertEquals(2, f.backend.effects.count { it.sound == SoundEffect.ROUND_PREPARE })
            f.controller.beginCompetitive(f.owner, "new")
            assertEquals(music, f.backend.musicActivity())
            assertTrue(f.backend.celebrations.isEmpty())
        }
    }

    @Test
    fun independentMusicAndSfxVolumesReachTheCorrectPlaybackPorts() {
        val f = game(settings = AudioSettings(musicVolume = 0.2f, sfxVolume = 0.8f))
        assertEquals(0.2f, f.backend.currentMusic!!.volume, 0f)
        assertEquals(0.8f, f.backend.effects.single().volume, 0f)
        f.controller.updateSettings(AudioSettings(musicVolume = 0.6f, sfxVolume = 0.3f))
        f.controller.select()
        assertEquals(0.6f, f.backend.currentMusic!!.volume, 0f)
        assertEquals(0.3f, f.backend.effects.last().volume, 0f)
    }

    @Test
    fun disabledAndZeroVolumeChannelsNeverPlayOrRequestFocus() {
        listOf(
            AudioSettings(musicEnabled = false, sfxEnabled = false, hapticsEnabled = false),
            AudioSettings(musicVolume = 0f, sfxVolume = 0f, hapticsEnabled = false)
        ).forEach { settings ->
            val f = game(settings = settings)
            f.controller.navigate()
            f.controller.select()
            complete(f)
            assertTrue(f.backend.music.isEmpty())
            assertTrue(f.backend.effects.isEmpty())
            assertTrue(f.backend.celebrations.isEmpty())
            assertTrue(f.backend.haptics.isEmpty())
            assertEquals(0, f.backend.focusRequests)
        }
    }

    @Test
    fun mutingSfxLeavesMusicUsable() {
        val f = game(settings = AudioSettings(sfxEnabled = false))
        complete(f)
        assertEquals(MusicTrack.RESULTS, f.backend.currentMusic!!.track)
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.celebrations.isEmpty())
    }

    @Test
    fun mutingMusicLeavesSfxUsable() {
        val f = game(settings = AudioSettings(musicEnabled = false))
        f.controller.select()
        complete(f)
        assertTrue(f.backend.music.isEmpty())
        assertEquals(SoundEffect.BUTTON_SELECT, f.backend.effects[1].sound)
        assertEquals(1, f.backend.celebrations.size)
    }

    @Test
    fun changingSfxSettingsCancelsExistingStreamsAndQueuedCelebrationsImmediately() {
        val f = game()
        complete(f)
        val before = f.backend.effectStops
        f.controller.updateSettings(AudioSettings(sfxEnabled = false, hapticsEnabled = false))
        assertTrue(f.backend.effectStops > before)
        assertTrue(f.backend.cancelHapticCalls > 0)
        assertEquals(MusicTrack.RESULTS, f.backend.currentMusic!!.track)
        assertFalse(f.backend.liveCelebration)
    }

    @Test
    fun sfxVolumeAndMuteChangesPreserveMusicAndOnlyCancelTheTransientChannel() {
        val f = game()
        complete(f)
        val playing = f.backend.currentMusic
        val musicStarts = f.backend.music.size
        val allStops = f.backend.stops
        val hapticCancellations = f.backend.cancelHapticCalls
        val effectStops = f.backend.effectStops
        f.controller.updateSettings(AudioSettings(sfxVolume = 0.2f))
        assertEquals(playing, f.backend.currentMusic)
        assertEquals(musicStarts, f.backend.music.size)
        assertEquals(allStops, f.backend.stops)
        assertEquals(hapticCancellations, f.backend.cancelHapticCalls)
        assertFalse(f.backend.liveCelebration)
        f.controller.select()
        assertEquals(0.2f, f.backend.effects.last().volume, 0f)
        val played = f.backend.effects.size
        f.controller.updateSettings(AudioSettings(sfxEnabled = false, sfxVolume = 0.2f))
        f.controller.select()
        assertEquals(played, f.backend.effects.size)
        f.controller.updateSettings(AudioSettings(sfxVolume = 0.4f))
        f.controller.select()
        assertEquals(0.4f, f.backend.effects.last().volume, 0f)
        assertEquals(playing, f.backend.currentMusic)
        assertEquals(musicStarts, f.backend.music.size)
        assertEquals(allStops, f.backend.stops)
        assertEquals(effectStops + 3, f.backend.effectStops)
    }

    @Test
    fun existingHapticDurationsArePreservedButGoCannotBuzzTwice() {
        val f = game()
        f.controller.beginCompetitive(f.owner, "session")
        repeat(10) { f.controller.goHaptic(f.owner) }
        f.controller.falseStart(f.owner, "session", 1)
        f.controller.beginCompetitive(f.owner, "session")
        f.controller.goHaptic(f.owner)
        f.controller.reaction(f.owner, "session", 1, update(reward = reward()))
        assertEquals(listOf(35L, 90L, 35L, 45L), f.backend.haptics)
    }

    @Test
    fun hapticsDisableAppliesDuringACompetitiveRoundAndRequiresNoAudioFocus() {
        val f = game(settings = AudioSettings(musicEnabled = false, sfxEnabled = false))
        f.controller.beginCompetitive(f.owner, "session")
        f.controller.updateSettings(AudioSettings(musicEnabled = false, sfxEnabled = false, hapticsEnabled = false))
        f.controller.goHaptic(f.owner)
        f.controller.reaction(f.owner, "session", 1, update(reward = reward()))
        assertTrue(f.backend.haptics.isEmpty())
        assertEquals(0, f.backend.focusRequests)
    }

    @Test
    fun backgroundStopsEverythingAndForegroundReplaysOnlyEligibleMusic() {
        val f = game()
        complete(f)
        f.backend.clearHistory()
        f.controller.setForeground(false)
        assertNull(f.backend.currentMusic)
        assertFalse(f.backend.liveCelebration)
        f.controller.setForeground(true)
        assertEquals(MusicTrack.RESULTS, f.backend.currentMusic!!.track)
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.celebrations.isEmpty())
        assertTrue(f.backend.haptics.isEmpty())
    }

    @Test
    fun backgroundDuringWaitingOrGoInvalidatesFalseStartRetryInEveryMode() {
        GameMode.entries.forEach { mode ->
            listOf(false, true).forEach { afterGo ->
                val f = game(mode)
                f.controller.beginCompetitive(f.owner, "old")
                f.controller.falseStart(f.owner, "old", 1)
                f.controller.beginCompetitive(f.owner, "old")
                if (afterGo) f.controller.goHaptic(f.owner)
                f.controller.setForeground(false)
                assertNull(f.backend.currentMusic)
                f.backend.clearHistory()
                repeat(3) {
                    f.controller.setForeground(true)
                    f.backend.focus(true)
                    f.controller.goHaptic(f.owner)
                    f.controller.falseStart(f.owner, "old", 2)
                    f.controller.reaction(f.owner, "old", 1,
                        update(reward = if (mode.rounds == 1) reward() else null))
                    f.controller.beginCompetitive(f.owner, "old")
                    assertNull(f.backend.currentMusic)
                    f.controller.setForeground(false)
                }
                assertTrue(f.backend.music.isEmpty())
                assertTrue(f.backend.effects.isEmpty())
                assertTrue(f.backend.celebrations.isEmpty())
                assertTrue(f.backend.haptics.isEmpty())
            }
        }
    }

    @Test
    fun externalFocusLossStopsAudioAndGainDoesNotReplayOneShotCues() {
        val f = game()
        f.backend.focus(false)
        f.backend.clearHistory()
        f.controller.updateSettings(AudioSettings(sfxVolume = 0.9f))
        assertNull(f.backend.currentMusic)
        assertTrue(f.backend.music.isEmpty())
        f.backend.focus(true)
        assertEquals(MusicTrack.QUICK_DRAW, f.backend.currentMusic!!.track)
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.celebrations.isEmpty())
    }

    @Test
    fun focusDeniedKeepsAudioSilentUntilAValidGain() {
        val backend = FakeBackend().apply { grantFocus = false }
        val controller = AudioController(backend)
        controller.setForeground(true)
        controller.showMenu()
        controller.updateSettings(AudioSettings(sfxVolume = 0.8f))
        assertTrue(backend.music.isEmpty())
        assertEquals(1, backend.focusRequests)
        backend.focus(true)
        assertEquals(MusicTrack.MENU, backend.currentMusic!!.track)
    }

    @Test
    fun deliberateMusicMuteAndUnmuteRetriesLostOrPreviouslyDeniedFocus() {
        listOf(true, false).forEach { initiallyGranted ->
            listOf(true, false).forEach { volumeMute ->
                val backend = FakeBackend().apply { grantFocus = initiallyGranted }
                val controller = AudioController(backend)
                controller.setForeground(true)
                controller.showMenu()
                if (initiallyGranted) backend.focus(false)
                backend.grantFocus = true
                val before = backend.focusRequests
                val muted = if (volumeMute) AudioSettings(musicVolume = 0f)
                    else AudioSettings(musicEnabled = false)
                controller.updateSettings(muted)
                assertNull(backend.currentMusic)
                assertEquals(before, backend.focusRequests)
                controller.updateSettings(AudioSettings(musicVolume = 0.3f))
                assertEquals(before + 1, backend.focusRequests)
                assertEquals(MusicTrack.MENU, backend.currentMusic!!.track)
                assertEquals(0.3f, backend.currentMusic!!.volume, 0f)
            }
        }
    }

    @Test
    fun aChangedPositiveMusicVolumeRetriesFocusButSfxAndHapticsDoNot() {
        val f = game()
        f.backend.focus(false)
        val before = f.backend.focusRequests
        f.controller.updateSettings(AudioSettings(sfxEnabled = false, hapticsEnabled = false))
        assertEquals(before, f.backend.focusRequests)
        assertNull(f.backend.currentMusic)
        f.controller.updateSettings(AudioSettings(musicVolume = 0.8f, sfxEnabled = false, hapticsEnabled = false))
        assertEquals(before + 1, f.backend.focusRequests)
        assertEquals(0.8f, f.backend.currentMusic!!.volume, 0f)
    }

    @Test
    fun deliberateMusicMuteUnmuteAndVolumeApplyDuringWaitingAndGoInEveryMode() {
        GameMode.entries.forEach { mode ->
            val settings = AudioSettings(sfxEnabled = false)
            val f = game(mode, settings)
            val track = f.backend.currentMusic!!.track
            f.controller.beginCompetitive(f.owner, "session")
            f.backend.clearHistory()
            repeat(2) { stage ->
                if (stage == 1) f.controller.goHaptic(f.owner)
                val before = f.backend.focusRequests
                f.controller.updateSettings(settings.copy(musicEnabled = false))
                assertNull(f.backend.currentMusic)
                assertEquals(before, f.backend.focusRequests)
                f.controller.updateSettings(settings)
                assertEquals(track, f.backend.currentMusic!!.track)
                assertEquals(before + 1, f.backend.focusRequests)
                f.controller.updateSettings(settings.copy(musicVolume = 0f))
                assertNull(f.backend.currentMusic)
                assertEquals(before + 1, f.backend.focusRequests)
                f.controller.updateSettings(settings.copy(musicVolume = 0.9f))
                assertEquals(track, f.backend.currentMusic!!.track)
                assertEquals(0.9f, f.backend.currentMusic!!.volume, 0f)
                assertEquals(before + 2, f.backend.focusRequests)
                f.controller.navigate()
                f.controller.select()
                assertTrue(f.backend.effects.isEmpty())
                assertTrue(f.backend.celebrations.isEmpty())
            }
        }
    }

    @Test
    fun timedTransientCuesCannotKeepOrRequestFocusWhenMusicIsMuted() {
        GameMode.entries.forEach { mode ->
            listOf(AudioSettings(musicEnabled = false), AudioSettings(musicVolume = 0f)).forEach { settings ->
                val f = game(mode, settings)
                val before = f.backend.focusRequests
                val abandons = f.backend.abandons
                f.controller.beginCompetitive(f.owner, "session")
                assertEquals(abandons + 1, f.backend.abandons)
                f.backend.clearHistory()
                f.controller.navigate()
                f.controller.select()
                f.controller.updateSettings(settings.copy(sfxVolume = 1f))
                f.controller.showResults(f.owner)
                val goOperations = f.backend.controlOperations()
                f.controller.goHaptic(f.owner)
                assertEquals(goOperations, f.backend.controlOperations())
                assertEquals(before, f.backend.focusRequests)
                assertNull(f.backend.currentMusic)
                assertTrue(f.backend.music.isEmpty())
                assertTrue(f.backend.effects.isEmpty())
                assertTrue(f.backend.celebrations.isEmpty())
            }
        }
    }

    @Test
    fun externalFocusGainDuringWaitingOrGoRestoresOnlyEligibleModeMusic() {
        GameMode.entries.forEach { mode ->
            val f = game(mode)
            val track = f.backend.currentMusic!!.track
            f.controller.beginCompetitive(f.owner, "session")
            val focusRequests = f.backend.focusRequests
            repeat(2) { stage ->
                f.backend.focus(false)
                f.backend.clearHistory()
                f.backend.focus(true)
                assertEquals(track, f.backend.currentMusic!!.track)
                assertEquals(1, f.backend.music.size)
                assertEquals(focusRequests, f.backend.focusRequests)
                assertTrue(f.backend.effects.isEmpty())
                assertTrue(f.backend.celebrations.isEmpty())
                assertTrue(f.backend.haptics.isEmpty())
                val goOperations = f.backend.controlOperations()
                f.controller.goHaptic(f.owner)
                assertEquals(goOperations, f.backend.controlOperations())
                assertEquals(if (stage == 0) listOf(35L) else emptyList<Long>(), f.backend.haptics)
            }
        }
    }

    @Test
    fun aDeliberateMusicRetryWaitsForForegroundAndDoesNotReplayTransientCues() {
        val f = game()
        f.backend.focus(false)
        f.controller.setForeground(false)
        val before = f.backend.focusRequests
        f.backend.clearHistory()
        f.controller.updateSettings(AudioSettings(musicEnabled = false))
        f.controller.updateSettings(AudioSettings(musicVolume = 0.9f))
        assertEquals(before, f.backend.focusRequests)
        assertNull(f.backend.currentMusic)
        f.controller.setForeground(true)
        assertEquals(before + 1, f.backend.focusRequests)
        assertEquals(MusicTrack.QUICK_DRAW, f.backend.currentMusic!!.track)
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.celebrations.isEmpty())
        assertTrue(f.backend.haptics.isEmpty())
    }

    @Test
    fun synchronousFocusCallbacksDoNotInitializeMusicTwice() {
        val backend = FakeBackend().apply { synchronousFocus = true }
        val controller = AudioController(backend)
        controller.setForeground(true)
        controller.showMenu()
        assertEquals(1, backend.music.size)
        assertEquals(MusicTrack.MENU, backend.currentMusic!!.track)
    }

    @Test
    fun rapidNavigationHasExplicitLowPriorityAndNoCelebrations() {
        val f = game()
        f.backend.clearHistory()
        repeat(25) { f.controller.navigate(); f.controller.select() }
        assertEquals(50, f.backend.effects.size)
        assertTrue(f.backend.effects.all { it.priority == 1 })
        assertTrue(f.backend.celebrations.isEmpty())
        assertEquals(1, f.backend.focusRequests)
    }

    @Test
    fun deliberateNavigationRestoresMenuMusicAfterFocusLossEvenWithSfxDisabled() {
        val backend = FakeBackend()
        val controller = AudioController(backend, AudioSettings(sfxEnabled = false))
        controller.setForeground(true)
        controller.showMenu()
        backend.focus(false)
        val requestsBeforeNavigation = backend.focusRequests
        backend.clearHistory()
        controller.showMenu() // The menu route already exists; route recomposition alone is inert.
        assertNull(backend.currentMusic)
        assertEquals(requestsBeforeNavigation, backend.focusRequests)
        controller.navigate()
        assertEquals(requestsBeforeNavigation + 1, backend.focusRequests)
        assertEquals(MusicTrack.MENU, backend.currentMusic!!.track)
        assertEquals(1, backend.music.size)
        assertTrue(backend.effects.isEmpty())
        assertTrue(backend.celebrations.isEmpty())
        assertTrue(backend.haptics.isEmpty())
    }

    @Test
    fun staleOwnersCannotReplaceStopOrPlayOverANewerGame() {
        val f = game()
        val newer = Any()
        f.controller.enterGame(newer, GameMode.ENDURANCE_RUSH)
        f.backend.clearHistory()
        f.controller.enterGame(f.owner, GameMode.QUICK_DRAW)
        f.controller.leaveGame(f.owner)
        f.controller.interrupt(f.owner)
        f.controller.beginCompetitive(f.owner, "old")
        f.controller.ready(f.owner, GameMode.QUICK_DRAW)
        f.controller.falseStart(f.owner, "old", 1)
        f.controller.reaction(f.owner, "old", 1, update(reward = reward()))
        assertEquals(MusicTrack.ENDURANCE, f.backend.currentMusic!!.track)
        assertTrue(f.backend.music.isEmpty())
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.celebrations.isEmpty())
    }

    @Test
    fun aRegisteredSessionTokenRejectsDifferentSessionCallbacksWithoutChangingMusic() {
        val f = game()
        f.controller.beginCompetitive(f.owner, "current")
        f.backend.clearHistory()
        val music = f.backend.musicActivity()
        f.controller.falseStart(f.owner, "obsolete", 1)
        f.controller.reaction(f.owner, "obsolete", 1, update(reward = reward()))
        assertEquals(music, f.backend.musicActivity())
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.celebrations.isEmpty())
        f.controller.reaction(f.owner, "current", 1, update(reward = reward()))
        assertEquals(1, f.backend.celebrations.size)
    }

    @Test
    fun roundCallbacksMustBeMonotonicEvenAcrossCompetitiveReentry() {
        val f = game(GameMode.CLASSIC_RUSH)
        f.controller.beginCompetitive(f.owner, "session")
        f.backend.clearHistory()
        val music = f.backend.musicActivity()
        f.controller.reaction(f.owner, "session", 2, update())
        assertTrue(f.backend.effects.isEmpty())
        f.controller.reaction(f.owner, "session", 1, update())
        repeat(4) { f.controller.reaction(f.owner, "session", 1, update()) }
        f.controller.beginCompetitive(f.owner, "session")
        f.controller.reaction(f.owner, "session", 1, update())
        assertEquals(music, f.backend.musicActivity())
        f.controller.reaction(f.owner, "session", 2, update())
        assertEquals(2, f.backend.effects.count { it.sound == SoundEffect.REACTION_SUCCESS })
        assertTrue(f.backend.celebrations.isEmpty())
    }

    @Test
    fun falseStartsAreDeduplicatedAndNeverCelebrateOrPretendCompletion() {
        val f = game()
        f.controller.beginCompetitive(f.owner, "session")
        f.backend.clearHistory()
        val music = f.backend.musicActivity()
        repeat(20) { f.controller.falseStart(f.owner, "session", 1) }
        f.controller.beginCompetitive(f.owner, "session")
        f.controller.falseStart(f.owner, "session", 1)
        assertEquals(music, f.backend.musicActivity())
        f.controller.falseStart(f.owner, "session", 2)
        assertEquals(2, f.backend.effects.count { it.sound == SoundEffect.FALSE_START })
        assertEquals(listOf(90L, 90L), f.backend.haptics)
        assertTrue(f.backend.celebrations.isEmpty())
    }

    @Test
    fun finalReactionRequiresAnActualRepositoryReward() {
        GameMode.entries.forEach { mode ->
            val f = game(mode)
            repeat(mode.rounds - 1) { round ->
                f.controller.beginCompetitive(f.owner, "session")
                f.controller.reaction(f.owner, "session", round + 1, update())
            }
            f.controller.beginCompetitive(f.owner, "session")
            f.backend.clearHistory()
            val music = f.backend.musicActivity()
            f.controller.reaction(f.owner, "session", mode.rounds, update())
            f.controller.showResults(f.owner)
            assertEquals(music, f.backend.musicActivity())
            assertTrue(f.backend.effects.isEmpty())
            assertTrue(f.backend.celebrations.isEmpty())
        }
    }

    @Test
    fun everyModeCompletesOnlyOnceAndResultsNavigationDoesNotRepeatItsCue() {
        GameMode.entries.forEach { mode ->
            val f = game(mode)
            complete(f)
            repeat(20) {
                f.controller.reaction(f.owner, "session", mode.rounds, update(reward = reward()))
                f.controller.showResults(f.owner)
            }
            assertEquals(MusicTrack.RESULTS, f.backend.currentMusic!!.track)
            assertEquals(mode.rounds, f.backend.effects.count { it.sound == SoundEffect.REACTION_SUCCESS })
            assertEquals(1, f.backend.celebrations.size)
        }
    }

    @Test
    fun levelUpSupersedesMilestoneAchievementRecordAndMedalCues() {
        val f = game()
        complete(f, reward = reward(newLevel = 2, unlocked = setOf(Achievement.GETTING_STARTED, Achievement.ELITE_REFLEX)), newRecord = true)
        assertEquals(Celebration(SoundEffect.LEVEL_UP, CelebrationStinger.LEVEL_UP, 0.7f, 10), f.backend.celebrations.single())
    }

    @Test
    fun everySessionAndLevelMilestoneUsesMilestoneStingerOverRecord() {
        listOf(Achievement.GETTING_STARTED, Achievement.DEDICATED, Achievement.VETERAN,
            Achievement.RISING_STAR, Achievement.REFLEX_MASTER, Achievement.LEGEND).forEach { milestone ->
            val f = game()
            complete(f, reward = reward(unlocked = setOf(milestone, Achievement.GOLD_REFLEX)), newRecord = true)
            assertEquals(CelebrationStinger.MILESTONE, f.backend.celebrations.single().stinger)
            assertEquals(SoundEffect.ACHIEVEMENT_UNLOCK, f.backend.celebrations.single().lead)
        }
    }

    @Test
    fun skillAndConsistencyAchievementsUseAchievementStingerOverRecord() {
        listOf(Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX, Achievement.ELITE_REFLEX,
            Achievement.STEADY_HANDS, Achievement.IRON_FOCUS, Achievement.CLEAN_SWEEP).forEach { achievement ->
            val f = game()
            complete(f, reward = reward(unlocked = setOf(achievement)), newRecord = true)
            assertEquals(CelebrationStinger.ACHIEVEMENT, f.backend.celebrations.single().stinger)
        }
    }

    @Test
    fun recordSupersedesMedalAndOrdinaryCompletionsChooseVictory() {
        val record = game()
        complete(record, newRecord = true)
        assertEquals(SoundEffect.NEW_RECORD, record.backend.celebrations.single().lead)
        assertEquals(CelebrationStinger.PERSONAL_RECORD, record.backend.celebrations.single().stinger)
        PerformanceMedal.entries.forEach { medal ->
            val f = game()
            complete(f, reward = reward(medal = medal))
            assertEquals(CelebrationStinger.VICTORY, f.backend.celebrations.single().stinger)
            assertEquals(if (medal == PerformanceMedal.UNRANKED) null else SoundEffect.MEDAL_AWARDED,
                f.backend.celebrations.single().lead)
        }
    }

    @Test
    fun replayRequiresFreshTokenAndCannotReopenTheCompletedSession() {
        val f = game()
        complete(f)
        f.controller.ready(f.owner, GameMode.QUICK_DRAW)
        f.backend.clearHistory()
        f.controller.beginCompetitive(f.owner, "session")
        assertEquals(MusicTrack.QUICK_DRAW, f.backend.currentMusic!!.track)
        f.controller.reaction(f.owner, "session", 1, update(reward = reward()))
        assertTrue(f.backend.celebrations.isEmpty())
        complete(f, id = "new")
        assertEquals(1, f.backend.celebrations.size)
    }

    @Test
    fun interruptionStaysSilentUntilNewDeliberateSessionWithFreshToken() {
        val f = game()
        f.controller.beginCompetitive(f.owner, "old")
        f.controller.interrupt(f.owner)
        f.backend.clearHistory()
        f.controller.navigate()
        f.controller.updateSettings(AudioSettings(musicVolume = 1f))
        f.backend.focus(true)
        f.controller.beginCompetitive(f.owner, "old")
        f.controller.reaction(f.owner, "old", 1, update(reward = reward()))
        assertNull(f.backend.currentMusic)
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.haptics.isEmpty())
        complete(f, id = "new")
        assertEquals(MusicTrack.RESULTS, f.backend.currentMusic!!.track)
        assertEquals(1, f.backend.celebrations.size)
    }

    @Test
    fun releaseIsIdempotentAndLateCallbacksCannotRestartAnything() {
        val f = game()
        f.controller.release()
        f.backend.clearHistory()
        f.controller.release()
        f.controller.showMenu()
        f.controller.enterGame(Any(), GameMode.ENDURANCE_RUSH)
        f.controller.ready(f.owner, GameMode.QUICK_DRAW)
        f.controller.beginCompetitive(f.owner, "session")
        f.controller.goHaptic(f.owner)
        f.controller.falseStart(f.owner, "session", 1)
        f.controller.reaction(f.owner, "session", 1, update(reward = reward()))
        f.controller.updateSettings(AudioSettings(musicVolume = 1f))
        f.controller.setForeground(true)
        f.controller.navigate()
        f.backend.focus(true)
        assertEquals(1, f.backend.releases)
        assertNull(f.backend.currentMusic)
        assertTrue(f.backend.music.isEmpty())
        assertTrue(f.backend.effects.isEmpty())
        assertTrue(f.backend.celebrations.isEmpty())
        assertTrue(f.backend.haptics.isEmpty())
    }

    @Test
    fun invalidVolumesAreNormalizedBeforeReachingAndroidPlayback() {
        val f = game(settings = AudioSettings(musicVolume = Float.NaN, sfxVolume = Float.POSITIVE_INFINITY))
        assertEquals(AudioSettings.DEFAULT_MUSIC_VOLUME, f.backend.currentMusic!!.volume, 0f)
        assertEquals(AudioSettings.DEFAULT_SFX_VOLUME, f.backend.effects.single().volume, 0f)
        f.controller.updateSettings(AudioSettings(musicVolume = 5f, sfxVolume = -1f))
        f.controller.select()
        assertEquals(1f, f.backend.currentMusic!!.volume, 0f)
        assertTrue(f.backend.effects.all { it.volume in 0f..1f })
    }

    @Test
    fun audioFailuresCannotChangeTheMonotonicReactionMeasurement() {
        val backend = FakeBackend().apply { failPlayback = true }
        val controller = AudioController(backend)
        val owner = Any()
        controller.setForeground(true)
        controller.enterGame(owner, GameMode.QUICK_DRAW)
        val timing = RoundTimingGuard().apply { setForeground(true) }
        val ticket = checkNotNull(timing.beginWaiting())
        controller.beginCompetitive(owner, "session")
        assertTrue(timing.signalGo(ticket, 10_000L))
        controller.goHaptic(owner)
        val score = timing.react(ticket, 10_237L)
        assertEquals(237, score)
        controller.reaction(owner, "session", 1, update(reward = reward()))
        assertNull(timing.react(ticket, 10_238L))
        controller.release()
    }

    private fun game(mode: GameMode = GameMode.QUICK_DRAW, settings: AudioSettings = AudioSettings()): Fixture {
        val backend = FakeBackend()
        val controller = AudioController(backend, settings)
        val owner = Any()
        controller.setForeground(true)
        controller.enterGame(owner, mode)
        return Fixture(controller, backend, owner, mode)
    }

    private fun complete(f: Fixture, id: String = "session", reward: SessionReward = reward(), newRecord: Boolean = false) {
        repeat(f.mode.rounds) { round ->
            f.controller.beginCompetitive(f.owner, id)
            f.controller.reaction(f.owner, id, round + 1,
                update(reward = if (round == f.mode.rounds - 1) reward else null, newRecord = newRecord))
        }
    }

    private fun update(reward: SessionReward? = null, newRecord: Boolean = false) =
        ReactionUpdate(PersonalRecords(), newRecord, reward)

    private fun reward(newLevel: Int = 1, unlocked: Set<Achievement> = emptySet(), medal: PerformanceMedal = PerformanceMedal.GOLD) =
        SessionReward(XpReward(10, 0, 0), 10, medal, 1, newLevel, unlocked)

    private data class Fixture(val controller: AudioController, val backend: FakeBackend, val owner: Any, val mode: GameMode)
    private data class Music(val track: MusicTrack?, val volume: Float)
    private data class Effect(val sound: SoundEffect, val volume: Float, val priority: Int)
    private data class Celebration(val lead: SoundEffect?, val stinger: CelebrationStinger, val volume: Float, val priority: Int)
    private data class MusicActivity(val current: Music?, val changes: Int, val focusRequests: Int, val abandons: Int, val stops: Int)
    private data class ControlOperations(val music: MusicActivity, val effectStops: Int, val hapticCancellations: Int)

    private fun FakeBackend.musicActivity() = MusicActivity(currentMusic, music.size, focusRequests, abandons, stops)
    private fun FakeBackend.controlOperations() = ControlOperations(musicActivity(), effectStops, cancelHapticCalls)

    private class FakeBackend : AudioBackend {
        var focus: (Boolean) -> Unit = {}
        var grantFocus = true
        var synchronousFocus = false
        var failPlayback = false
        var focusRequests = 0
        var abandons = 0
        var stops = 0
        var effectStops = 0
        var releases = 0
        var cancelHapticCalls = 0
        var liveCelebration = false
        var currentMusic: Music? = null
        val music = mutableListOf<Music>()
        val effects = mutableListOf<Effect>()
        val celebrations = mutableListOf<Celebration>()
        val haptics = mutableListOf<Long>()

        override fun setFocusListener(listener: (Boolean) -> Unit) { focus = listener }
        override fun requestFocus(): Boolean {
            focusRequests++
            if (synchronousFocus) focus(grantFocus)
            return grantFocus
        }
        override fun abandonFocus() { abandons++ }
        override fun setMusic(track: MusicTrack?, volume: Float) {
            if (failPlayback) error("simulated unavailable audio device")
            val event = Music(track, volume)
            music += event
            currentMusic = event.takeIf { track != null }
        }
        override fun stopAll() { stops++; currentMusic = null; liveCelebration = false }
        override fun stopEffects() { effectStops++; liveCelebration = false }
        override fun playEffect(effect: SoundEffect, volume: Float, priority: Int) {
            if (failPlayback) error("simulated unavailable sound sample")
            effects += Effect(effect, volume, priority)
        }
        override fun celebrate(leadEffect: SoundEffect?, stinger: CelebrationStinger, volume: Float, priority: Int) {
            if (failPlayback) error("simulated unavailable celebration sample")
            celebrations += Celebration(leadEffect, stinger, volume, priority)
            liveCelebration = true
        }
        override fun haptic(durationMs: Long) {
            if (failPlayback) error("simulated missing vibrator")
            haptics += durationMs
        }
        override fun cancelHaptics() { cancelHapticCalls++ }
        override fun release() { releases++ }
        fun clearHistory() { music.clear(); effects.clear(); celebrations.clear(); haptics.clear() }
    }
}
