package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
                "record_endurance_rush" to 310
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
        val storedXp = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(preferencesFile).getElementsByTagName("long").item(0) as Element
        assertEquals("total_xp", storedXp.getAttribute("name"))
        assertEquals(repository.load().totalXp, storedXp.getAttribute("value").toLong())
        assertEquals(repository.load().totalXp, reloaded.totalXp)
        assertTrue(reloaded.totalXp > 0)
        assertFalse(prefs.contains("active_session_id"))
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
        assertEquals(0L, repository.load().totalXp)
        assertFalse(prefs.contains("total_xp"))
        assertFalse(prefs.contains("attempts"))

        val replacement = RecordsRepository(prefs).startSession(GameMode.ENDURANCE_RUSH)
        assertNotEquals(first, replacement)
        assertEquals(replacement, prefs.getString("active_session_id", null))
        assertEquals("ENDURANCE_RUSH", prefs.getString("active_session_mode", null))
        assertEquals(0, prefs.getInt("active_session_rounds", -1))
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

    private fun completeSession(mode: GameMode, scores: List<Int>): ReactionUpdate {
        require(scores.size == mode.rounds)
        val sessionId = repository.startSession(mode)
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
