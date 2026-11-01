package com.maurointeractive.reactionrush

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun AchievementsScreen(records: PersonalRecords, onBackHome: () -> Unit) {
    BackHandler(onBack = onBackHome)
    val state = records.achievements
    val level = PlayerProgression.levelProgress(records.totalXp).level

    Surface(modifier = Modifier.fillMaxSize(), color = RushColors.Background) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = RushSpace.Xl)) {
            ScreenHeader(title = "ACHIEVEMENTS", onBack = onBackHome)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RushSpace.Md)
            ) {
                RushPanel(accent = RushColors.Gold) {
                    SectionLabel("YOUR COLLECTION")
                    Text(
                        "${state.unlocked.size} / ${Achievement.entries.size} UNLOCKED",
                        color = RushColors.Text,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black
                    )
                    AchievementProgressBar(state.unlocked.size.toFloat() / Achievement.entries.size)
                    Text(
                        "COMPLETED SESSIONS: ${state.completedSessions}\n" +
                            "CURRENT CLEAN STREAK: ${state.cleanSessionStreak}",
                        color = RushColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(RushSpace.Sm))
                SectionLabel("MILESTONES & MASTERY")
                Achievement.entries.forEach { achievement ->
                    val unlocked = achievement in state.unlocked
                    val progress = Achievements.progress(achievement, state, level)
                    RushPanel(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                            stateDescription = if (unlocked) "Unlocked" else "Locked"
                        },
                        accent = if (unlocked) RushColors.Gold else null
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseMark(
                                modifier = Modifier.size(30.dp),
                                accent = if (unlocked) RushColors.Gold else RushColors.Subtle
                            )
                            Spacer(Modifier.width(RushSpace.Md))
                            Text(
                                achievement.title,
                                modifier = Modifier.weight(1f),
                                color = RushColors.Text,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        AchievementStatus(unlocked)
                        Text(
                            achievement.description,
                            color = RushColors.Muted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (progress != null) {
                            Text(
                                "Progress: ${progress.current} / ${progress.target}",
                                color = if (unlocked) RushColors.Gold else RushColors.Muted,
                                style = MaterialTheme.typography.labelMedium
                            )
                            AchievementProgressBar(
                                (progress.current.toDouble() / progress.target).toFloat()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(RushSpace.Xl))
            }
        }
    }
}

@Composable
private fun AchievementStatus(unlocked: Boolean) {
    val accent = if (unlocked) RushColors.Gold else RushColors.Muted
    Surface(
        color = accent.copy(alpha = 0.09f),
        shape = RushShapes.Pill,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Text(
            if (unlocked) "UNLOCKED" else "LOCKED",
            modifier = Modifier.padding(horizontal = RushSpace.Md, vertical = RushSpace.Xs),
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AchievementProgressBar(fraction: Float) {
    Box(
        modifier = Modifier.fillMaxWidth().height(5.dp)
            .background(RushColors.SurfaceRaised, RushShapes.Pill)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp)
                .background(RushColors.Gold, RushShapes.Pill)
        )
    }
}

@Composable
internal fun AchievementUnlockFeedback(unlocked: Set<Achievement>) {
    RushPanel(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        accent = RushColors.Gold
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RushSpace.Sm)
        ) {
            Text(
                if (unlocked.size == 1) "ACHIEVEMENT UNLOCKED!"
                else "${unlocked.size} ACHIEVEMENTS UNLOCKED!",
                color = RushColors.Gold,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            if (unlocked.size == 1) {
                Text(
                    unlocked.single().title,
                    color = RushColors.Text,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun NewAchievementsCard(unlocked: Set<Achievement>) {
    RushPanel(modifier = Modifier.fillMaxWidth(), accent = RushColors.Gold) {
        Text(
            "UNLOCKED THIS SESSION",
            color = RushColors.Gold,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Achievement.entries.filter { it in unlocked }.forEach {
            Text(it.title, color = RushColors.Text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
