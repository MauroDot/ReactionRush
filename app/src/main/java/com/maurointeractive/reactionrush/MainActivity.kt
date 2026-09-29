
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

enum class AppScreen {
    HOME,
    GAME
}

enum class GameState {
    READY,
    WAITING,
    GO,
    ROUND_RESULT,
    FALSE_START,
    SESSION_RESULT
}

enum class GameMode(
    val title: String,
    val rounds: Int,
    val description: String
) {
    QUICK_DRAW(
        "QUICK DRAW",
        1,
        "One shot. One reaction. Chase your fastest time."
    ),
    CLASSIC_RUSH(
        "CLASSIC RUSH",
        5,
        "Five rounds. Balance raw speed with consistency."
    ),
    ENDURANCE_RUSH(
        "ENDURANCE RUSH",
        10,
        "Ten rounds. Stay sharp and protect your average."
    )
}

@Composable
fun ReactionRushApp() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
    }

    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var selectedMode by remember { mutableStateOf(GameMode.CLASSIC_RUSH) }

    val best = prefs.getInt("best", 0)
    val attempts = prefs.getInt("attempts", 0)

    when (screen) {
        AppScreen.HOME -> {
            HomeScreen(
                best = best,
                attempts = attempts,
                onModeSelected = { mode ->
                    selectedMode = mode
                    screen = AppScreen.GAME
                }
            )
        }

        AppScreen.GAME -> {
            GameScreen(
                mode = selectedMode,
                onBackHome = {
                    screen = AppScreen.HOME
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    best: Int,
    attempts: Int,
    onModeSelected: (GameMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0B1020)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(30.dp))

            Text(
                "REACTION RUSH",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black
            )

            Text(
                "Mauro Interactive",
                color = Color(0xFFAAB4D0),
                fontSize = 14.sp
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "TEST YOUR REFLEXES",
                color = Color(0xFF62E6B5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    "OVERALL BEST",
                    if (best == 0) "—" else "$best ms",
                    Modifier.weight(1f)
                )

                StatCard(
                    "REACTIONS",
                    attempts.toString(),
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "SELECT MODE",
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF8D98B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            ModeCard(
                mode = GameMode.QUICK_DRAW,
                accent = Color(0xFF4DA3FF),
                onClick = { onModeSelected(GameMode.QUICK_DRAW) }
            )

            Spacer(Modifier.height(12.dp))

            ModeCard(
                mode = GameMode.CLASSIC_RUSH,
                accent = Color(0xFF62E6B5),
                onClick = { onModeSelected(GameMode.CLASSIC_RUSH) }
            )

            Spacer(Modifier.height(12.dp))

            ModeCard(
                mode = GameMode.ENDURANCE_RUSH,
                accent = Color(0xFFFFB454),
                onClick = { onModeSelected(GameMode.ENDURANCE_RUSH) }
            )

            Spacer(Modifier.weight(1f))

            Text(
                "More modes and challenges coming soon.",
                color = Color(0xFF697491),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ModeCard(
    mode: GameMode,
    accent: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF151D35)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accent, RoundedCornerShape(10.dp))
            )

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    mode.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    mode.description,
                    color = Color(0xFFAAB4D0),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                "${mode.rounds}",
                color = accent,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun GameScreen(
    mode: GameMode,
    onBackHome: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
    }

    var state by remember(mode) { mutableStateOf(GameState.READY) }
    var roundId by remember(mode) { mutableIntStateOf(0) }
    var currentRound by remember(mode) { mutableIntStateOf(1) }
    var goTime by remember(mode) { mutableLongStateOf(0L) }
    var lastScore by remember(mode) { mutableIntStateOf(0) }

    var best by remember(mode) {
        mutableIntStateOf(prefs.getInt("best", 0))
    }

    var attempts by remember(mode) {
        mutableIntStateOf(prefs.getInt("attempts", 0))
    }

    var sessionScores by remember(mode) {
        mutableStateOf(emptyList<Int>())
    }

    var falseStarts by remember(mode) {
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
        state = GameState.READY
    }

    fun startSession() {
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

    val sessionBest = sessionScores.minOrNull() ?: 0

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
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹ HOME",
                    modifier = Modifier.clickable {
                        onBackHome()
                    },
                    color = Color(0xFFAAB4D0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.weight(1f))

                Text(
                    mode.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.height(18.dp))

            if (
                state != GameState.READY &&
                state != GameState.SESSION_RESULT
            ) {
                Text(
                    "ROUND $currentRound / ${mode.rounds}",
                    color = Color(0xFF8D98B8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))
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

            Spacer(Modifier.height(20.dp))

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
                                startSession()
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
                                sessionScores = sessionScores + score

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
                                if (currentRound >= mode.rounds) {
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
                    modifier = Modifier.padding(26.dp)
                ) {
                    val title = when (state) {
                        GameState.READY -> mode.title
                        GameState.WAITING -> "WAIT…"
                        GameState.GO -> "TAP!"
                        GameState.ROUND_RESULT -> "$lastScore ms"
                        GameState.FALSE_START -> "TOO SOON!"
                        GameState.SESSION_RESULT ->
                            if (mode == GameMode.QUICK_DRAW)
                                "DRAW COMPLETE"
                            else
                                "RUSH COMPLETE"
                    }

                    val subtitle = when (state) {
                        GameState.READY ->
                            "${mode.description}\n\nTap to begin."

                        GameState.WAITING ->
                            "Don't tap until it turns green."

                        GameState.GO ->
                            "NOW!"

                        GameState.ROUND_RESULT ->
                            if (currentRound == mode.rounds)
                                "Round complete. Tap for results."
                            else
                                "Round $currentRound complete. Tap for the next round."

                        GameState.FALSE_START ->
                            "False start! This round doesn't count.\nTap to retry."

                        GameState.SESSION_RESULT ->
                            "Tap to play this mode again."
                    }

                    Text(
                        title,
                        color = Color.White,
                        fontSize =
                            if (
                                state == GameState.ROUND_RESULT ||
                                state == GameState.SESSION_RESULT
                            ) 40.sp else 34.sp,
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
                        Spacer(Modifier.height(26.dp))

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

                        Spacer(Modifier.height(10.dp))

                        MiniResult(
                            "FALSE STARTS",
                            falseStarts.toString(),
                            Modifier.fillMaxWidth()
                        )

                        if (mode.rounds > 1) {
                            Spacer(Modifier.height(20.dp))

                            sessionScores.forEachIndexed { index, score ->
                                Text(
                                    "Round ${index + 1}: $score ms",
                                    color = Color(0xFFAAB4D0),
                                    fontSize =
                                        if (mode.rounds >= 10) 12.sp
                                        else 14.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                when (state) {
                    GameState.READY ->
                        "${mode.title} • ${mode.rounds} ${if (mode.rounds == 1) "round" else "rounds"}"

                    GameState.SESSION_RESULT ->
                        "Tap HOME to choose another challenge."

                    else ->
                        "Stay ready. Don't anticipate the signal."
                },
                color = Color(0xFF8D98B8),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = Color(0xFF8D98B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = value,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
