package com.maurointeractive.reactionrush

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        GameMode.entries.forEach { assertEquals(0, records.bestFor(it)) }
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun legacyStatisticsArePreservedWithoutInventingModeRecords() {
        assertTrue(prefs.edit().putInt("best", 143).putInt("attempts", 29).commit())

        val records = repository.load()

        assertEquals(143, records.lifetimeBestMs)
        assertEquals(29, records.lifetimeReactions)
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
            val scores = mutableListOf<Int>()
            repeat(mode.rounds - 1) {
                scores += 150 + it
                val update = repository.recordReaction(mode, scores.last(), scores.toList())
                assertFalse(update.isNewRecord)
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

        val partial = repository.recordReaction(GameMode.CLASSIC_RUSH, 100, listOf(100))
        val reloaded = RecordsRepository(prefs).load()

        assertFalse(partial.isNewRecord)
        assertEquals(300, reloaded.bestFor(GameMode.CLASSIC_RUSH))
        assertEquals(100, reloaded.lifetimeBestMs)
        assertEquals(6, reloaded.lifetimeReactions)
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
    }

    @Test
    fun aNewSessionDoesNotReuseThePreviousSessionsNewRecordFlag() {
        assertTrue(completeSession(GameMode.QUICK_DRAW, listOf(250)).isNewRecord)

        val firstClassicRound = repository.recordReaction(GameMode.CLASSIC_RUSH, 100, listOf(100))

        assertFalse(firstClassicRound.isNewRecord)
        assertFalse(completeSession(GameMode.QUICK_DRAW, listOf(260)).isNewRecord)
    }

    @Test
    fun zeroAndNegativeTimesAreRejectedBeforeAnyStatisticsChange() {
        completeSession(GameMode.QUICK_DRAW, listOf(200))
        val before = prefs.all.toMap()

        listOf(0, -1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordReaction(GameMode.QUICK_DRAW, invalid, listOf(invalid))
            }
        }

        assertEquals(before, prefs.all)
    }

    @Test
    fun malformedSessionScoresAreRejectedBeforeAnyStatisticsChange() {
        val before = prefs.all.toMap()

        listOf(
            emptyList(),
            listOf(201),
            listOf(0, 200),
            listOf(-1, 200),
            List(6) { 200 }
        ).forEach { invalidScores ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.recordReaction(GameMode.CLASSIC_RUSH, 200, invalidScores)
            }
        }

        assertEquals(before, prefs.all)
    }

    private fun completeSession(mode: GameMode, scores: List<Int>): ReactionUpdate {
        require(scores.size == mode.rounds)
        val updates = scores.indices.map { index ->
            repository.recordReaction(mode, scores[index], scores.take(index + 1))
        }
        updates.dropLast(1).forEach { assertFalse(it.isNewRecord) }
        return updates.last()
    }
}
