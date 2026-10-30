package com.maurointeractive.reactionrush

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AchievementsScreen(records: PersonalRecords, onBackHome: () -> Unit) {
    BackHandler(onBack = onBackHome)
    val state = records.achievements
    val level = PlayerProgression.levelProgress(records.totalXp).level

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1020)) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBackHome) {
                    Text("‹ HOME", color = Color(0xFFAAB4D0), fontWeight = FontWeight.Bold)
                }
                Text(
                    "ACHIEVEMENTS",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "${state.unlocked.size} / ${Achievement.entries.size} UNLOCKED",
                    color = Color(0xFF62E6B5),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "COMPLETED SESSIONS: ${state.completedSessions}\n" +
                        "CURRENT CLEAN STREAK: ${state.cleanSessionStreak}",
                    color = Color(0xFFAAB4D0),
                    fontSize = 13.sp
                )

                Achievement.entries.forEach { achievement ->
                    val unlocked = achievement in state.unlocked
                    val progress = Achievements.progress(achievement, state, level)
                    Card(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                            stateDescription = if (unlocked) "Unlocked" else "Locked"
                        },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D35)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                achievement.title,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (unlocked) "UNLOCKED" else "LOCKED",
                                color = if (unlocked) Color(0xFF62E6B5) else Color(0xFFAAB4D0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(achievement.description, color = Color(0xFFD6DCF0), fontSize = 13.sp)
                            if (progress != null) {
                                Text(
                                    "Progress: ${progress.current} / ${progress.target}",
                                    color = Color(0xFFAAB4D0),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
internal fun AchievementUnlockFeedback(unlocked: Set<Achievement>) {
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (unlocked.size == 1) "ACHIEVEMENT UNLOCKED!"
            else "${unlocked.size} ACHIEVEMENTS UNLOCKED!",
            color = Color(0xFFFFD166),
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        if (unlocked.size == 1) {
            Text(
                unlocked.single().title,
                color = Color(0xFFD6DCF0),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun NewAchievementsCard(unlocked: Set<Achievement>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D35)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("UNLOCKED THIS SESSION", color = Color(0xFFFFD166), fontWeight = FontWeight.Bold)
            Achievement.entries.filter { it in unlocked }.forEach {
                Text(it.title, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
