package com.maurointeractive.reactionrush

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.StringReader
import kotlin.math.roundToInt

data class SessionHistoryEntry(
    val sessionId: String,
    val mode: GameMode,
    val completedAtMillis: Long,
    val roundScoresMs: List<Int>,
    val bestTimeMs: Int,
    val averageTimeMs: Int,
    val spreadMs: Int,
    val falseStartCount: Int,
    val xpEarned: Int,
    val medal: PerformanceMedal
)

data class SessionHistory(
    val sessions: List<SessionHistoryEntry> = emptyList(),
    val hadUnreadableEntries: Boolean = false
)

object SessionHistoryCodec {
    const val MAX_SESSIONS = 100
    private const val MAX_RAW_CHARS = 200_000
    private const val MAX_ID_CHARS = 128
    private const val VERSION = 1

    fun decode(raw: String?): SessionHistory {
        if (raw == null) return SessionHistory()
        if (raw.length > MAX_RAW_CHARS || !isBoundedStrictObject(raw)) return SessionHistory(hadUnreadableEntries = true)
        return try {
            var hadNonFiniteNumbers = false
            val tokener = object : JSONTokener(raw) {
                override fun nextValue(): Any? {
                    val value = super.nextValue()
                    if (value is Double && !value.isFinite()) {
                        // JSONObject rejects non-finite numbers while parsing. Preserve
                        // siblings by making only the affected field invalid instead.
                        hadNonFiniteNumbers = true
                        return JSONObject.NULL
                    }
                    return value
                }
            }
            val root = tokener.nextValue() as? JSONObject
                ?: return SessionHistory(hadUnreadableEntries = true)
            if (tokener.nextClean() != '\u0000' || root.exactInt("version") != VERSION) {
                return SessionHistory(hadUnreadableEntries = true)
            }
            val entries = root.opt("sessions") as? JSONArray
                ?: return SessionHistory(hadUnreadableEntries = true)
            val retained = mutableListOf<SessionHistoryEntry>()
            val seen = mutableSetOf<String>()
            var recovered = hadNonFiniteNumbers
            for (index in 0 until entries.length()) {
                val saved = entries.opt(index) as? JSONObject
                if (saved == null) {
                    recovered = true
                    continue
                }
                val entry = saved.readEntry()
                if (entry == null) {
                    recovered = true
                    continue
                }
                if (!saved.hasCanonicalDerivedFields(entry)) recovered = true
                if (!seen.add(entry.sessionId)) {
                    recovered = true
                    continue
                }
                if (retained.size < MAX_SESSIONS) retained += entry
            }
            SessionHistory(retained, recovered)
        } catch (_: JSONException) {
            SessionHistory(hadUnreadableEntries = true)
        } catch (_: IllegalArgumentException) {
            SessionHistory(hadUnreadableEntries = true)
        }
    }

    fun encode(sessions: List<SessionHistoryEntry>): String {
        val entries = JSONArray()
        canonicalRetainedSessions(sessions).forEach { entry ->
            val scores = JSONArray()
            entry.roundScoresMs.forEach { scores.put(it) }
            entries.put(JSONObject().apply {
                put("sessionId", entry.sessionId)
                put("mode", entry.mode.name)
                put("completedAtMillis", entry.completedAtMillis)
                put("roundScoresMs", scores)
                put("bestTimeMs", entry.bestTimeMs)
                put("averageTimeMs", entry.averageTimeMs)
                put("spreadMs", entry.spreadMs)
                put("falseStartCount", entry.falseStartCount)
                put("xpEarned", entry.xpEarned)
                put("medal", entry.medal.name)
            })
        }
        return JSONObject().put("version", VERSION).put("sessions", entries).toString().also {
            require(it.length <= MAX_RAW_CHARS) { "Session history exceeds its storage bound." }
        }
    }

    fun prepend(history: SessionHistory, entry: SessionHistoryEntry): SessionHistory =
        // A canonical rewrite no longer requires payload recovery; no warning flag is persisted.
        SessionHistory(canonicalRetainedSessions(listOf(entry) + history.sessions))

