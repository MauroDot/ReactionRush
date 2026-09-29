package com.maurointeractive.reactionrush

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReactionRushApp() }
    }
}

enum class GameState { READY, WAITING, GO, RESULT, FALSE_START }

@Composable
fun ReactionRushApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE) }
    var state by remember { mutableStateOf(GameState.READY) }
    var roundId by remember { mutableIntStateOf(0) }
    var goTime by remember { mutableLongStateOf(0L) }
    var lastScore by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best", 0)) }
    var attempts by remember { mutableIntStateOf(prefs.getInt("attempts", 0)) }

    fun buzz(ms: Long) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    LaunchedEffect(roundId) {
        if (state == GameState.WAITING) {
            delay(Random.nextLong(1500, 4501))
            if (state == GameState.WAITING) {
                goTime = SystemClock.elapsedRealtime()
                state = GameState.GO
                buzz(35)
            }
        }
    }

    val bg = when (state) {
        GameState.GO -> Color(0xFF0D7A55)
        GameState.FALSE_START -> Color(0xFF8F2735)
        else -> Color(0xFF0B1020)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = bg) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            Text("REACTION RUSH", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Mauro Interactive", color = Color(0xFFAAB4D0), fontSize = 14.sp)
            Spacer(Modifier.height(28.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("BEST", if (best == 0) "—" else "${best} ms", Modifier.weight(1f))
                StatCard("ATTEMPTS", attempts.toString(), Modifier.weight(1f))
            }

            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(28.dp)).clickable {
                    when (state) {
                        GameState.READY, GameState.RESULT, GameState.FALSE_START -> {
                            state = GameState.WAITING
                            roundId++
                        }
                        GameState.WAITING -> {
                            state = GameState.FALSE_START
                            buzz(90)
                        }
                        GameState.GO -> {
                            val score = (SystemClock.elapsedRealtime() - goTime).toInt()
                            lastScore = score
                            attempts++
                            if (best == 0 || score < best) best = score
                            prefs.edit().putInt("best", best).putInt("attempts", attempts).apply()
                            state = GameState.RESULT
                            buzz(45)
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                    val title = when (state) {
                        GameState.READY -> "TAP TO START"
                        GameState.WAITING -> "WAIT…"
                        GameState.GO -> "TAP!"
                        GameState.RESULT -> "$lastScore ms"
                        GameState.FALSE_START -> "TOO SOON!"
                    }
                    val subtitle = when (state) {
                        GameState.READY -> "When the screen turns green, tap as fast as you can."
                        GameState.WAITING -> "Don't tap until it turns green."
                        GameState.GO -> "NOW!"
                        GameState.RESULT -> if (lastScore < 200) "Lightning fast! Tap to go again." else if (lastScore < 300) "Nice reflexes! Tap to go again." else "Tap to try again."
                        GameState.FALSE_START -> "False start. Tap to retry."
                    }
                    Text(title, color = Color.White, fontSize = if (state == GameState.RESULT) 46.sp else 38.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Text(subtitle, color = Color(0xFFD6DCF0), fontSize = 18.sp, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Tip: hold the phone naturally and use the same finger each round.", color = Color(0xFF8D98B8), fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF151D35)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color(0xFF8D98B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}
