package com.maurointeractive.reactionrush

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionHistoryTest {
    @Test
    fun missingAndCanonicalEmptyHistoryHaveNoRecoveryWarning() {
        assertEquals(SessionHistory(), SessionHistoryCodec.decode(null))
        val raw = SessionHistoryCodec.encode(emptyList())
        assertEquals(1, JSONObject(raw).getInt("version"))
        assertEquals(0, JSONObject(raw).getJSONArray("sessions").length())
        assertEquals(SessionHistory(), SessionHistoryCodec.decode(raw))
    }

    @Test
    fun allModesRoundTripTheirCoreAndDerivedSessionFacts() {
        val sessions = listOf(
            entry(GameMode.QUICK_DRAW, "quick", listOf(199), falseStarts = 3, xp = 14),
            entry(GameMode.CLASSIC_RUSH, "classic", listOf(100, 200, 300, 400, 502), falseStarts = 1, xp = 40),
            entry(GameMode.ENDURANCE_RUSH, "endurance", List(9) { 200 } + 205, xp = 100)
        )
        val decoded = SessionHistoryCodec.decode(SessionHistoryCodec.encode(sessions))
        assertEquals(sessions, decoded.sessions)
        assertFalse(decoded.hadUnreadableEntries)
        assertEquals(100, decoded.sessions[1].bestTimeMs)
        assertEquals(300, decoded.sessions[1].averageTimeMs)
        assertEquals(402, decoded.sessions[1].spreadMs)
        assertEquals(PerformanceMedal.SILVER, decoded.sessions[1].medal)
        assertEquals(201, decoded.sessions[2].averageTimeMs)
    }

    @Test
    fun retentionPreservesNewestInsertionOrderEvenWithEqualOrBackwardTimestamps() {
        val sessions = List(103) { index ->
            entry(id = "id-$index", timestamp = if (index % 2 == 0) 10L else 10_000L)
        }
        val encoded = SessionHistoryCodec.decode(SessionHistoryCodec.encode(sessions))
        assertEquals(sessions.take(100), encoded.sessions)
        assertFalse(encoded.hadUnreadableEntries)
        val oversizedPayload = payload(*sessions.map { jsonEntry(it) }.toTypedArray())
        val decoded = SessionHistoryCodec.decode(oversizedPayload)
        assertEquals(sessions.take(100), decoded.sessions)
        assertFalse(decoded.hadUnreadableEntries)
    }

    @Test
    fun prependBoundsHistoryClearsRecoveryWarningAndDoesNotSortByClock() {
        val previous = List(100) { entry(id = "old-$it", timestamp = 1_000L) }
        val incoming = entry(id = "new", timestamp = 1L)
        val updated = SessionHistoryCodec.prepend(SessionHistory(previous, true), incoming)
        assertEquals(listOf(incoming) + previous.take(99), updated.sessions)
        assertFalse(updated.hadUnreadableEntries)
        assertEquals(100, previous.size)
        assertEquals(updated, SessionHistoryCodec.decode(SessionHistoryCodec.encode(updated.sessions)))
    }

    @Test
    fun duplicateIdsKeepTheFirstValidOccurrenceAndSignalRecoveryOnDecode() {
        val first = entry(id = "same", scores = listOf(200))
        val duplicate = entry(id = "same", scores = listOf(300))
        val other = entry(id = "other")
        val decoded = SessionHistoryCodec.decode(payload(jsonEntry(first), jsonEntry(duplicate), jsonEntry(other)))
        assertEquals(listOf(first, other), decoded.sessions)
        assertTrue(decoded.hadUnreadableEntries)
        assertEquals(listOf(first, other), SessionHistoryCodec.decode(
            SessionHistoryCodec.encode(listOf(first, duplicate, other))
        ).sessions)
        assertEquals(listOf(duplicate, other), SessionHistoryCodec.prepend(
            SessionHistory(listOf(first, other)), duplicate
        ).sessions)
    }

    @Test
    fun anInvalidOccurrenceDoesNotHideALaterValidEntryWithTheSameId() {
        val valid = entry(id = "same")
        val invalid = jsonEntry(valid).put("xpEarned", -1)
        val decoded = SessionHistoryCodec.decode(payload(invalid, jsonEntry(valid)))
        assertEquals(listOf(valid), decoded.sessions)
        assertTrue(decoded.hadUnreadableEntries)
    }

    @Test
    fun wrongTopLevelSchemaVersionAndMalformedPayloadsRecoverToAnEmptyHistory() {
        val payloads = listOf(
            "", " ", "null", "[]", "not json", "{", "{\"version\":1,\"sessions\":[}",
            "{\"sessions\":[]}", "{\"version\":1}", "{\"version\":1,\"sessions\":{}}",
            "{\"version\":2,\"sessions\":[]}", "{\"version\":\"1\",\"sessions\":[]}",
            "{\"version\":1.0,\"sessions\":[]}", "{\"version\":null,\"sessions\":[]}",
            "{\"version\":1,\"sessions\":[]} trailing"
        )
        payloads.forEach { raw ->
            assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(raw))
        }
    }

    @Test
    fun oversizedAndDeeplyNestedPayloadsAreRejectedBeforeParsing() {
        assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(" ".repeat(200_001)))
        val deep = "{\"version\":1,\"sessions\":[],\"ignored\":" + "[".repeat(1_000) + "0" + "]".repeat(1_000) + "}"
        assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(deep))
    }

    @Test
    fun strictPreflightRejectsSingleQuoteUnquotedLiteralAndCommentDepthBypasses() {
        val nested = "[".repeat(32) + "0" + "]".repeat(32)
        val payloads = listOf(
            "{'version':1,'sessions':[],'a':'x\"','b':$nested,'c':'x\"'}",
            "{version:1,sessions:[],a:x\",b:$nested,c:x\"}",
            "{\"version\":1,\"sessions\":[],\"a\":0/* \" */,\"b\":$nested,\"c\":0/* \" */}"
        )
        payloads.forEach { raw ->
            assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(raw))
        }
    }

    @Test
    fun strictPreflightRejectsOrdinaryNonstandardJsonSyntax() {
        val payloads = listOf(
            "{'version':1,'sessions':[]}",
            "{version:1,sessions:[]}",
            "{\"version\":1,\"sessions\":[],\"ignored\":unquoted}",
            "/* comment */{\"version\":1,\"sessions\":[]}",
            "{\"version\":1,// comment\n\"sessions\":[]}",
            "{\"version\":1,# comment\n\"sessions\":[]}",
            "{\"version\"=1;\"sessions\":[]}",
            "{\"version\":1,\"sessions\":[,]}",
            "{\"version\":1,\"sessions\":[],}",
            "{\"version\":1,\"sessions\":[]}{}"
        )
        payloads.forEach { raw ->
            assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(raw))
        }
    }

    @Test
    fun strictPreflightAcceptsDepthSixteenAndRejectsSeventeen() {
        fun rawWithArrayDepth(depth: Int): String =
            "{\"version\":1,\"sessions\":[],\"ignored\":" + "[".repeat(depth) + "0" + "]".repeat(depth) + "}"
        assertEquals(SessionHistory(), SessionHistoryCodec.decode(rawWithArrayDepth(15)))
        assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(rawWithArrayDepth(16)))
    }

    @Test
    fun invalidCoreFieldsAreSkippedWhileValidSiblingsSurvive() {
        val good = entry(id = "valid-sibling")
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("sessionId", " ") }, { it.put("sessionId", "x".repeat(129)) }, { it.put("sessionId", 12) },
            { it.put("mode", "UNKNOWN") }, { it.put("mode", "quick_draw") },
            { it.put("completedAtMillis", 0) }, { it.put("completedAtMillis", -1) },
            { it.put("completedAtMillis", "1") }, { it.put("completedAtMillis", 1.5) },
            { it.put("roundScoresMs", JSONArray().put(0)) }, { it.put("roundScoresMs", JSONArray().put(-1)) },
            { it.put("roundScoresMs", JSONArray().put(200.5)) },
            { it.put("roundScoresMs", JSONArray().put(Int.MAX_VALUE.toLong() + 1)) },
            { it.put("roundScoresMs", JSONArray()) }, { it.put("roundScoresMs", JSONArray().put(200).put(200)) },
            { it.put("roundScoresMs", "200") },
            { it.put("falseStartCount", -1) }, { it.put("falseStartCount", "0") },
            { it.put("falseStartCount", Int.MAX_VALUE.toLong() + 1) }, { it.put("falseStartCount", 0.5) },
            { it.put("xpEarned", -1) }, { it.put("xpEarned", 16) }, { it.put("xpEarned", "0") },
            { it.put("xpEarned", Int.MAX_VALUE.toLong() + 1) }, { it.put("xpEarned", 0.5) }
        )
        mutations.forEach { mutate ->
            val invalid = jsonEntry(entry(id = "invalid"))
            mutate(invalid)
            val decoded = SessionHistoryCodec.decode(payload(invalid, jsonEntry(good)))
            assertEquals(listOf(good), decoded.sessions)
            assertTrue(decoded.hadUnreadableEntries)
        }
        listOf("sessionId", "mode", "completedAtMillis", "roundScoresMs", "falseStartCount", "xpEarned").forEach { key ->
            val invalid = jsonEntry(entry(id = "invalid"))
            invalid.remove(key)
            val decoded = SessionHistoryCodec.decode(payload(invalid, jsonEntry(good)))
            assertEquals(listOf(good), decoded.sessions)
            assertTrue(decoded.hadUnreadableEntries)
        }
        val invalidObjects = SessionHistoryCodec.decode(payload(JSONObject.NULL, "invalid", 1, jsonEntry(good)))
        assertEquals(listOf(good), invalidObjects.sessions)
        assertTrue(invalidObjects.hadUnreadableEntries)
    }

    @Test
    fun overflowingJsonNumbersCannotBecomeValidCoreFields() {
        val validRaw = jsonEntry(entry()).toString()
        listOf(
            validRaw.replace("\"completedAtMillis\":1000", "\"completedAtMillis\":9223372036854775808"),
            validRaw.replace("\"roundScoresMs\":[200]", "\"roundScoresMs\":[2147483648]"),
            validRaw.replace("\"falseStartCount\":0", "\"falseStartCount\":2147483648")
        ).forEach { invalid ->
            assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(
                "{\"version\":1,\"sessions\":[$invalid]}"
            ))
        }
    }

    @Test
    fun nonFiniteParsedNumbersInvalidateOnlyTheirEntryAndPreserveValidSiblings() {
        val goodBefore = entry(id = "good-before", xp = 10)
        val goodAfter = entry(GameMode.CLASSIC_RUSH, id = "good-after", xp = 40)
        val invalidRaw = jsonEntry(entry(id = "invalid")).toString()
        listOf("1e999", "-1e999").forEach { overflow ->
            val invalidXp = invalidRaw.replace("\"xpEarned\":0", "\"xpEarned\":$overflow")
            val raw = "{\"version\":1,\"sessions\":[${jsonEntry(goodBefore)},$invalidXp,${jsonEntry(goodAfter)}]}"
            val decoded = SessionHistoryCodec.decode(raw)
            assertEquals(listOf(goodBefore, goodAfter), decoded.sessions)
            assertTrue(decoded.hadUnreadableEntries)
            assertEquals(10, decoded.sessions[0].xpEarned)
            assertEquals(40, decoded.sessions[1].xpEarned)
            assertFalse(decoded.sessions.any { it.sessionId == "invalid" })
        }
    }

    @Test
    fun nonFiniteDerivedNumbersAreRepairedWhileUnknownFieldsStillSignalRecovery() {
        val good = entry()
        val encoded = jsonEntry(good).toString()
        val invalidDerived = encoded.replace("\"bestTimeMs\":200", "\"bestTimeMs\":1e999")
        assertEquals(SessionHistory(listOf(good), true), SessionHistoryCodec.decode(
            "{\"version\":1,\"sessions\":[$invalidDerived]}"
        ))
        assertEquals(SessionHistory(listOf(good), true), SessionHistoryCodec.decode(
            "{\"version\":1,\"sessions\":[$encoded],\"ignored\":1e999}"
        ))
    }

    @Test
    fun missingOrInconsistentDerivedFieldsAreRecomputedWithoutLosingTheSession() {
        val expected = entry(GameMode.CLASSIC_RUSH, scores = listOf(100, 200, 300, 400, 502))
        val missing = jsonEntry(expected)
        listOf("bestTimeMs", "averageTimeMs", "spreadMs", "medal").forEach { missing.remove(it) }
        val repaired = SessionHistoryCodec.decode(payload(missing))
        assertEquals(listOf(expected), repaired.sessions)
        assertTrue(repaired.hadUnreadableEntries)
        val wrong = jsonEntry(expected).put("bestTimeMs", -1).put("averageTimeMs", "300")
            .put("spreadMs", 0).put("medal", "ELITE")
        assertEquals(SessionHistory(listOf(expected), true), SessionHistoryCodec.decode(payload(wrong)))
        val canonical = SessionHistoryCodec.decode(SessionHistoryCodec.encode(repaired.sessions))
        assertEquals(SessionHistory(listOf(expected)), canonical)
    }

    @Test
    fun canonicalEncodingRecomputesDerivedFieldsButRejectsInvalidCoreFacts() {
        val valid = entry()
        val inconsistent = valid.copy(bestTimeMs = 0, averageTimeMs = -1, spreadMs = 100, medal = PerformanceMedal.UNRANKED)
        assertEquals(SessionHistory(listOf(valid)), SessionHistoryCodec.decode(SessionHistoryCodec.encode(listOf(inconsistent))))
        listOf(
            valid.copy(sessionId = ""), valid.copy(sessionId = "x".repeat(129)),
            valid.copy(completedAtMillis = 0), valid.copy(falseStartCount = -1),
            valid.copy(roundScoresMs = listOf(0)), valid.copy(roundScoresMs = emptyList()),
            valid.copy(xpEarned = -1), valid.copy(xpEarned = 16)
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SessionHistoryCodec.encode(listOf(invalid)) }
            assertThrows(IllegalArgumentException::class.java) { SessionHistoryCodec.prepend(SessionHistory(), invalid) }
        }
    }

    @Test
    fun creditedXpCanBeZeroOrNominalButCannotExceedTheComputedReward() {
        GameMode.entries.forEach { mode ->
            val scores = List(mode.rounds) { 150 }
            val nominal = PlayerProgression.reward(mode, scores).totalXp
            listOf(0, nominal).forEach { xp ->
                val valid = entry(mode, scores = scores, xp = xp, falseStarts = Int.MAX_VALUE, timestamp = Long.MAX_VALUE)
                assertEquals(SessionHistory(listOf(valid)), SessionHistoryCodec.decode(SessionHistoryCodec.encode(listOf(valid))))
            }
            val invalid = jsonEntry(entry(mode, scores = scores)).put("xpEarned", nominal + 1)
            assertEquals(SessionHistory(hadUnreadableEntries = true), SessionHistoryCodec.decode(payload(invalid)))
        }
    }

    @Test
    fun maximumEscapedIdsAndScoresStillProduceSupportedBoundedOutput() {
        val sessions = List(100) { index ->
            entry(
                GameMode.ENDURANCE_RUSH,
                id = "\u0001".repeat(125) + index.toString().padStart(3, '0'),
                scores = List(10) { Int.MAX_VALUE },
                timestamp = Long.MAX_VALUE,
                falseStarts = Int.MAX_VALUE,
                xp = 125
            )
        }
        val raw = SessionHistoryCodec.encode(sessions)
        assertTrue(raw.length <= 200_000)
        assertTrue(raw.length > 100_000)
        assertEquals(SessionHistory(sessions), SessionHistoryCodec.decode(raw))
    }

    @Test
    fun escapedBracketsAndQuotesInIdsDoNotTriggerTheNestingGuard() {
        val valid = entry(id = "quoted\"\\[{]}\nidentifier")
        assertEquals(SessionHistory(listOf(valid)), SessionHistoryCodec.decode(SessionHistoryCodec.encode(listOf(valid))))
    }

    @Test
    fun emptyStatisticsIncludeEveryModeWithNullAveragesAndTrends() {
        val statistics = PlayerStatistics.fromHistory(emptyList())
        assertEquals(0, statistics.sessionCount)
        assertEquals(0, statistics.reactionCount)
        assertNull(statistics.averageReactionMs)
        assertEquals(GameMode.entries.toSet(), statistics.byMode.keys)
        statistics.byMode.values.forEach {
            assertEquals(ModeHistoryStatistics(0, 0, null, null), it)
        }
    }

    @Test
    fun overallAverageWeightsActualReactionsRatherThanSessionAverages() {
        val sessions = listOf(
            entry(GameMode.QUICK_DRAW, "quick", listOf(100)),
            entry(GameMode.CLASSIC_RUSH, "classic", List(5) { 200 }),
            entry(GameMode.ENDURANCE_RUSH, "endurance", List(10) { 300 })
        )
        val statistics = PlayerStatistics.fromHistory(sessions)
        assertEquals(3, statistics.sessionCount)
        assertEquals(16, statistics.reactionCount)
        assertEquals(256, statistics.averageReactionMs)
        assertEquals(ModeHistoryStatistics(1, 1, 100, null), statistics.byMode[GameMode.QUICK_DRAW])
        assertEquals(ModeHistoryStatistics(1, 5, 200, null), statistics.byMode[GameMode.CLASSIC_RUSH])
        assertEquals(ModeHistoryStatistics(1, 10, 300, null), statistics.byMode[GameMode.ENDURANCE_RUSH])
        assertEquals(256, PlayerStatistics.fromHistory(sessions.map { it.copy(averageTimeMs = 1) }).averageReactionMs)
    }

    @Test
    fun reactionAveragesRoundHalfMillisecondsUpAndAvoidIntegerSumOverflow() {
        assertEquals(201, PlayerStatistics.fromHistory(listOf(
            entry(id = "one", scores = listOf(200)), entry(id = "two", scores = listOf(201))
        )).averageReactionMs)
        val extremes = List(100) { entry(GameMode.ENDURANCE_RUSH, "id-$it", List(10) { Int.MAX_VALUE }) }
        val statistics = PlayerStatistics.fromHistory(extremes)
        assertEquals(1_000, statistics.reactionCount)
        assertEquals(Int.MAX_VALUE, statistics.averageReactionMs)
        assertEquals(Int.MAX_VALUE, statistics.byMode.getValue(GameMode.ENDURANCE_RUSH).averageReactionMs)
    }

    @Test
    fun trendsRequireTenSessionsForTheSameModeAndUseTheTwoMostRecentGroups() {
        GameMode.entries.forEach { mode ->
            val sessions = List(5) { entry(mode, "recent-$it", List(mode.rounds) { 200 }, timestamp = 1) } +
                List(5) { entry(mode, "previous-$it", List(mode.rounds) { 300 }, timestamp = 10_000) }
            assertNull(PlayerStatistics.fromHistory(sessions.take(9)).byMode.getValue(mode).trend)
            assertEquals(PerformanceTrend(200, 300), PlayerStatistics.fromHistory(sessions).byMode.getValue(mode).trend)
            val withOlder = sessions + List(10) { entry(mode, "old-$it", List(mode.rounds) { 1 }) }
            assertEquals(PerformanceTrend(200, 300), PlayerStatistics.fromHistory(withOlder).byMode.getValue(mode).trend)
        }
        val mixedModes = List(10) { index ->
            val mode = GameMode.entries[index % GameMode.entries.size]
            entry(mode, "mixed-$index", List(mode.rounds) { 200 })
        }
        PlayerStatistics.fromHistory(mixedModes).byMode.values.forEach { assertNull(it.trend) }
    }

    @Test
    fun trendImprovementIsPositiveForFasterNegativeForSlowerAndZeroForUnchanged() {
        listOf(200 to 100, 300 to 0, 400 to -100).forEach { (recent, improvement) ->
            val sessions = List(5) { entry(id = "recent-$it", scores = listOf(recent)) } +
                List(5) { entry(id = "previous-$it", scores = listOf(300)) }
            val trend = PlayerStatistics.fromHistory(sessions).byMode.getValue(GameMode.QUICK_DRAW).trend
            assertEquals(improvement, trend!!.improvementMs)
        }
    }

    @Test
    fun trendAveragesAllRoundsBeforeRoundingInsteadOfAveragingDisplayedSessionScores() {
        val recent = List(4) { entry(GameMode.CLASSIC_RUSH, "recent-$it", listOf(200, 200, 200, 201, 201)) } +
            entry(GameMode.CLASSIC_RUSH, "recent-4", listOf(201, 201, 201, 202, 202))
        val previous = List(5) { entry(GameMode.CLASSIC_RUSH, "previous-$it", List(5) { 201 }) }
        val trend = PlayerStatistics.fromHistory(recent + previous).byMode.getValue(GameMode.CLASSIC_RUSH).trend
        assertEquals(PerformanceTrend(201, 201), trend)
        assertEquals(0, trend!!.improvementMs)
    }

    @Test
    fun statisticsRespectRetentionAndFirstOccurrenceIdsWithoutTimestampSorting() {
        val first = entry(id = "same", scores = listOf(100), timestamp = 1)
        val duplicate = entry(id = "same", scores = listOf(900), timestamp = Long.MAX_VALUE)
        assertEquals(100, PlayerStatistics.fromHistory(listOf(first, duplicate)).averageReactionMs)
        val many = List(101) { entry(id = "id-$it", scores = listOf(if (it < 100) 200 else 900)) }
        val statistics = PlayerStatistics.fromHistory(many)
        assertEquals(100, statistics.sessionCount)
        assertEquals(100, statistics.reactionCount)
        assertEquals(200, statistics.averageReactionMs)
    }

    @Test
    fun statisticsRejectInvalidRoundListsBeforeSummingThem() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayerStatistics.fromHistory(listOf(entry().copy(roundScoresMs = emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlayerStatistics.fromHistory(listOf(entry().copy(roundScoresMs = listOf(-1))))
        }
    }

    private fun entry(
        mode: GameMode = GameMode.QUICK_DRAW,
        id: String = "session",
        scores: List<Int> = List(mode.rounds) { 200 },
        timestamp: Long = 1_000,
        falseStarts: Int = 0,
        xp: Int = 0
    ): SessionHistoryEntry {
        val average = PlayerProgression.sessionScore(mode, scores)
        val best = checkNotNull(scores.minOrNull())
        return SessionHistoryEntry(
            id, mode, timestamp, scores, best, average,
            (checkNotNull(scores.maxOrNull()).toLong() - best).toInt(), falseStarts, xp,
            PlayerProgression.medal(average)
        )
    }

    private fun jsonEntry(entry: SessionHistoryEntry): JSONObject =
        JSONObject(SessionHistoryCodec.encode(listOf(entry))).getJSONArray("sessions").getJSONObject(0)

    private fun payload(vararg entries: Any): String {
        val array = JSONArray()
        entries.forEach { array.put(it) }
        return JSONObject().put("version", 1).put("sessions", array).toString()
    }
}