    private fun JSONObject.readEntry(): SessionHistoryEntry? {
        val id = opt("sessionId") as? String ?: return null
        val modeName = opt("mode") as? String ?: return null
        val mode = GameMode.entries.firstOrNull { it.name == modeName } ?: return null
        val timestamp = exactLong("completedAtMillis") ?: return null
        val falseStarts = exactInt("falseStartCount") ?: return null
        val xp = exactInt("xpEarned") ?: return null
        val savedScores = opt("roundScoresMs") as? JSONArray ?: return null
        if (savedScores.length() != mode.rounds) return null
        val scores = (0 until savedScores.length()).map { index ->
            exactIntValue(savedScores.opt(index)) ?: return null
        }
        return try {
            canonicalEntry(SessionHistoryEntry(id, mode, timestamp, scores, 0, 0, 0, falseStarts, xp, PerformanceMedal.UNRANKED))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JSONObject.hasCanonicalDerivedFields(entry: SessionHistoryEntry): Boolean =
        exactInt("bestTimeMs") == entry.bestTimeMs &&
            exactInt("averageTimeMs") == entry.averageTimeMs &&
            exactInt("spreadMs") == entry.spreadMs &&
            opt("medal") == entry.medal.name

    private fun JSONObject.exactInt(key: String): Int? = exactIntValue(opt(key))

    private fun JSONObject.exactLong(key: String): Long? = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun exactIntValue(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }

    private fun isBoundedStrictObject(raw: String): Boolean = try {
        // JSONTokener accepts nonstandard syntax. Validate tokens iteratively before its
        // recursive parser so comments and lenient strings cannot hide excessive depth.
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return@use false
            var depth = 0
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        if (depth == 16) return@use false
                        reader.beginObject()
                        depth++
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        if (depth == 16) return@use false
                        reader.beginArray()
                        depth++
                    }
                    JsonToken.END_OBJECT -> {
                        reader.endObject()
                        depth--
                    }
                    JsonToken.END_ARRAY -> {
                        reader.endArray()
                        depth--
                    }
                    JsonToken.NAME -> reader.nextName()
                    JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    JsonToken.END_DOCUMENT -> Unit
                }
            }
            depth == 0
        }
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun canonicalRetainedSessions(sessions: List<SessionHistoryEntry>): List<SessionHistoryEntry> {
        val retained = mutableListOf<SessionHistoryEntry>()
        val seen = mutableSetOf<String>()
        for (entry in sessions) {
            if (retained.size == MAX_SESSIONS) break
            if (seen.add(entry.sessionId)) retained += canonicalEntry(entry)
        }
        return retained
    }

    private fun canonicalEntry(entry: SessionHistoryEntry): SessionHistoryEntry {
        require(entry.sessionId.isNotBlank() && entry.sessionId.length <= MAX_ID_CHARS) { "Invalid session ID." }
        require(entry.completedAtMillis > 0L) { "Session completion time must be positive." }
        require(entry.falseStartCount >= 0) { "False-start count must not be negative." }
        val score = PlayerProgression.sessionScore(entry.mode, entry.roundScoresMs)
        require(entry.xpEarned in 0..PlayerProgression.reward(entry.mode, entry.roundScoresMs).totalXp) {
            "Credited XP must be within the session's reward."
        }
        val best = checkNotNull(entry.roundScoresMs.minOrNull())
        val spread = checkNotNull(entry.roundScoresMs.maxOrNull()).toLong() - best
        return entry.copy(
            roundScoresMs = entry.roundScoresMs.toList(),
            bestTimeMs = best,
            averageTimeMs = score,
            spreadMs = spread.toInt(),
            medal = PlayerProgression.medal(score)
        )
    }
}

data class PerformanceTrend(val recentAverageMs: Int, val previousAverageMs: Int) {
    val improvementMs: Int get() = previousAverageMs - recentAverageMs
}

data class ModeHistoryStatistics(
    val sessionCount: Int,
    val reactionCount: Int,
    val averageReactionMs: Int?,
    val trend: PerformanceTrend?
)

data class HistoryStatistics(
    val sessionCount: Int,
    val reactionCount: Int,
    val averageReactionMs: Int?,
    val byMode: Map<GameMode, ModeHistoryStatistics>
)

object PlayerStatistics {
    fun fromHistory(sessions: List<SessionHistoryEntry>): HistoryStatistics {
        val retained = sessions.asSequence().distinctBy { it.sessionId }
            .take(SessionHistoryCodec.MAX_SESSIONS).toList()
        retained.forEach { PlayerProgression.sessionScore(it.mode, it.roundScoresMs) }
        return HistoryStatistics(
            sessionCount = retained.size,
            reactionCount = retained.sumOf { it.roundScoresMs.size },
            averageReactionMs = average(retained),
            byMode = GameMode.entries.associateWith { mode ->
                val modeSessions = retained.filter { it.mode == mode }
                ModeHistoryStatistics(
                    sessionCount = modeSessions.size,
                    reactionCount = modeSessions.sumOf { it.roundScoresMs.size },
                    averageReactionMs = average(modeSessions),
                    trend = if (modeSessions.size >= 10) PerformanceTrend(
                        recentAverageMs = checkNotNull(average(modeSessions.take(5))),
                        previousAverageMs = checkNotNull(average(modeSessions.drop(5).take(5)))
                    ) else null
                )
            }
        )
    }

    private fun average(sessions: List<SessionHistoryEntry>): Int? {
        var sum = 0L
        var count = 0
        sessions.forEach { session ->
            session.roundScoresMs.forEach { score ->
                sum += score
                count++
            }
        }
        return if (count == 0) null else (sum.toDouble() / count).roundToInt()
    }
}
