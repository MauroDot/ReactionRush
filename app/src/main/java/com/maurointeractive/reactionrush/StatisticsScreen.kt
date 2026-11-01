package com.maurointeractive.reactionrush

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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

    Surface(modifier = Modifier.fillMaxSize(), color = RushColors.Background) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = RushSpace.Xl)) {
            ScreenHeader(
                title = if (selected != null) "SESSION DETAILS" else "STATISTICS",
                backLabel = if (selected != null) "‹ STATISTICS" else "‹ HOME",
                onBack = { back() }
            )

            if (selected != null) {
                key(selected.sessionId) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(RushSpace.Md)
                    ) {
                        SessionDetails(selected)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(overviewScroll),
                    verticalArrangement = Arrangement.spacedBy(RushSpace.Md)
                ) {
                    StatisticsHeading("SAVED PLAYER TOTALS")
                    StatisticsNote(
                        "Saved totals include older sessions beyond the retained history. " +
                            "Successful reactions also include unfinished sessions."
                    )
                    StatisticsPanel(accent = RushColors.Cyan) {
                        StatisticsValue("Total completed sessions", records.achievements.completedSessions.toString())
                        StatisticsValue("Lifetime successful reactions", records.lifetimeReactions.toString())
                        StatisticsValue("Global best reaction", reactionTime(records.lifetimeBestMs.takeIf { it > 0 }))
                        HorizontalDivider(color = RushColors.Border)
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
                    GameMode.entries.forEach { mode ->
                        StatisticsPanel(accent = modeAccent(mode)) {
                            StatisticsValue("${mode.title} completed", (records.completedByMode[mode] ?: 0L).toString())
                            StatisticsValue(
                                "${mode.title} personal record (${mode.recordLabel.lowercase(Locale.ROOT)})",
                                reactionTime(records.bestFor(mode).takeIf { it > 0 })
                            )
                        }
                    }

                    StatisticsHeading("RETAINED HISTORY METRICS")
                    StatisticsNote("Only the most recent ${SessionHistoryCodec.MAX_SESSIONS} completed sessions are retained.")
                    StatisticsPanel(accent = RushColors.Mint) {
                        StatisticsValue("Retained sessions", statistics.sessionCount.toString())
                        StatisticsValue("Retained reactions", statistics.reactionCount.toString())
                        HorizontalDivider(color = RushColors.Border)
                        StatisticsValue("Average of retained reactions", reactionTime(statistics.averageReactionMs))
                    }
                    StatisticsNote("The average includes each valid reaction equally; it excludes abandoned sessions and false starts.")
                    if (records.history.hadUnreadableEntries) {
                        RushPanel(accent = RushColors.Warning) {
                            Text(
                                "Some saved history needed recovery. Showing the sessions we could recover.",
                                color = RushColors.Warning,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    StatisticsHeading("RECENT PERFORMANCE BY MODE")
                    StatisticsNote("Latest five versus previous five sessions in each mode, from retained history only. Lower is faster.")
                    GameMode.entries.forEach { mode ->
                        val modeStatistics = checkNotNull(statistics.byMode[mode])
                        val trend = modeStatistics.trend
                        StatisticsPanel(accent = modeAccent(mode)) {
                            Text(
                                mode.title,
                                color = RushColors.Text,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (trend == null) {
                                StatisticsNote("Complete 10 retained sessions in this mode to compare performance (${modeStatistics.sessionCount} / 10).")
                                StatisticsBar(modeStatistics.sessionCount / 10f, modeAccent(mode))
                            } else {
                                val largestAverage = maxOf(trend.recentAverageMs, trend.previousAverageMs).toFloat()
                                StatisticsValue("Recent 5 average", reactionTime(trend.recentAverageMs))
                                StatisticsBar(trend.recentAverageMs / largestAverage, modeAccent(mode))
                                StatisticsValue("Previous 5 average", reactionTime(trend.previousAverageMs))
                                StatisticsBar(trend.previousAverageMs / largestAverage, RushColors.Subtle)
                                TrendStatus(trend)
                            }
                        }
                    }

                    StatisticsHeading("SESSION HISTORY (NEWEST FIRST)")
                    StatisticsNote("Ordered by completion. Dates use this device's time zone; changing its clock does not reorder history.")
                    if (records.history.sessions.isEmpty()) {
                        RushPanel(accent = RushColors.Cyan) {
                            PulseMark(modifier = Modifier.size(32.dp), accent = RushColors.Cyan)
                            Text(
                                "No completed sessions in history yet.",
                                color = RushColors.Text,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            StatisticsNote("Finish any mode to record a session. Older sessions have no saved details.")
                        }
                    } else {
                        records.history.sessions.forEach { entry ->
                            RushPanel(
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("session-${entry.sessionId}")
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "View session details"
                                    ) { selectedSessionId = entry.sessionId },
                                accent = modeAccent(entry.mode)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PulseMark(modifier = Modifier.size(24.dp), accent = modeAccent(entry.mode))
                                    Spacer(Modifier.width(RushSpace.Md))
                                    Text(
                                        entry.mode.title,
                                        modifier = Modifier.weight(1f),
                                        color = RushColors.Text,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                StatisticsNote(completionTime(entry.completedAtMillis))
                                Text(
                                    "Average: ${entry.averageTimeMs} ms · ${entry.medal.title}",
                                    color = RushColors.Text,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "View session details",
                                    color = RushColors.Mint,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(RushSpace.Xl))
                }
            }
        }
    }
}

@Composable
private fun SessionDetails(entry: SessionHistoryEntry) {
    StatisticsPanel(accent = modeAccent(entry.mode)) {
        SectionLabel("SESSION AVERAGE")
        Text(
            "${entry.averageTimeMs} ms",
            color = RushColors.Text,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black
        )
        MedalBadge(entry.medal)
        HorizontalDivider(color = RushColors.Border)
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
            Surface(color = RushColors.SurfaceRaised, shape = RushShapes.Small) {
                Text(
                    "Round ${index + 1}: $score ms",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .padding(horizontal = RushSpace.Md, vertical = RushSpace.Md),
                    color = if (score == entry.bestTimeMs) RushColors.Mint else RushColors.Text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (score == entry.bestTimeMs) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
    Spacer(Modifier.height(RushSpace.Xl))
}

@Composable
private fun StatisticsPanel(accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    RushPanel(modifier = Modifier.fillMaxWidth(), accent = accent, content = content)
}

@Composable
private fun StatisticsHeading(text: String) {
    Spacer(Modifier.height(RushSpace.Md))
    SectionLabel(text)
}

@Composable
private fun StatisticsValue(label: String, value: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = RushColors.Muted)) { append("$label: ") }
            withStyle(SpanStyle(color = RushColors.Text, fontWeight = FontWeight.Bold)) { append(value) }
        },
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun StatisticsNote(text: String) {
    Text(text, color = RushColors.Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StatisticsBar(fraction: Float, accent: Color) {
    Box(
        modifier = Modifier.fillMaxWidth().height(RushSpace.Xs)
            .background(RushColors.SurfaceRaised, RushShapes.Pill)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(RushSpace.Xs)
                .background(accent, RushShapes.Pill)
        )
    }
}

@Composable
private fun TrendStatus(trend: PerformanceTrend) {
    val accent = when {
        trend.improvementMs > 0 -> RushColors.Mint
        trend.improvementMs < 0 -> RushColors.Warning
        else -> RushColors.Muted
    }
    Surface(
        color = accent.copy(alpha = 0.1f),
        shape = RushShapes.Pill,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
    ) {
        Text(
            when {
                trend.improvementMs > 0 -> "${trend.improvementMs} ms faster"
                trend.improvementMs < 0 -> "${-trend.improvementMs.toLong()} ms slower"
                else -> "Unchanged average"
            },
            modifier = Modifier.padding(horizontal = RushSpace.Md, vertical = RushSpace.Sm),
            color = accent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun reactionTime(value: Int?): String = value?.let { "$it ms" } ?: "—"

private fun completionTime(timestamp: Long): String = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
} catch (_: DateTimeException) {
    "Timestamp unavailable"
}
