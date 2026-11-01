package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioSettingsTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: RecordsRepository
    private lateinit var tasks: ArrayDeque<Runnable>
    private lateinit var persistence: CompletionPersistence
    private var commitSucceeds = true

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("reaction_rush_audio_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        tasks = ArrayDeque()
        commitSucceeds = true
        persistence = CompletionPersistence(Executor { tasks.addLast(it) }) {
            assertFalse(Thread.holdsLock(prefs))
            if (commitSucceeds) prefs.edit().commit() else false
        }
        repository = RecordsRepository(prefs, persistence)
    }

    @Test
    fun freshInstallUsesDefaultsWithoutWritingOrChangingPlayerData() {
        assertEquals(AudioSettings(), repository.loadAudioSettings())
        val records = repository.load()
        assertEquals(0, records.lifetimeBestMs)
        assertEquals(0, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        assertEquals(AchievementState(), records.achievements)
        assertTrue(records.history.sessions.isEmpty())
        assertTrue(prefs.all.isEmpty())
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun allSettingsRoundTripAcrossRepositoryRecreation() {
        val settings = AudioSettings(false, 0.2f, false, 0.8f, false)
        assertEquals(settings, repository.saveAudioSettings(settings))
        assertEquals(settings, RecordsRepository(prefs, persistence).loadAudioSettings())
        assertEquals(settings, repository.loadAudioSettings())
    }

    @Test
    fun normalizationClampsFiniteVolumesAndPreservesSwitches() {
        val actual = AudioSettings(false, -0.5f, false, 9f, false).normalized()
        assertEquals(AudioSettings(false, 0f, false, 1f, false), actual)
        assertEquals(AudioSettings(musicVolume = 0f, sfxVolume = 1f),
            AudioSettings(musicVolume = 0f, sfxVolume = 1f).normalized())
    }

    @Test
    fun normalizationRecoversNonFiniteVolumesToTheirIndependentDefaults() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertEquals(AudioSettings(), AudioSettings(musicVolume = invalid, sfxVolume = invalid).normalized())
        }
    }

    @Test
    fun wrongPersistedTypesUseSafeDefaultsWithoutThrowing() {
        assertTrue(prefs.edit()
            .putString("audio_music_enabled", "false")
            .putInt("audio_music_volume", 1)
            .putLong("audio_sfx_enabled", 0)
            .putString("audio_sfx_volume", "0.2")
            .putInt("audio_haptics_enabled", 0).commit())
        val snapshot = prefs.all.toMap()
        assertEquals(AudioSettings(), repository.loadAudioSettings())
        assertEquals(snapshot, prefs.all)
    }

    @Test
    fun corruptPersistedNonFiniteAndOutOfRangeVolumesRecoverIndependently() {
        assertTrue(prefs.edit().putFloat("audio_music_volume", Float.NaN)
            .putFloat("audio_sfx_volume", Float.POSITIVE_INFINITY).commit())
        assertEquals(AudioSettings(), repository.loadAudioSettings())
        assertTrue(prefs.edit().putFloat("audio_music_volume", -4f)
            .putFloat("audio_sfx_volume", 4f).commit())
        assertEquals(AudioSettings(musicVolume = 0f, sfxVolume = 1f), repository.loadAudioSettings())
    }

    @Test
    fun partiallySavedSettingsPreserveValidFieldsAndDefaultMissingFields() {
        assertTrue(prefs.edit().putBoolean("audio_music_enabled", false)
            .putFloat("audio_sfx_volume", 0.15f).commit())
        assertEquals(AudioSettings(musicEnabled = false, sfxVolume = 0.15f), repository.loadAudioSettings())
    }

    @Test
    fun settingsAreImmediatelyVisibleAndUseTheExistingAsyncCheckpoint() {
        val settings = AudioSettings(musicVolume = 0.33f, hapticsEnabled = false)
        repository.saveAudioSettings(settings)
        assertEquals(settings, repository.loadAudioSettings())
        assertEquals(SaveStatus.SAVING, repository.saveStatus.value)
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(SaveStatus.SAVED, repository.saveStatus.value)
    }

    @Test
    fun repeatedUnchangedSaveDoesNotQueueAnotherCheckpoint() {
        val settings = AudioSettings(musicEnabled = false)
        repository.saveAudioSettings(settings)
        tasks.removeFirst().run()
        repository.saveAudioSettings(settings)
        assertTrue(tasks.isEmpty())
        assertEquals(SaveStatus.SAVED, repository.saveStatus.value)
    }

    @Test
    fun rapidSettingsCommitsCoalesceAndSaveTheLatestValues() {
        repository.saveAudioSettings(AudioSettings(musicVolume = 0.1f))
        repository.saveAudioSettings(AudioSettings(musicVolume = 0.6f, sfxVolume = 0.3f))
        repository.saveAudioSettings(AudioSettings(musicVolume = 0.9f, sfxVolume = 0.1f))
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(AudioSettings(musicVolume = 0.9f, sfxVolume = 0.1f), repository.loadAudioSettings())
        assertEquals(SaveStatus.SAVED, repository.saveStatus.value)
    }

    @Test
    fun failedSettingsCheckpointCanRetryWithoutChangingGameplay() {
        val initialRecords = repository.load()
        val settings = AudioSettings(sfxEnabled = false)
        commitSucceeds = false
        repository.saveAudioSettings(settings)
        tasks.removeFirst().run()
        assertEquals(SaveStatus.FAILED, repository.saveStatus.value)
        assertEquals(settings, repository.loadAudioSettings())
        commitSucceeds = true
        repository.retrySave()
        tasks.removeFirst().run()
        assertEquals(SaveStatus.SAVED, repository.saveStatus.value)
        assertEquals(initialRecords, repository.load())
    }

    @Test
    fun settingsCheckpointWritesEveryValueToTheActualPreferenceFile() {
        val settings = AudioSettings(false, 0.25f, true, 0.75f, false)
        repository.saveAudioSettings(settings)
        tasks.removeFirst().run()
        val file = File(RuntimeEnvironment.getApplication().applicationInfo.dataDir,
            "shared_prefs/reaction_rush_audio_settings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val children = document.documentElement.childNodes
        val elements = (0 until children.length).mapNotNull { children.item(it) as? Element }
            .associateBy { it.getAttribute("name") }
        assertEquals("false", elements.getValue("audio_music_enabled").getAttribute("value"))
        assertEquals("0.25", elements.getValue("audio_music_volume").getAttribute("value"))
        assertEquals("true", elements.getValue("audio_sfx_enabled").getAttribute("value"))
        assertEquals("0.75", elements.getValue("audio_sfx_volume").getAttribute("value"))
        assertEquals("false", elements.getValue("audio_haptics_enabled").getAttribute("value"))
        assertEquals(settings, RecordsRepository(prefs, persistence).loadAudioSettings())
    }

    @Test
    fun settingsEditsPreserveRecordsXpAchievementsAndHistoryByteForByte() {
        val id = repository.startSession(GameMode.QUICK_DRAW)
        val completed = repository.recordReaction(id, GameMode.QUICK_DRAW, 190, listOf(190))
        tasks.removeFirst().run()
        val previous = prefs.all.toMap()
        repository.saveAudioSettings(AudioSettings(false, 0.11f, false, 0.22f, false))
        assertEquals(previous, prefs.all.filterKeys { !it.startsWith("audio_") })
        assertEquals(completed.records, repository.load())
    }

    @Test
    fun settingsEditsDoNotConsumeAnActiveSessionOrCreateProgression() {
        val id = repository.startSession(GameMode.CLASSIC_RUSH)
        repository.recordFalseStart(id)
        val before = prefs.all.toMap()
        repository.saveAudioSettings(AudioSettings(hapticsEnabled = false))
        assertEquals(before, prefs.all.filterKeys { !it.startsWith("audio_") })
        assertEquals(0L, repository.load().totalXp)
        assertEquals(0L, repository.load().achievements.completedSessions)
        assertTrue(repository.load().history.sessions.isEmpty())
    }

    @Test
    fun completedSessionEditsPreserveAudioPreferencesAndItsOwnRewardIntegrity() {
        val settings = AudioSettings(false, 0.2f, true, 0.8f, false)
        repository.saveAudioSettings(settings)
        tasks.removeFirst().run()
        val id = repository.startSession(GameMode.CLASSIC_RUSH)
        val scores = listOf(250, 260, 270, 280, 290)
        val updates = scores.indices.map { index ->
            repository.recordReaction(id, GameMode.CLASSIC_RUSH, scores[index], scores.take(index + 1))
        }
        val completed = updates.last()
        assertEquals(settings, repository.loadAudioSettings())
        assertEquals(1L, completed.records.achievements.completedSessions)
        assertEquals(1, completed.records.history.sessions.size)
        assertEquals(5, completed.records.lifetimeReactions)
        assertEquals(270, completed.records.bestFor(GameMode.CLASSIC_RUSH))
        assertTrue(completed.records.totalXp > 0L)
    }
}
