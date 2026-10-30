package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordsRepositoryTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: RecordsRepository

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        repository = RecordsRepository(prefs)
    }

    @Test
    fun freshInstallHasNoRecordsOrReactions() {
        val records = repository.load()

        assertEquals(0, records.lifetimeBestMs)
        assertEquals(0, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        assertEquals(AchievementState(), records.achievements)
        assertEquals(SessionHistory(), records.history)
        GameMode.entries.forEach { assertEquals(0L, records.completedByMode[it]) }
        GameMode.entries.forEach { assertEquals(0, records.bestFor(it)) }
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun legacyStatisticsArePreservedWithoutInventingModeRecords() {
        assertTrue(prefs.edit().putInt("best", 143).putInt("attempts", 29).commit())

        val records = repository.load()

        assertEquals(143, records.lifetimeBestMs)
        assertEquals(29, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        GameMode.entries.forEach { assertEquals(0, records.bestFor(it)) }

        val result = completeSession(GameMode.CLASSIC_RUSH, listOf(250, 260, 270, 280, 290))
        assertEquals(143, result.records.lifetimeBestMs)
        assertEquals(34, result.records.lifetimeReactions)
        assertEquals(270, result.records.bestFor(GameMode.CLASSIC_RUSH))
        assertTrue(result.isNewRecord)
        assertEquals(0, result.records.bestFor(GameMode.QUICK_DRAW))
    }

    @Test
    fun quickDrawSavesSingleReactionAndOnlyStrictImprovementsSetTheFlag() {
        val first = completeSession(GameMode.QUICK_DRAW, listOf(250))
        assertEquals(250, first.records.bestFor(GameMode.QUICK_DRAW))
        assertTrue(first.isNewRecord)

        val tied = completeSession(GameMode.QUICK_DRAW, listOf(250))
        assertFalse(tied.isNewRecord)
        val slower = completeSession(GameMode.QUICK_DRAW, listOf(300))
        assertFalse(slower.isNewRecord)
        assertEquals(250, slower.records.bestFor(GameMode.QUICK_DRAW))

        val improved = completeSession(GameMode.QUICK_DRAW, listOf(249))
        assertTrue(improved.isNewRecord)
        assertEquals(249, improved.records.bestFor(GameMode.QUICK_DRAW))
        assertEquals(4, improved.records.lifetimeReactions)
    }

    @Test
    fun classicScoresTheCompletedFiveRoundAverageRatherThanTheFastestRound() {
        val result = completeSession(GameMode.CLASSIC_RUSH, listOf(100, 200, 300, 400, 502))

        assertEquals(300, result.records.bestFor(GameMode.CLASSIC_RUSH))
        assertEquals(100, result.records.lifetimeBestMs)
        assertEquals(5, result.records.lifetimeReactions)
        assertTrue(result.isNewRecord)
    }

    @Test
    fun enduranceScoresAllTenRoundsAndRoundsHalfMillisecondsUp() {
        val scores = List(9) { 200 } + 205
        val result = completeSession(GameMode.ENDURANCE_RUSH, scores)

        assertEquals(201, result.records.bestFor(GameMode.ENDURANCE_RUSH))
        assertEquals(200, result.records.lifetimeBestMs)
        assertEquals(10, result.records.lifetimeReactions)
        assertTrue(result.isNewRecord)
    }

    @Test
    fun multiRoundRecordsCompareRoundedAveragesAndRejectTiesAndSlowerSessions() {
        completeSession(GameMode.CLASSIC_RUSH, List(5) { 200 })

        val roundedTie = completeSession(GameMode.CLASSIC_RUSH, listOf(199, 200, 200, 200, 200))
        assertFalse(roundedTie.isNewRecord)
        assertEquals(200, roundedTie.records.bestFor(GameMode.CLASSIC_RUSH))

        val slowerWithFasterSingleRound =
            completeSession(GameMode.CLASSIC_RUSH, listOf(100, 300, 300, 300, 300))
        assertFalse(slowerWithFasterSingleRound.isNewRecord)
        assertEquals(200, slowerWithFasterSingleRound.records.bestFor(GameMode.CLASSIC_RUSH))
        assertEquals(100, slowerWithFasterSingleRound.records.lifetimeBestMs)

        val improved = completeSession(GameMode.CLASSIC_RUSH, List(5) { 199 })
        assertTrue(improved.isNewRecord)
        assertEquals(199, improved.records.bestFor(GameMode.CLASSIC_RUSH))
    }

    @Test
    fun incompleteSessionsUpdateLifetimeStatisticsButCannotSetModeRecords() {
        GameMode.entries.filter { it.rounds > 1 }.forEach { mode ->
            val sessionId = repository.startSession(mode)
            val scores = mutableListOf<Int>()
            repeat(mode.rounds - 1) {
                scores += 150 + it
                val update = repository.recordReaction(sessionId, mode, scores.last(), scores.toList())
                assertFalse(update.isNewRecord)
                assertNull(update.reward)
                assertEquals(0L, update.records.totalXp)
                assertEquals(0, update.records.bestFor(mode))
                assertFalse(prefs.contains(mode.recordKey))
            }
        }

        val records = RecordsRepository(prefs).load()
        assertEquals(13, records.lifetimeReactions)
        assertEquals(150, records.lifetimeBestMs)
    }

    @Test
    fun abandoningAnImprovedPartialSessionPreservesTheExistingModeRecord() {
        completeSession(GameMode.CLASSIC_RUSH, List(5) { 300 })

        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        val xpBefore = repository.load().totalXp
        val partial = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 100, listOf(100))
        val reloaded = RecordsRepository(prefs).load()

        assertFalse(partial.isNewRecord)
        assertEquals(300, reloaded.bestFor(GameMode.CLASSIC_RUSH))
        assertEquals(100, reloaded.lifetimeBestMs)
        assertEquals(6, reloaded.lifetimeReactions)
        assertEquals(xpBefore, reloaded.totalXp)
        assertNull(partial.reward)
    }

    @Test
    fun allModeRecordsUseIndependentStablePreferenceKeys() {
        completeSession(GameMode.QUICK_DRAW, listOf(175))
        completeSession(GameMode.CLASSIC_RUSH, List(5) { 250 })
        completeSession(GameMode.ENDURANCE_RUSH, List(10) { 325 })

        assertEquals(175, prefs.getInt("record_quick_draw", 0))
        assertEquals(250, prefs.getInt("record_classic_rush", 0))
        assertEquals(325, prefs.getInt("record_endurance_rush", 0))

        val result = completeSession(GameMode.CLASSIC_RUSH, List(5) { 200 })
        assertEquals(175, result.records.bestFor(GameMode.QUICK_DRAW))
        assertEquals(200, result.records.bestFor(GameMode.CLASSIC_RUSH))
        assertEquals(325, result.records.bestFor(GameMode.ENDURANCE_RUSH))
        assertEquals(175, result.records.lifetimeBestMs)
        assertEquals(21, result.records.lifetimeReactions)
    }

    @Test
    fun recordsSurviveReopeningPreferencesAndCreatingANewRepository() {
        completeSession(GameMode.QUICK_DRAW, listOf(180))
        completeSession(GameMode.CLASSIC_RUSH, List(5) { 240 })
        completeSession(GameMode.ENDURANCE_RUSH, List(10) { 310 })
        // commit() waits for preceding apply() writes to reach the preference file.
        assertTrue(prefs.edit().commit())
        val preferencesFile = File(
            RuntimeEnvironment.getApplication().applicationInfo.dataDir,
            "shared_prefs/reaction_rush.xml"
        )
        val storedEntries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(preferencesFile).getElementsByTagName("int")
        val storedValues = (0 until storedEntries.length).associate { index ->
            val entry = storedEntries.item(index) as Element
            entry.getAttribute("name") to entry.getAttribute("value").toInt()
        }
        assertEquals(
            mapOf(
                "best" to 180,
                "attempts" to 16,
                "record_quick_draw" to 180,
                "record_classic_rush" to 240,
                "record_endurance_rush" to 310,
                "clean_session_streak" to 3
            ),
            storedValues
        )
        val reopenedPrefs = RuntimeEnvironment.getApplication()
            .createPackageContext("com.maurointeractive.reactionrush", 0)
            .getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)

        val reloaded = RecordsRepository(reopenedPrefs).load()

        assertEquals(180, reloaded.bestFor(GameMode.QUICK_DRAW))
        assertEquals(240, reloaded.bestFor(GameMode.CLASSIC_RUSH))
        assertEquals(310, reloaded.bestFor(GameMode.ENDURANCE_RUSH))
        assertEquals(180, reloaded.lifetimeBestMs)
        assertEquals(16, reloaded.lifetimeReactions)
        val storedLongs = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(preferencesFile).getElementsByTagName("long")
        val storedLongValues = (0 until storedLongs.length).associate { index ->
            val entry = storedLongs.item(index) as Element
            entry.getAttribute("name") to entry.getAttribute("value").toLong()
        }
        assertEquals(
            mapOf(
                "total_xp" to repository.load().totalXp,
                "completed_sessions" to 3L,
                "completed_sessions_quick_draw" to 1L,
                "completed_sessions_classic_rush" to 1L,
                "completed_sessions_endurance_rush" to 1L
            ),
            storedLongValues
        )
        assertEquals(repository.load().totalXp, reloaded.totalXp)
        assertTrue(reloaded.totalXp > 0)
        assertFalse(prefs.contains("active_session_id"))
        assertEquals(repository.load().achievements, reloaded.achievements)
        assertEquals(3L, reloaded.achievements.completedSessions)
        assertEquals(3, reloaded.achievements.cleanSessionStreak)
        assertEquals(repository.load().history, reloaded.history)
        assertEquals(3, reloaded.history.sessions.size)
        assertEquals(GameMode.entries.associateWith { 1L }, reloaded.completedByMode)
        val storedSets = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(preferencesFile).getElementsByTagName("set")
        val achievementSet = (0 until storedSets.length).map { storedSets.item(it) as Element }
            .single { it.getAttribute("name") == "unlocked_achievement_ids" }
        val storedIds = achievementSet.getElementsByTagName("string")
        assertEquals(
            reloaded.achievements.unlocked.map { it.name }.toSet(),
            (0 until storedIds.length).map { storedIds.item(it).textContent }.toSet()
        )
    }

    @Test
    fun aNewSessionDoesNotReuseThePreviousSessionsNewRecordFlag() {
        assertTrue(completeSession(GameMode.QUICK_DRAW, listOf(250)).isNewRecord)

        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        val firstClassicRound = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 100, listOf(100))

        assertFalse(firstClassicRound.isNewRecord)
        assertFalse(completeSession(GameMode.QUICK_DRAW, listOf(260)).isNewRecord)
    }

    @Test
    fun zeroAndNegativeTimesAreRejectedBeforeAnyStatisticsChange() {
        completeSession(GameMode.QUICK_DRAW, listOf(200))
        val sessionId = repository.startSession(GameMode.QUICK_DRAW)
        val before = prefs.all.toMap()

        listOf(0, -1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordReaction(sessionId, GameMode.QUICK_DRAW, invalid, listOf(invalid))
            }
        }

        assertEquals(before, prefs.all)
    }

    @Test
    fun malformedSessionScoresAreRejectedBeforeAnyStatisticsChange() {
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        val before = prefs.all.toMap()

        listOf(
            emptyList(),
            listOf(201),
            listOf(0, 200),
            listOf(-1, 200),
            List(6) { 200 }
        ).forEach { invalidScores ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 200, invalidScores)
            }
        }

        assertEquals(before, prefs.all)
    }

    @Test
    fun startingOrReplacingASessionPersistsOnlyActiveMetadataAndNeverAwardsXp() {
        val first = repository.startSession(GameMode.CLASSIC_RUSH)
        assertEquals(first, prefs.getString("active_session_id", null))
        assertEquals("CLASSIC_RUSH", prefs.getString("active_session_mode", null))
        assertEquals(0, prefs.getInt("active_session_rounds", -1))
        assertFalse(prefs.getBoolean("active_session_had_false_start", true))
        assertEquals(0, prefs.getInt("active_session_false_start_count", -1))
        assertEquals(0L, repository.load().totalXp)
        assertFalse(prefs.contains("total_xp"))
        assertFalse(prefs.contains("attempts"))

        val replacement = RecordsRepository(prefs).startSession(GameMode.ENDURANCE_RUSH)
        assertNotEquals(first, replacement)
        assertEquals(replacement, prefs.getString("active_session_id", null))
        assertEquals("ENDURANCE_RUSH", prefs.getString("active_session_mode", null))
        assertEquals(0, prefs.getInt("active_session_rounds", -1))
        assertFalse(prefs.getBoolean("active_session_had_false_start", true))
        assertEquals(0, prefs.getInt("active_session_false_start_count", -1))
        assertEquals(0L, repository.load().totalXp)
    }

    @Test
    fun onlyCompletionAwardsXpAndReturnsTheMedalForTheModeScore() {
        val scores = listOf(100, 300, 300, 300, 500)
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        repeat(4) { index ->
            val update = repository.recordReaction(
                sessionId, GameMode.CLASSIC_RUSH, scores[index], scores.take(index + 1)
            )
            assertNull(update.reward)
            assertEquals(0L, update.records.totalXp)
            assertEquals(index + 1, prefs.getInt("active_session_rounds", -1))
        }

        val completed = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 500, scores)
        val reward = checkNotNull(completed.reward)
        val expectedXp = PlayerProgression.reward(GameMode.CLASSIC_RUSH, scores)
        assertEquals(expectedXp, reward.xp)
        assertEquals(expectedXp.totalXp, reward.awardedXp)
        assertTrue(reward.xp.totalXp >= 40)
        assertEquals(expectedXp.totalXp.toLong(), completed.records.totalXp)
        assertEquals(PlayerProgression.medal(300), reward.medal)
        assertEquals(300, completed.records.bestFor(GameMode.CLASSIC_RUSH))
        assertEquals(100, completed.records.lifetimeBestMs)
        assertEquals(5, completed.records.lifetimeReactions)
        assertEquals(1, reward.previousLevel)
        assertEquals(PlayerProgression.levelProgress(completed.records.totalXp).level, reward.newLevel)
        assertEquals(completed.records.totalXp, prefs.getLong("total_xp", -1))
        assertFalse(prefs.contains("active_session_id"))
        assertFalse(prefs.contains("active_session_mode"))
        assertFalse(prefs.contains("active_session_rounds"))
        assertFalse(prefs.contains("active_session_had_false_start"))
        assertFalse(prefs.contains("active_session_false_start_count"))
    }

    @Test
    fun everyCompletedSessionCanEarnXpEvenWhenItsScoreDoesNotSetARecord() {
        val first = completeSession(GameMode.QUICK_DRAW, listOf(250))
        val second = completeSession(GameMode.QUICK_DRAW, listOf(350))

        assertFalse(second.isNewRecord)
        assertEquals(250, second.records.bestFor(GameMode.QUICK_DRAW))
        assertNotNull(second.reward)
        assertEquals(
            first.records.totalXp + checkNotNull(second.reward).xp.totalXp,
            second.records.totalXp
        )
    }

    @Test
    fun repeatedFinalReactionCannotAwardAgainAfterRepositoryRecreation() {
        val sessionId = repository.startSession(GameMode.QUICK_DRAW)
        val completed = repository.recordReaction(sessionId, GameMode.QUICK_DRAW, 250, listOf(250))
        assertNotNull(completed.reward)
        val saved = prefs.all.toMap()

        repeat(3) {
            val duplicate = RecordsRepository(prefs)
                .recordReaction(sessionId, GameMode.QUICK_DRAW, 250, listOf(250))
            assertEquals(completed.records, duplicate.records)
            assertFalse(duplicate.isNewRecord)
            assertNull(duplicate.reward)
            assertEquals(saved, prefs.all)
        }
    }

    @Test
    fun consumedSessionStaysInvalidAfterANewSessionStarts() {
        val oldId = repository.startSession(GameMode.QUICK_DRAW)
        repository.recordReaction(oldId, GameMode.QUICK_DRAW, 250, listOf(250))
        val newId = repository.startSession(GameMode.QUICK_DRAW)
        val before = prefs.all.toMap()

        val duplicate = RecordsRepository(prefs)
            .recordReaction(oldId, GameMode.QUICK_DRAW, 200, listOf(200))
        assertNull(duplicate.reward)
        assertFalse(duplicate.isNewRecord)
        assertEquals(before, prefs.all)
        assertEquals(newId, prefs.getString("active_session_id", null))

        val fresh = repository.recordReaction(newId, GameMode.QUICK_DRAW, 200, listOf(200))
        assertNotNull(fresh.reward)
        assertEquals(2, fresh.records.lifetimeReactions)
        assertTrue(fresh.isNewRecord)
    }

    @Test
    fun duplicateOrSkippedRoundsCannotAdvanceTheSession() {
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        val first = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 250, listOf(250))
        val before = prefs.all.toMap()

        val duplicate = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 250, listOf(250))
        val skipped = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 250, List(3) { 250 })
        listOf(duplicate, skipped).forEach {
            assertEquals(first.records, it.records)
            assertNull(it.reward)
            assertFalse(it.isNewRecord)
        }
        assertEquals(before, prefs.all)

        val next = RecordsRepository(prefs)
            .recordReaction(sessionId, GameMode.CLASSIC_RUSH, 250, List(2) { 250 })
        assertEquals(2, next.records.lifetimeReactions)
        assertEquals(2, prefs.getInt("active_session_rounds", -1))
        assertEquals(0L, next.records.totalXp)
    }

    @Test
    fun staleAndWrongModeTokensCannotChangeCurrentSessionOrStatistics() {
        val abandonedId = repository.startSession(GameMode.CLASSIC_RUSH)
        repository.recordReaction(abandonedId, GameMode.CLASSIC_RUSH, 250, listOf(250))
        val currentId = repository.startSession(GameMode.ENDURANCE_RUSH)
        val before = prefs.all.toMap()
        val requests = listOf(
            Triple(abandonedId, GameMode.CLASSIC_RUSH, List(2) { 250 }),
            Triple(currentId, GameMode.CLASSIC_RUSH, listOf(250)),
            Triple("unknown", GameMode.ENDURANCE_RUSH, listOf(250)),
            Triple("", GameMode.ENDURANCE_RUSH, listOf(250))
        )
        requests.forEach { (sessionId, mode, scores) ->
            val rejected = RecordsRepository(prefs).recordReaction(sessionId, mode, 250, scores)
            assertNull(rejected.reward)
            assertFalse(rejected.isNewRecord)
            assertEquals(1, rejected.records.lifetimeReactions)
            assertEquals(0L, rejected.records.totalXp)
            assertEquals(before, prefs.all)
        }
    }

    @Test
    fun anAbandonedSessionCannotResumeAfterANewSessionOfTheSameModeStarts() {
        val oldId = repository.startSession(GameMode.CLASSIC_RUSH)
        repository.recordReaction(oldId, GameMode.CLASSIC_RUSH, 200, listOf(200))
        val newId = RecordsRepository(prefs).startSession(GameMode.CLASSIC_RUSH)
        val before = prefs.all.toMap()

        val oldRound = repository.recordReaction(oldId, GameMode.CLASSIC_RUSH, 200, List(2) { 200 })
        assertNull(oldRound.reward)
        assertEquals(before, prefs.all)

        val firstNewRound = repository.recordReaction(newId, GameMode.CLASSIC_RUSH, 300, listOf(300))
        assertEquals(2, firstNewRound.records.lifetimeReactions)
        assertEquals(1, prefs.getInt("active_session_rounds", -1))
        assertEquals(0L, firstNewRound.records.totalXp)
    }

    @Test
    fun abandonedSessionCannotAcceptLaterCallbacksAfterRepositoryRecreation() {
        completeSession(GameMode.QUICK_DRAW, listOf(250))
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 200, listOf(200))
        val recordsBefore = repository.load()

        repository.abandonSession(sessionId)

        assertEquals(recordsBefore, repository.load())
        assertFalse(prefs.contains("active_session_id"))
        assertFalse(prefs.contains("active_session_mode"))
        assertFalse(prefs.contains("active_session_rounds"))
        assertFalse(prefs.contains("active_session_had_false_start"))
        assertFalse(prefs.contains("active_session_false_start_count"))
        val saved = prefs.all.toMap()
        val lateRound = RecordsRepository(prefs)
            .recordReaction(sessionId, GameMode.CLASSIC_RUSH, 200, List(2) { 200 })
        assertEquals(recordsBefore, lateRound.records)
        assertNull(lateRound.reward)
        assertFalse(lateRound.isNewRecord)
        assertEquals(saved, prefs.all)
    }

    @Test
    fun abandoningOldOrBlankTokensCannotCancelTheCurrentSessionOrChangeStatistics() {
        completeSession(GameMode.QUICK_DRAW, listOf(250))
        val oldId = repository.startSession(GameMode.CLASSIC_RUSH)
        repository.recordReaction(oldId, GameMode.CLASSIC_RUSH, 200, listOf(200))
        val currentId = repository.startSession(GameMode.ENDURANCE_RUSH)
        val saved = prefs.all.toMap()
        listOf(oldId, "", " ", "unknown").forEach { staleId ->
            RecordsRepository(prefs).abandonSession(staleId)
            assertEquals(saved, prefs.all)
        }

        val currentRound = repository
            .recordReaction(currentId, GameMode.ENDURANCE_RUSH, 300, listOf(300))
        assertEquals(3, currentRound.records.lifetimeReactions)
        assertEquals(1, prefs.getInt("active_session_rounds", -1))
        assertNull(currentRound.reward)
        assertEquals(repository.load().totalXp, saved["total_xp"])
    }

    @Test
    fun orphanedIncompleteSessionNeverAwardsXpWhenLoaded() {
        val sessionId = repository.startSession(GameMode.ENDURANCE_RUSH)
        repository.recordReaction(sessionId, GameMode.ENDURANCE_RUSH, 300, listOf(300))
        val saved = prefs.all.toMap()

        val reloaded = RecordsRepository(prefs).load()
        assertEquals(0L, reloaded.totalXp)
        assertEquals(1, reloaded.lifetimeReactions)
        assertEquals(0, reloaded.bestFor(GameMode.ENDURANCE_RUSH))
        assertEquals(saved, prefs.all)
    }

    @Test
    fun malformedPersistedStatisticsAndXpAreReadSafelyWithoutChangingTheSave() {
        assertTrue(prefs.edit()
            .putString("best", "invalid")
            .putLong("attempts", 100)
            .putInt("record_quick_draw", -1)
            .putBoolean("record_classic_rush", true)
            .putString("record_endurance_rush", "300")
            .putInt("total_xp", 100)
            .commit())
        val saved = prefs.all.toMap()

        val records = repository.load()
        assertEquals(0, records.lifetimeBestMs)
        assertEquals(0, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        GameMode.entries.forEach { assertEquals(0, records.bestFor(it)) }
        assertEquals(saved, prefs.all)

        val repaired = completeSession(GameMode.QUICK_DRAW, listOf(250))
        assertEquals(250, repaired.records.lifetimeBestMs)
        assertEquals(1, repaired.records.lifetimeReactions)
        assertEquals(250, prefs.getInt("record_quick_draw", 0))
        assertEquals(checkNotNull(repaired.reward).xp.totalXp.toLong(), prefs.getLong("total_xp", -1))
    }

    @Test
    fun negativeStatisticsAndXpAreClampedToZero() {
        assertTrue(prefs.edit()
            .putInt("best", -10)
            .putInt("attempts", -50)
            .putLong("total_xp", -100)
            .commit())

        val records = repository.load()
        assertEquals(0, records.lifetimeBestMs)
        assertEquals(0, records.lifetimeReactions)
        assertEquals(0L, records.totalXp)
        val completed = completeSession(GameMode.QUICK_DRAW, listOf(300))
        assertEquals(300, completed.records.lifetimeBestMs)
        assertEquals(1, completed.records.lifetimeReactions)
        assertTrue(completed.records.totalXp >= 10)
    }

    @Test
    fun corruptActiveMetadataRejectsReactionsWithoutWritingOrCrashing() {
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        val corruptions: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { it.putInt("active_session_id", 7) },
            { it.putBoolean("active_session_mode", true) },
            { it.putString("active_session_mode", "UNKNOWN_MODE") },
            { it.putLong("active_session_rounds", 0) },
            { it.putInt("active_session_rounds", -1) },
            { it.putInt("active_session_rounds", 5) },
            { it.putInt("active_session_rounds", Int.MAX_VALUE) },
            { it.remove("active_session_rounds") }
        )
        corruptions.forEach { corrupt ->
            val editor = prefs.edit()
                .putString("active_session_id", sessionId)
                .putString("active_session_mode", GameMode.CLASSIC_RUSH.name)
                .putInt("active_session_rounds", 0)
            corrupt(editor)
            assertTrue(editor.commit())
            val before = prefs.all.toMap()

            val rejected = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 250, listOf(250))
            assertNull(rejected.reward)
            assertFalse(rejected.isNewRecord)
            assertEquals(0, rejected.records.lifetimeReactions)
            assertEquals(before, prefs.all)
        }
        val recoveredId = repository.startSession(GameMode.QUICK_DRAW)
        val recovered = repository.recordReaction(recoveredId, GameMode.QUICK_DRAW, 250, listOf(250))
        assertNotNull(recovered.reward)
        assertEquals(1, recovered.records.lifetimeReactions)
    }

    @Test
    fun xpAndLifetimeCounterSaturateInsteadOfOverflowing() {
        assertTrue(prefs.edit()
            .putLong("total_xp", Long.MAX_VALUE - 1)
            .putInt("attempts", Int.MAX_VALUE)
            .commit())

        val first = completeSession(GameMode.QUICK_DRAW, listOf(300))
        assertEquals(Long.MAX_VALUE, first.records.totalXp)
        assertEquals(Int.MAX_VALUE, first.records.lifetimeReactions)
        assertEquals(Long.MAX_VALUE, prefs.getLong("total_xp", 0))
        assertEquals(1, checkNotNull(first.reward).awardedXp)
        val second = completeSession(GameMode.QUICK_DRAW, listOf(350))
        assertEquals(Long.MAX_VALUE, second.records.totalXp)
        assertEquals(Int.MAX_VALUE, second.records.lifetimeReactions)
        assertNotNull(second.reward)
        assertEquals(0, checkNotNull(second.reward).awardedXp)
    }

    @Test
    fun rewardReportsLevelsBeforeAndAfterAddingPersistedXp() {
        assertTrue(prefs.edit().putLong("total_xp", 99).commit())
        val completed = completeSession(GameMode.QUICK_DRAW, listOf(199))
        val reward = checkNotNull(completed.reward)
        assertEquals(PlayerProgression.levelProgress(99).level, reward.previousLevel)
        assertEquals(PlayerProgression.levelProgress(completed.records.totalXp).level, reward.newLevel)
        assertEquals(99L + reward.xp.totalXp, completed.records.totalXp)
        assertEquals(PlayerProgression.medal(199), reward.medal)
    }

    @Test
    fun finalReactionUnlocksMedalHierarchyAndConsistencyTogetherOnlyOnce() {
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        repeat(4) { index ->
            val round = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 199, List(index + 1) { 199 })
            assertEquals(AchievementState(), round.records.achievements)
            assertFalse(prefs.contains("completed_sessions"))
            assertNull(round.reward)
        }

        val completed = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 199, List(5) { 199 })
        val expected = setOf(
            Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX,
            Achievement.ELITE_REFLEX, Achievement.STEADY_HANDS
        )
        assertEquals(expected, completed.records.achievements.unlocked)
        assertEquals(expected, checkNotNull(completed.reward).newlyUnlocked)
        assertEquals(1L, completed.records.achievements.completedSessions)
        assertEquals(1, completed.records.achievements.cleanSessionStreak)
        assertEquals(expected.map { it.name }.toSet(), prefs.getStringSet("unlocked_achievement_ids", null))
        assertFalse(prefs.contains("active_session_had_false_start"))
        val saved = prefs.all.toMap()
        val duplicate = RecordsRepository(prefs)
            .recordReaction(sessionId, GameMode.CLASSIC_RUSH, 199, List(5) { 199 })
        assertNull(duplicate.reward)
        assertEquals(completed.records, duplicate.records)
        assertEquals(saved, prefs.all)
    }

    @Test
    fun previouslyUnlockedAchievementsStayUnlockedWithoutBeingAnnouncedAgain() {
        val first = completeSession(GameMode.QUICK_DRAW, listOf(199))
        val second = completeSession(GameMode.QUICK_DRAW, listOf(198))

        assertEquals(3, checkNotNull(first.reward).newlyUnlocked.size)
        assertTrue(checkNotNull(second.reward).newlyUnlocked.isEmpty())
        assertEquals(first.records.achievements.unlocked, second.records.achievements.unlocked)
        assertEquals(2L, second.records.achievements.completedSessions)
        assertEquals(2, second.records.achievements.cleanSessionStreak)
    }

    @Test
    fun falseStartsBeforeAndAfterValidRoundsOnlyResetTheStreakWhenTheSessionCompletes() {
        repeat(2) { completeSession(GameMode.QUICK_DRAW, listOf(500)) }
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        val beforeFalseStart = prefs.all.toMap()

        repository.recordFalseStart(sessionId)

        assertTrue(prefs.getBoolean("active_session_had_false_start", false))
        assertEquals(1, prefs.getInt("active_session_false_start_count", -1))
        assertEquals(
            beforeFalseStart.filterKeys { it !in setOf("active_session_had_false_start", "active_session_false_start_count") },
            prefs.all.filterKeys { it !in setOf("active_session_had_false_start", "active_session_false_start_count") }
        )
        val markedDirty = prefs.all.toMap()
        repository.recordFalseStart(sessionId)
        assertEquals(2, prefs.getInt("active_session_false_start_count", -1))
        assertEquals(markedDirty.filterKeys { it != "active_session_false_start_count" },
            prefs.all.filterKeys { it != "active_session_false_start_count" })
        val firstRound = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 500, listOf(500))
        assertEquals(2, firstRound.records.achievements.cleanSessionStreak)
        assertEquals(2L, firstRound.records.achievements.completedSessions)
        val afterFirstRound = prefs.all.toMap()
        RecordsRepository(prefs).recordFalseStart(sessionId)
        assertEquals(3, prefs.getInt("active_session_false_start_count", -1))
        assertEquals(afterFirstRound.filterKeys { it != "active_session_false_start_count" },
            prefs.all.filterKeys { it != "active_session_false_start_count" })

        var completion = firstRound
        repeat(4) { index ->
            completion = repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 500, List(index + 2) { 500 })
        }
        assertEquals(3L, completion.records.achievements.completedSessions)
        assertEquals(0, completion.records.achievements.cleanSessionStreak)
        assertFalse(Achievement.CLEAN_SWEEP in completion.records.achievements.unlocked)
        assertTrue(Achievement.STEADY_HANDS in checkNotNull(completion.reward).newlyUnlocked)
        assertFalse(prefs.contains("active_session_had_false_start"))
        assertFalse(prefs.contains("active_session_false_start_count"))
        assertEquals(3, completion.records.history.sessions.first().falseStartCount)
    }

    @Test
    fun abandoningADirtySessionPreservesTheStreakAndTheNextSessionStartsClean() {
        repeat(4) { completeSession(GameMode.QUICK_DRAW, listOf(500)) }
        val savedProgress = repository.load()
        val abandonedId = repository.startSession(GameMode.CLASSIC_RUSH)
        repository.recordFalseStart(abandonedId)
        repository.recordReaction(abandonedId, GameMode.CLASSIC_RUSH, 500, listOf(500))
        repository.abandonSession(abandonedId)

        assertEquals(savedProgress.achievements, repository.load().achievements)
        assertEquals(savedProgress.totalXp, repository.load().totalXp)
        assertFalse(prefs.contains("active_session_had_false_start"))
        val nextId = repository.startSession(GameMode.QUICK_DRAW)
        assertFalse(prefs.getBoolean("active_session_had_false_start", true))
        val completed = repository.recordReaction(nextId, GameMode.QUICK_DRAW, 500, listOf(500))
        assertEquals(5L, completed.records.achievements.completedSessions)
        assertEquals(5, completed.records.achievements.cleanSessionStreak)
        assertEquals(setOf(Achievement.CLEAN_SWEEP), checkNotNull(completed.reward).newlyUnlocked)
    }

    @Test
    fun staleOrBlankFalseStartsCannotDirtyTheCurrentSession() {
        val oldId = repository.startSession(GameMode.CLASSIC_RUSH)
        val currentId = repository.startSession(GameMode.QUICK_DRAW)
        val saved = prefs.all.toMap()
        listOf(oldId, "", " ", "unknown").forEach { token ->
            RecordsRepository(prefs).recordFalseStart(token)
            assertEquals(saved, prefs.all)
        }

        val completed = repository.recordReaction(currentId, GameMode.QUICK_DRAW, 500, listOf(500))
        assertEquals(1, completed.records.achievements.cleanSessionStreak)
        val afterCompletion = prefs.all.toMap()
        repository.recordFalseStart(currentId)
        assertEquals(afterCompletion, prefs.all)
    }

    @Test
    fun missingOrWronglyTypedFalseStartMetadataCannotAwardACleanSession() {
        val corruptions: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { it.remove("active_session_had_false_start") },
            { it.putString("active_session_had_false_start", "false") },
            { it.putInt("active_session_had_false_start", 0) }
        )
        corruptions.forEach { corrupt ->
            assertTrue(prefs.edit().clear().putLong("completed_sessions", 4).putInt("clean_session_streak", 4).commit())
            val sessionId = repository.startSession(GameMode.QUICK_DRAW)
            val editor = prefs.edit()
            corrupt(editor)
            assertTrue(editor.commit())

            val completed = repository.recordReaction(sessionId, GameMode.QUICK_DRAW, 500, listOf(500))
            assertEquals(5L, completed.records.achievements.completedSessions)
            assertEquals(0, completed.records.achievements.cleanSessionStreak)
            assertFalse(Achievement.CLEAN_SWEEP in completed.records.achievements.unlocked)
        }
    }

    @Test
    fun completedSessionMilestonesUnlockAtTheirExactThresholdsWithoutBonusXp() {
        val milestones = listOf(
            10L to Achievement.GETTING_STARTED,
            50L to Achievement.DEDICATED,
            100L to Achievement.VETERAN
        )
        milestones.forEach { (threshold, achievement) ->
            assertTrue(prefs.edit().clear().putLong("completed_sessions", threshold - 1).commit())
            val before = repository.load()
            assertFalse(achievement in before.achievements.unlocked)

            val completed = completeSession(GameMode.QUICK_DRAW, listOf(500), hadFalseStart = true)
            assertEquals(threshold, completed.records.achievements.completedSessions)
            assertEquals(setOf(achievement), checkNotNull(completed.reward).newlyUnlocked)
            assertEquals(10L, completed.records.totalXp)
            assertEquals(10, checkNotNull(completed.reward).awardedXp)
        }
    }

    @Test
    fun completionUsesTheNewXpLevelForAchievementEvaluation() {
        assertTrue(prefs.edit().putLong("total_xp", 999).commit())

        val completed = completeSession(GameMode.QUICK_DRAW, listOf(500))

        assertEquals(1_009L, completed.records.totalXp)
        assertEquals(4, checkNotNull(completed.reward).previousLevel)
        assertEquals(5, checkNotNull(completed.reward).newLevel)
        assertEquals(setOf(Achievement.RISING_STAR), checkNotNull(completed.reward).newlyUnlocked)
        assertEquals(1L, completed.records.achievements.completedSessions)
    }

    @Test
    fun migrationRecoversOnlyProvableModeMedalAndLevelAchievements() {
        assertTrue(prefs.edit()
            .putInt("best", 100)
            .putInt("attempts", 10_000)
            .putInt("record_quick_draw", 199)
            .putInt("record_classic_rush", 250)
            .putInt("record_endurance_rush", 320)
            .putLong("total_xp", 30_000)
            .commit())
        val oldValues = prefs.all.toMap()

        val migrated = repository.load()

        val expected = setOf(
            Achievement.GOLD_REFLEX, Achievement.PLATINUM_REFLEX, Achievement.ELITE_REFLEX,
            Achievement.RISING_STAR, Achievement.REFLEX_MASTER, Achievement.LEGEND
        )
        assertEquals(expected, migrated.achievements.unlocked)
        assertEquals(0L, migrated.achievements.completedSessions)
        assertEquals(0, migrated.achievements.cleanSessionStreak)
        assertEquals(oldValues, prefs.all.filterKeys { it != "unlocked_achievement_ids" })
        assertFalse(prefs.contains("completed_sessions"))
        assertFalse(prefs.contains("clean_session_streak"))
        assertEquals(expected.map { it.name }.toSet(), prefs.getStringSet("unlocked_achievement_ids", null))
        val afterMigration = prefs.all.toMap()
        assertEquals(migrated, RecordsRepository(prefs).load())
        assertEquals(afterMigration, prefs.all)
    }

    @Test
    fun migrationDoesNotGuessAchievementsFromLifetimeReactionsOrOverallBest() {
        assertTrue(prefs.edit().putInt("best", 100).putInt("attempts", 10_000).commit())
        val saved = prefs.all.toMap()

        val loaded = repository.load()

        assertEquals(AchievementState(), loaded.achievements)
        assertEquals(saved, prefs.all)
    }

    @Test
    fun migrationRecoversStoredCountAndStreakMilestonesWithoutIncrementingThem() {
        assertTrue(prefs.edit().putLong("completed_sessions", 100).putInt("clean_session_streak", 5).commit())

        val loaded = repository.load()

        assertEquals(100L, loaded.achievements.completedSessions)
        assertEquals(5, loaded.achievements.cleanSessionStreak)
        assertEquals(
            setOf(Achievement.GETTING_STARTED, Achievement.DEDICATED, Achievement.VETERAN, Achievement.CLEAN_SWEEP),
            loaded.achievements.unlocked
        )
        assertEquals(100L, prefs.getLong("completed_sessions", -1))
        assertEquals(5, prefs.getInt("clean_session_streak", -1))
    }

    @Test
    fun corruptAchievementValuesAreSafeAndKnownUnlockedIdsRemainPermanent() {
        assertTrue(prefs.edit()
            .putStringSet("unlocked_achievement_ids", setOf("LEGEND", "UNKNOWN_ACHIEVEMENT"))
            .putLong("completed_sessions", -10)
            .putInt("clean_session_streak", Int.MAX_VALUE)
            .commit())

        val loaded = repository.load()

        val implied = setOf(Achievement.RISING_STAR, Achievement.REFLEX_MASTER, Achievement.LEGEND)
        assertEquals(implied, loaded.achievements.unlocked)
        assertEquals(0L, loaded.achievements.completedSessions)
        assertEquals(0, loaded.achievements.cleanSessionStreak)
        val completed = completeSession(GameMode.QUICK_DRAW, listOf(500))
        assertEquals(implied, completed.records.achievements.unlocked)
        assertTrue(checkNotNull(completed.reward).newlyUnlocked.isEmpty())
        assertEquals(implied.map { it.name }.toSet(), prefs.getStringSet("unlocked_achievement_ids", null))
    }

    @Test
    fun wronglyTypedAchievementPreferencesDefaultSafelyAndStreakCannotExceedCompletedCount() {
        assertTrue(prefs.edit()
            .putString("unlocked_achievement_ids", "ELITE_REFLEX")
            .putInt("completed_sessions", 50)
            .putLong("clean_session_streak", 5)
            .commit())
        assertEquals(AchievementState(), repository.load().achievements)

        assertTrue(prefs.edit().putLong("completed_sessions", 2).putInt("clean_session_streak", Int.MAX_VALUE).commit())
        val clamped = repository.load().achievements
        assertEquals(2L, clamped.completedSessions)
        assertEquals(2, clamped.cleanSessionStreak)
        assertFalse(Achievement.CLEAN_SWEEP in clamped.unlocked)
    }

    @Test
    fun achievementCountersSaturateAndDirtyCompletionCanResetASaturatedStreak() {
        assertTrue(prefs.edit()
            .putLong("completed_sessions", Long.MAX_VALUE)
            .putInt("clean_session_streak", Int.MAX_VALUE)
            .commit())
        repository.load()

        val clean = completeSession(GameMode.QUICK_DRAW, listOf(500))
        assertEquals(Long.MAX_VALUE, clean.records.achievements.completedSessions)
        assertEquals(Int.MAX_VALUE, clean.records.achievements.cleanSessionStreak)
        val dirty = completeSession(GameMode.QUICK_DRAW, listOf(500), hadFalseStart = true)
        assertEquals(Long.MAX_VALUE, dirty.records.achievements.completedSessions)
        assertEquals(0, dirty.records.achievements.cleanSessionStreak)
        assertTrue(Achievement.CLEAN_SWEEP in dirty.records.achievements.unlocked)
        assertTrue(checkNotNull(dirty.reward).newlyUnlocked.isEmpty())
    }

    @Test
    fun everyModeCapturesItsCompletedSessionWithClockScoresAndExactFalseStarts() {
        var completedAt = 1_800_000_000_000L
        repository = RecordsRepository(prefs) { completedAt }
        val capturedIds = mutableListOf<String>()
        GameMode.entries.forEachIndexed { modeIndex, mode ->
            val scores = when (mode) {
                GameMode.QUICK_DRAW -> listOf(175)
                GameMode.CLASSIC_RUSH -> listOf(201, 210, 199, 220, 200)
                GameMode.ENDURANCE_RUSH -> List(10) { 200 + it * 2 }
            }
            val sessionId = repository.startSession(mode)
            repeat(modeIndex) { repository.recordFalseStart(sessionId) }
            var completed: ReactionUpdate? = null
            scores.forEachIndexed { index, score ->
                completed = repository.recordReaction(sessionId, mode, score, scores.take(index + 1))
            }
            val update = checkNotNull(completed)
            val entry = update.records.history.sessions.first()
            capturedIds += sessionId
            assertEquals(sessionId, entry.sessionId)
            assertEquals(mode, entry.mode)
            assertEquals(completedAt, entry.completedAtMillis)
            assertEquals(scores, entry.roundScoresMs)
            assertEquals(scores.minOrNull(), entry.bestTimeMs)
            assertEquals(PlayerProgression.sessionScore(mode, scores), entry.averageTimeMs)
            assertEquals(checkNotNull(scores.maxOrNull()) - checkNotNull(scores.minOrNull()), entry.spreadMs)
            assertEquals(modeIndex, entry.falseStartCount)
            assertEquals(checkNotNull(update.reward).awardedXp, entry.xpEarned)
            assertEquals(checkNotNull(update.reward).medal, entry.medal)
            assertEquals(1L, update.records.completedByMode[mode])
            assertFalse(prefs.contains("active_session_false_start_count"))
            completedAt++
        }
        val reloaded = RecordsRepository(prefs).load()
        assertEquals(capturedIds.reversed(), reloaded.history.sessions.map { it.sessionId })
        assertEquals(GameMode.entries.associateWith { 1L }, reloaded.completedByMode)
        assertFalse(reloaded.history.hadUnreadableEntries)
    }

    @Test
    fun historyStoresActualCreditedXpAtTheXpLimit() {
        assertTrue(prefs.edit().putLong("total_xp", Long.MAX_VALUE).commit())
        val completed = completeSession(GameMode.QUICK_DRAW, listOf(250))

        assertEquals(0, checkNotNull(completed.reward).awardedXp)
        assertTrue(checkNotNull(completed.reward).xp.totalXp > 0)
        assertEquals(0, completed.records.history.sessions.single().xpEarned)
        assertEquals(0, RecordsRepository(prefs).load().history.sessions.single().xpEarned)
    }

    @Test
    fun historyClockAndSerializationAreUsedOnlyOnAcceptedCompletion() {
        val unreadableHistory = "{invalid history"
        assertTrue(prefs.edit().putString("session_history", unreadableHistory).commit())
        var clockCalls = 0
        repository = RecordsRepository(prefs) { clockCalls++; 123L }
        assertTrue(repository.load().history.hadUnreadableEntries)
        val sessionId = repository.startSession(GameMode.CLASSIC_RUSH)
        repository.recordFalseStart(sessionId)
        repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 300, listOf(300))
        repository.recordReaction(sessionId, GameMode.CLASSIC_RUSH, 300, listOf(300))
        repository.recordReaction("stale", GameMode.CLASSIC_RUSH, 300, List(2) { 300 })
        repository.abandonSession(sessionId)

        assertEquals(0, clockCalls)
        assertEquals(unreadableHistory, prefs.getString("session_history", null))
        assertFalse(prefs.contains("completed_sessions_classic_rush"))
        val completed = completeSession(GameMode.QUICK_DRAW, listOf(500))
        assertEquals(1, clockCalls)
        assertEquals(123L, completed.records.history.sessions.single().completedAtMillis)
        assertFalse(completed.records.history.hadUnreadableEntries)
        assertFalse(SessionHistoryCodec.decode(prefs.getString("session_history", null)).hadUnreadableEntries)
        assertEquals(1L, completed.records.completedByMode[GameMode.QUICK_DRAW])
        assertEquals(0L, completed.records.completedByMode[GameMode.CLASSIC_RUSH])
    }

    @Test
    fun historyRetainsOnlyNewestHundredWhileLifetimeModeCounterKeepsGrowing() {
        var timestamp = 0L
        repository = RecordsRepository(prefs) { ++timestamp }
        val completedIds = mutableListOf<String>()
        repeat(105) {
            completedIds += completeSession(GameMode.QUICK_DRAW, listOf(500))
                .records.history.sessions.first().sessionId
        }

        val reloaded = RecordsRepository(prefs).load()
        assertEquals(SessionHistoryCodec.MAX_SESSIONS, reloaded.history.sessions.size)
        assertEquals(completedIds.takeLast(100).reversed(), reloaded.history.sessions.map { it.sessionId })
        assertEquals(105L, reloaded.completedByMode[GameMode.QUICK_DRAW])
        assertEquals(105L, prefs.getLong("completed_sessions_quick_draw", 0))
        assertEquals(105L, reloaded.achievements.completedSessions)
        assertEquals(105, reloaded.lifetimeReactions)
        assertEquals(1_050L, reloaded.totalXp)
    }

    @Test
    fun unreadableHistoryEntryDoesNotDiscardOtherValidSessionsOrExistingProgress() {
        completeSession(GameMode.QUICK_DRAW, listOf(250))
        completeSession(GameMode.CLASSIC_RUSH, List(5) { 300 })
        val before = repository.load()
        val json = JSONObject(checkNotNull(prefs.getString("session_history", null)))
        json.getJSONArray("sessions").put(JSONObject().put("sessionId", "broken-entry"))
        val damaged = json.toString()
        assertTrue(prefs.edit().putString("session_history", damaged).commit())

        val recovered = RecordsRepository(prefs).load()
        assertTrue(recovered.history.hadUnreadableEntries)
        assertEquals(before.history.sessions, recovered.history.sessions)
        assertEquals(before.totalXp, recovered.totalXp)
        assertEquals(before.achievements, recovered.achievements)
        assertEquals(damaged, prefs.getString("session_history", null))

        val completed = completeSession(GameMode.QUICK_DRAW, listOf(500))
        assertEquals(3, completed.records.history.sessions.size)
        assertEquals(before.history.sessions, completed.records.history.sessions.drop(1))
        assertFalse(completed.records.history.hadUnreadableEntries)
        assertEquals(completed.records.history, RecordsRepository(prefs).load().history)
    }

    @Test
    fun wronglyTypedOrOversizedHistoryIsWarnedAboutWithoutChangingOtherSaves() {
        assertTrue(prefs.edit().putInt("best", 200).putInt("attempts", 9).putLong("total_xp", 99)
            .putInt("session_history", 7).commit())
        val original = prefs.all.toMap()
        val loaded = repository.load()
        assertEquals(SessionHistory(hadUnreadableEntries = true), loaded.history)
        assertEquals(200, loaded.lifetimeBestMs)
        assertEquals(9, loaded.lifetimeReactions)
        assertEquals(99L, loaded.totalXp)
        assertEquals(original, prefs.all)

        val oversized = " ".repeat(200_001)
        assertTrue(prefs.edit().putString("session_history", oversized).commit())
        assertEquals(SessionHistory(hadUnreadableEntries = true), repository.load().history)
        assertEquals(oversized, prefs.getString("session_history", null))
    }

    @Test
    fun preStatisticsProgressDoesNotInventHistoryOrPerModeCompletionCounts() {
        assertTrue(prefs.edit().putInt("best", 180).putInt("attempts", 800)
            .putInt("record_quick_draw", 180).putLong("total_xp", 30_000)
            .putLong("completed_sessions", 100).putInt("clean_session_streak", 7).commit())

        val loaded = repository.load()

        assertEquals(SessionHistory(), loaded.history)
        assertEquals(GameMode.entries.associateWith { 0L }, loaded.completedByMode)
        assertEquals(100L, loaded.achievements.completedSessions)
        assertEquals(7, loaded.achievements.cleanSessionStreak)
        assertFalse(prefs.contains("session_history"))
        assertFalse(prefs.contains("completed_sessions_quick_draw"))
        assertEquals(30_000L, loaded.totalXp)
        assertEquals(180, loaded.bestFor(GameMode.QUICK_DRAW))
    }

    @Test
    fun retainedHistoryRecoversOnlyALowerBoundForDamagedModeCountersWithoutLoadWrites() {
        repeat(3) { completeSession(GameMode.QUICK_DRAW, listOf(500)) }
        completeSession(GameMode.CLASSIC_RUSH, List(5) { 500 })
        assertTrue(prefs.edit().putLong("completed_sessions_quick_draw", 1)
            .putString("completed_sessions_classic_rush", "invalid")
            .putLong("completed_sessions_endurance_rush", -1).commit())
        val saved = prefs.all.toMap()

        val recovered = RecordsRepository(prefs).load()
        assertEquals(3L, recovered.completedByMode[GameMode.QUICK_DRAW])
        assertEquals(1L, recovered.completedByMode[GameMode.CLASSIC_RUSH])
        assertEquals(0L, recovered.completedByMode[GameMode.ENDURANCE_RUSH])
        assertEquals(saved, prefs.all)

        val next = completeSession(GameMode.QUICK_DRAW, listOf(500))
        assertEquals(4L, next.records.completedByMode[GameMode.QUICK_DRAW])
        assertEquals(4L, prefs.getLong("completed_sessions_quick_draw", 0))
        assertEquals(5L, next.records.achievements.completedSessions)
        assertEquals(1L, prefs.getLong("completed_sessions_classic_rush", 0))
    }

    @Test
    fun modeCompletionAndFalseStartCountersSaturateWithoutOverflowing() {
        assertTrue(prefs.edit().putLong("completed_sessions_quick_draw", Long.MAX_VALUE).commit())
        val sessionId = repository.startSession(GameMode.QUICK_DRAW)
        assertTrue(prefs.edit().putInt("active_session_false_start_count", Int.MAX_VALUE)
            .putBoolean("active_session_had_false_start", true).commit())
        repository.recordFalseStart(sessionId)
        assertEquals(Int.MAX_VALUE, prefs.getInt("active_session_false_start_count", -1))

        val completed = repository.recordReaction(sessionId, GameMode.QUICK_DRAW, 500, listOf(500))
        assertEquals(Long.MAX_VALUE, completed.records.completedByMode[GameMode.QUICK_DRAW])
        assertEquals(Int.MAX_VALUE, completed.records.history.sessions.single().falseStartCount)
        assertEquals(0, completed.records.achievements.cleanSessionStreak)
    }

    @Test
    fun recoveredOtherModeCounterPersistsOnCompletionBeforeItsHistoryIsEvicted() {
        completeSession(GameMode.ENDURANCE_RUSH, List(10) { 500 })
        assertTrue(prefs.edit().putString("completed_sessions_endurance_rush", "invalid").commit())
        assertEquals(1L, RecordsRepository(prefs).load().completedByMode[GameMode.ENDURANCE_RUSH])
        assertEquals("invalid", prefs.getString("completed_sessions_endurance_rush", null))

        completeSession(GameMode.QUICK_DRAW, listOf(500))

        assertEquals(1L, prefs.getLong("completed_sessions_endurance_rush", 0))
        repeat(100) { completeSession(GameMode.QUICK_DRAW, listOf(500)) }
        val reloaded = RecordsRepository(prefs).load()
        assertEquals(SessionHistoryCodec.MAX_SESSIONS, reloaded.history.sessions.size)
        assertTrue(reloaded.history.sessions.none { it.mode == GameMode.ENDURANCE_RUSH })
        assertEquals(1L, reloaded.completedByMode[GameMode.ENDURANCE_RUSH])
        assertEquals(101L, reloaded.completedByMode[GameMode.QUICK_DRAW])
    }

    @Test
    fun legacyOrContradictoryFalseStartMetadataFailsClosedInHistoryAndAchievements() {
        val cases: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { it.remove("active_session_false_start_count").putBoolean("active_session_had_false_start", true) },
            { it.remove("active_session_false_start_count").putBoolean("active_session_had_false_start", false) },
            { it.putString("active_session_false_start_count", "0").putBoolean("active_session_had_false_start", false) },
            { it.putInt("active_session_false_start_count", -1).putBoolean("active_session_had_false_start", false) },
            { it.putInt("active_session_false_start_count", 0).putBoolean("active_session_had_false_start", true) },
            { it.putInt("active_session_false_start_count", 0).remove("active_session_had_false_start") },
            { it.putInt("active_session_false_start_count", 3).putBoolean("active_session_had_false_start", false) }
        )
        cases.forEachIndexed { index, corrupt ->
            assertTrue(prefs.edit().clear().commit())
            val sessionId = repository.startSession(GameMode.QUICK_DRAW)
            val editor = prefs.edit()
            corrupt(editor)
            assertTrue(editor.commit())
            val completed = repository.recordReaction(sessionId, GameMode.QUICK_DRAW, 500, listOf(500))
            assertEquals(if (index == cases.lastIndex) 3 else 1,
                completed.records.history.sessions.single().falseStartCount)
            assertEquals(0, completed.records.achievements.cleanSessionStreak)
        }
    }

    @Test
    fun unchangedHistoryIsReusedAcrossLoadsAndPartialRoundsAndCallerScoresAreCopied() {
        val mutableScores = mutableListOf(250)
        val sessionId = repository.startSession(GameMode.QUICK_DRAW)
        val completed = repository.recordReaction(sessionId, GameMode.QUICK_DRAW, 250, mutableScores)
        mutableScores[0] = 999
        assertEquals(listOf(250), completed.records.history.sessions.single().roundScoresMs)
        assertSame(completed.records.history, repository.load().history)
        val newId = repository.startSession(GameMode.CLASSIC_RUSH)
        val partial = repository.recordReaction(newId, GameMode.CLASSIC_RUSH, 300, listOf(300))
        assertSame(completed.records.history, partial.records.history)
        assertSame(completed.records.history, repository.load().history)
    }

    @Test
    fun invalidInjectedClockIsClampedToAPositivePersistableTimestamp() {
        repository = RecordsRepository(prefs) { -1L }
        val completed = completeSession(GameMode.QUICK_DRAW, listOf(500))
        assertEquals(1L, completed.records.history.sessions.single().completedAtMillis)
        assertEquals(completed.records.history, RecordsRepository(prefs).load().history)
    }

    private fun completeSession(mode: GameMode, scores: List<Int>, hadFalseStart: Boolean = false): ReactionUpdate {
        require(scores.size == mode.rounds)
        val sessionId = repository.startSession(mode)
        if (hadFalseStart) repository.recordFalseStart(sessionId)
        val updates = scores.indices.map { index ->
            repository.recordReaction(sessionId, mode, scores[index], scores.take(index + 1))
        }
        updates.dropLast(1).forEach {
            assertFalse(it.isNewRecord)
            assertNull(it.reward)
        }
        assertNotNull(updates.last().reward)
        return updates.last()
    }
}
