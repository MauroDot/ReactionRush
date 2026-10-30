package com.maurointeractive.reactionrush

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun StatisticsScreen(records: PersonalRecords, onBackHome: () -> Unit) {
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    val selected = records.history.sessions.firstOrNull { it.sessionId == selectedSessionId }
    val overviewScroll = rememberScrollState()
    val statistics = remember(records.history.sessions) { PlayerStatistics.fromHistory(records.history.sessions) }

    fun back() {
        if (selectedSessionId != null) selectedSessionId = null else onBackHome()
    }
    BackHandler { back() }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1020)) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { back() }) {
                    Text(
                        if (selected != null) "‹ STATISTICS" else "‹ HOME",
                        color = Color(0xFFAAB4D0),
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    if (selected != null) "SESSION DETAILS" else "STATISTICS",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.End
                )
            }

            if (selected != null) {
                key(selected.sessionId) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SessionDetails(selected)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(overviewScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatisticsHeading("SAVED PLAYER TOTALS")
                    StatisticsNote(
                        "Saved totals include older sessions beyond the retained history. " +
                            "Successful reactions also include unfinished sessions."
                    )
                    StatisticsPanel {
                        StatisticsValue("Total completed sessions", records.achievements.completedSessions.toString())
                        StatisticsValue("Lifetime successful reactions", records.lifetimeReactions.toString())
                        StatisticsValue("Global best reaction", reactionTime(records.lifetimeBestMs.takeIf { it > 0 }))
                        StatisticsValue("Lifetime XP", records.totalXp.toString())
                        StatisticsValue("Current level", PlayerProgression.levelProgress(records.totalXp).level.toString())
                        StatisticsValue("Current clean-session streak", records.achievements.cleanSessionStreak.toString())
                        StatisticsValue("Achievements unlocked", "${records.achievements.unlocked.size} / ${Achievement.entries.size}")
                    }
                    StatisticsNote("Completed-session totals have been tracked since achievements were added.")

                    StatisticsHeading("MODE COMPLETIONS")
                    StatisticsNote(
                        "Mode totals include sessions completed since Statistics was added, " +
                            "including sessions no longer in history. Earlier mode totals are unavailable."
                    )
                    StatisticsPanel {
                        GameMode.entries.forEach { mode ->
                            StatisticsValue("${mode.title} completed", (records.completedByMode[mode] ?: 0L).toString())
                            StatisticsValue(
                                "${mode.title} personal record (${mode.recordLabel.lowercase(Locale.ROOT)})",
                                reactionTime(records.bestFor(mode).takeIf { it > 0 })
                            )
                        }
                    }

                    StatisticsHeading("RETAINED HISTORY METRICS")
                    StatisticsNote("Only the most recent ${SessionHistoryCodec.MAX_SESSIONS} completed sessions are retained.")
                    StatisticsPanel {
                        StatisticsValue("Retained sessions", statistics.sessionCount.toString())
                        StatisticsValue("Retained reactions", statistics.reactionCount.toString())
                        StatisticsValue("Average of retained reactions", reactionTime(statistics.averageReactionMs))
                    }
                    StatisticsNote("The average includes each valid reaction equally; it excludes abandoned sessions and false starts.")
                    if (records.history.hadUnreadableEntries) {
                        Text(
                            "Some saved history needed recovery. Showing the sessions we could recover.",
                            color = Color(0xFFFFD166),
                            fontSize = 14.sp
                        )
                    }

                    StatisticsHeading("RECENT PERFORMANCE BY MODE")
                    StatisticsNote("Latest five versus previous five sessions in each mode, from retained history only. Lower is faster.")
                    GameMode.entries.forEach { mode ->
                        val modeStatistics = checkNotNull(statistics.byMode[mode])
                        val trend = modeStatistics.trend
                        StatisticsPanel {
                            Text(mode.title, color = Color.White, fontWeight = FontWeight.Bold)
                            if (trend == null) {
                                StatisticsNote("Complete 10 retained sessions in this mode to compare performance (${modeStatistics.sessionCount} / 10).")
                            } else {
                                StatisticsValue("Recent 5 average", reactionTime(trend.recentAverageMs))
                                StatisticsValue("Previous 5 average", reactionTime(trend.previousAverageMs))
                                Text(
                                    when {
                                        trend.improvementMs > 0 -> "${trend.improvementMs} ms faster"
                                        trend.improvementMs < 0 -> "${-trend.improvementMs.toLong()} ms slower"
                                        else -> "Unchanged average"
                                    },
                                    color = Color(0xFF62E6B5),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    StatisticsHeading("SESSION HISTORY (NEWEST FIRST)")
                    StatisticsNote("Ordered by completion. Dates use this device's time zone; changing its clock does not reorder history.")
                    if (records.history.sessions.isEmpty()) {
                        Text("No completed sessions in history yet.", color = Color.White, fontSize = 16.sp)
                        StatisticsNote("Finish any mode to record a session. Older sessions have no saved details.")
                    } else {
                        records.history.sessions.forEach { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("session-${entry.sessionId}")
                                    .clickable { selectedSessionId = entry.sessionId },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF151D35)),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(entry.mode.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    StatisticsNote(completionTime(entry.completedAtMillis))
                                    Text("Average: ${entry.averageTimeMs} ms · ${entry.medal.title}", color = Color(0xFFD6DCF0), fontSize = 14.sp)
                                    Text("View session details", color = Color(0xFF62E6B5), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionDetails(entry: SessionHistoryEntry) {
    StatisticsPanel {
        StatisticsValue("Mode", entry.mode.title)
        StatisticsValue("Completed", completionTime(entry.completedAtMillis))
        StatisticsValue("Best", reactionTime(entry.bestTimeMs))
        StatisticsValue("Average", reactionTime(entry.averageTimeMs))
        StatisticsValue("Spread", reactionTime(entry.spreadMs))
        StatisticsValue("False starts", entry.falseStartCount.toString())
        StatisticsValue("XP earned", entry.xpEarned.toString())
        StatisticsValue("Medal", entry.medal.title)
    }
    StatisticsHeading("ROUND SCORES")
    StatisticsPanel {
        entry.roundScoresMs.forEachIndexed { index, score ->
            Text("Round ${index + 1}: $score ms", color = Color.White, fontSize = 15.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StatisticsPanel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D35)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { content() }
    }
}

@Composable
private fun StatisticsHeading(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, color = Color(0xFF62E6B5), fontSize = 17.sp, fontWeight = FontWeight.Black)
}

@Composable
private fun StatisticsValue(label: String, value: String) {
    Text("$label: $value", color = Color.White, fontSize = 14.sp)
}

@Composable
private fun StatisticsNote(text: String) {
    Text(text, color = Color(0xFFAAB4D0), fontSize = 13.sp)
}

private fun reactionTime(value: Int?): String = value?.let { "$it ms" } ?: "—"

private fun completionTime(timestamp: Long): String = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
} catch (_: DateTimeException) {
    "Timestamp unavailable"
}
