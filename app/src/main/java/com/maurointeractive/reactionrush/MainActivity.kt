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
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReactionRushApp() }
    }
}

enum class GameState {
    READY,
    WAITING,
    GO,
    ROUND_RESULT,
    FALSE_START,
    SESSION_RESULT
}

private const val TOTAL_ROUNDS = 5

@Composable
fun ReactionRushApp() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
    }

    var state by remember { mutableStateOf(GameState.READY) }
    var roundId by remember { mutableIntStateOf(0) }
    var currentRound by remember { mutableIntStateOf(1) }
    var goTime by remember { mutableLongStateOf(0L) }
    var lastScore by remember { mutableIntStateOf(0) }

    var best by remember {
        mutableIntStateOf(prefs.getInt("best", 0))
    }

    var attempts by remember {
        mutableIntStateOf(prefs.getInt("attempts", 0))
    }

    var sessionScores by remember {
        mutableStateOf(emptyList<Int>())
    }

    var falseStarts by remember {
        mutableIntStateOf(0)
    }

    fun buzz(ms: Long) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createOneShot(
                ms,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    }

    fun beginRound() {
        state = GameState.WAITING
        roundId++
    }

    fun resetSession() {
        currentRound = 1
        lastScore = 0
        sessionScores = emptyList()
        falseStarts = 0
        beginRound()
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

    val averageScore =
        if (sessionScores.isEmpty()) 0
        else sessionScores.average().roundToInt()

    val sessionBest =
        sessionScores.minOrNull() ?: 0

    val bg = when (state) {
        GameState.GO -> Color(0xFF0D7A55)
        GameState.FALSE_START -> Color(0xFF8F2735)
        GameState.SESSION_RESULT -> Color(0xFF10172A)
        else -> Color(0xFF0B1020)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(Modifier.height(24.dp))

            Text(
                "REACTION RUSH",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )

            Text(
                "Mauro Interactive",
                color = Color(0xFFAAB4D0),
                fontSize = 14.sp
            )

            Spacer(Modifier.height(16.dp))

            if (state != GameState.READY &&
                state != GameState.SESSION_RESULT
            ) {
                Text(
                    "ROUND $currentRound / $TOTAL_ROUNDS",
                    color = Color(0xFF8D98B8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(14.dp))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    "BEST",
                    if (best == 0) "—" else "$best ms",
                    Modifier.weight(1f)
                )

                StatCard(
                    "ATTEMPTS",
                    attempts.toString(),
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(22.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        Color.White.copy(alpha = 0.07f),
                        RoundedCornerShape(28.dp)
                    )
                    .clickable {
                        when (state) {

                            GameState.READY -> {
                                resetSession()
                            }

                            GameState.WAITING -> {
                                falseStarts++
                                state = GameState.FALSE_START
                                buzz(90)
                            }

                            GameState.GO -> {
                                val score =
                                    (SystemClock.elapsedRealtime() - goTime)
                                        .toInt()

                                lastScore = score
                                attempts++

                                sessionScores =
                                    sessionScores + score

                                if (best == 0 || score < best) {
                                    best = score
                                }

                                prefs.edit()
                                    .putInt("best", best)
                                    .putInt("attempts", attempts)
                                    .apply()

                                state = GameState.ROUND_RESULT
                                buzz(45)
                            }

                            GameState.ROUND_RESULT -> {
                                if (currentRound >= TOTAL_ROUNDS) {
                                    state = GameState.SESSION_RESULT
                                } else {
                                    currentRound++
                                    beginRound()
                                }
                            }

                            GameState.FALSE_START -> {
                                beginRound()
                            }

                            GameState.SESSION_RESULT -> {
                                resetSession()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(28.dp)
                ) {

                    val title = when (state) {
                        GameState.READY ->
                            "CLASSIC RUSH"

                        GameState.WAITING ->
                            "WAIT…"

                        GameState.GO ->
                            "TAP!"

                        GameState.ROUND_RESULT ->
                            "$lastScore ms"

                        GameState.FALSE_START ->
                            "TOO SOON!"

                        GameState.SESSION_RESULT ->
                            "RUSH COMPLETE"
                    }

                    val subtitle = when (state) {
                        GameState.READY ->
                            "Five rounds. Wait for green, then react as fast as you can.\n\nTap to begin."

                        GameState.WAITING ->
                            "Don't tap until it turns green."

                        GameState.GO ->
                            "NOW!"

                        GameState.ROUND_RESULT ->
                            if (currentRound == TOTAL_ROUNDS)
                                "Round $currentRound complete. Tap for results."
                            else
                                "Round $currentRound complete. Tap for the next round."

                        GameState.FALSE_START ->
                            "False start! This round doesn't count.\nTap to retry."

                        GameState.SESSION_RESULT ->
                            "Tap to play another five rounds."
                    }

                    Text(
                        title,
                        color = Color.White,
                        fontSize =
                            if (
                                state == GameState.ROUND_RESULT ||
                                state == GameState.SESSION_RESULT
                            ) 40.sp else 36.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        subtitle,
                        color = Color(0xFFD6DCF0),
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center
                    )

                    if (state == GameState.SESSION_RESULT) {

                        Spacer(Modifier.height(30.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            MiniResult(
                                "BEST",
                                "$sessionBest ms",
                                Modifier.weight(1f)
                            )

                            MiniResult(
                                "AVERAGE",
                                "$averageScore ms",
                                Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        MiniResult(
                            "FALSE STARTS",
                            falseStarts.toString(),
                            Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(24.dp))

                        sessionScores.forEachIndexed { index, score ->
                            Text(
                                "Round ${index + 1}: $score ms",
                                color = Color(0xFFAAB4D0),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                when (state) {
                    GameState.READY ->
                        "Classic Rush • 5 rounds"

                    GameState.SESSION_RESULT ->
                        "Consistency matters as much as raw speed."

                    else ->
                        "Stay ready. Don't anticipate the signal."
                },
                color = Color(0xFF8D98B8),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF151D35)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                color = Color(0xFF8D98B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                value,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MiniResult(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.06f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                color = Color(0xFF8D98B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                value,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
