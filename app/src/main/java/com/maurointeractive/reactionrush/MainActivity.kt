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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val foregroundMonitor = ForegroundMonitor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReactionRushApp(foregroundMonitor) }
    }

    override fun onResume() {
        super.onResume()
        // Recreation can preserve an already focused window without another focus-change
        // callback. Refresh the new monitor instead of relying only on future changes.
        foregroundMonitor.setWindowFocused(hasWindowFocus())
        foregroundMonitor.setResumed(true)
    }

    override fun onPause() {
        foregroundMonitor.setResumed(false)
        super.onPause()
    }

    override fun onStop() {
        foregroundMonitor.setResumed(false)
        super.onStop()
    }

    override fun onDestroy() {
        foregroundMonitor.setResumed(false)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        foregroundMonitor.setWindowFocused(hasFocus)
        super.onWindowFocusChanged(hasFocus)
    }
}

enum class AppScreen {
    HOME,
    GAME,
    ACHIEVEMENTS,
    STATISTICS
}

enum class GameState {
    READY,
    WAITING,
    GO,
    ROUND_RESULT,
    FALSE_START,
    INTERRUPTED,
    SESSION_RESULT
}

@Composable
fun ReactionRushApp(foregroundMonitor: ForegroundMonitor) {
    val applicationContext = LocalContext.current.applicationContext
    var repository by remember { mutableStateOf<RecordsRepository?>(null) }
    var playerRecords by remember { mutableStateOf<PersonalRecords?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var hadUnfinishedSession by rememberSaveable { mutableStateOf(false) }
    var showInterruptedNotice by rememberSaveable { mutableStateOf(false) }

    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var selectedMode by remember { mutableStateOf(GameMode.CLASSIC_RUSH) }

    LaunchedEffect(applicationContext, loadAttempt) {
        loadFailed = false
        try {
            val loaded = withContext(Dispatchers.IO) {
                val prefs = applicationContext.getSharedPreferences("reaction_rush", Context.MODE_PRIVATE)
                val store = RecordsRepository(prefs)
                store to store.load()
            }
            // Preferences are hydrated now. This memory-only invalidation runs after
            // cancellation checks on Main, so an obsolete startup cannot clear a newer game.
            val orphanedSession = loaded.first.recoverInterruptedSession()
            showInterruptedNotice = showInterruptedNotice || hadUnfinishedSession || orphanedSession
            hadUnfinishedSession = false
            repository = loaded.first
            playerRecords = loaded.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    val recordsRepository = repository
    val records = playerRecords
    if (recordsRepository == null || records == null) {
        StartupScreen(loadFailed, onRetry = { loadAttempt++ })
        return
    }
    val saveStatus by recordsRepository.saveStatus.collectAsState()

    Column(Modifier.fillMaxSize().background(Color(0xFF0B1020))) {
        if (screen != AppScreen.GAME && saveStatus != SaveStatus.IDLE) {
            ProgressSaveNotice(saveStatus, recordsRepository::retrySave, Modifier.padding(16.dp))
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                AppScreen.HOME -> {
                    HomeScreen(
                        records = records,
                        interrupted = showInterruptedNotice,
                        onDismissInterruption = { showInterruptedNotice = false },
                        onAchievementsSelected = { screen = AppScreen.ACHIEVEMENTS },
                        onStatisticsSelected = { screen = AppScreen.STATISTICS },
                        onModeSelected = { mode ->
                            selectedMode = mode
                            screen = AppScreen.GAME
                        }
                    )
                }

                AppScreen.GAME -> {
                    GameScreen(
                        mode = selectedMode,
                        records = records,
                        foregroundMonitor = foregroundMonitor,
                        saveStatus = saveStatus,
                        onRetrySave = recordsRepository::retrySave,
                        onStartSession = { mode ->
                            hadUnfinishedSession = true
                            showInterruptedNotice = false
                            recordsRepository.startSession(mode)
                        },
                        onAbandonSession = recordsRepository::abandonSession,
                        onSessionInterrupted = { showInterruptedNotice = true },
                        onFalseStart = recordsRepository::recordFalseStart,
                        onReaction = { sessionId, mode, score, scores ->
                            val update = recordsRepository.recordReaction(sessionId, mode, score, scores)
                            playerRecords = update.records
                            if (update.reward != null) hadUnfinishedSession = false
                            update
                        },
                        onBackHome = {
                            hadUnfinishedSession = false
                            screen = AppScreen.HOME
                        }
                    )
                }

                AppScreen.ACHIEVEMENTS -> {
                    AchievementsScreen(records = records, onBackHome = { screen = AppScreen.HOME })
                }

                AppScreen.STATISTICS -> {
                    StatisticsScreen(records = records, onBackHome = { screen = AppScreen.HOME })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    records: PersonalRecords,
    interrupted: Boolean,
    onDismissInterruption: () -> Unit,
    onAchievementsSelected: () -> Unit,
    onStatisticsSelected: () -> Unit,
    onModeSelected: (GameMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0B1020)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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

            if (interrupted) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151D35))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "An unfinished session was interrupted. Choose a mode to start again.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = Color(0xFFFFD166),
                            fontSize = 14.sp
                        )
                        TextButton(onClick = onDismissInterruption) {
                            Text("DISMISS", color = Color(0xFFAAB4D0))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    "OVERALL BEST",
                    formatRecord(records.lifetimeBestMs),
                    Modifier.weight(1f)
                )

                StatCard(
                    "REACTIONS",
                    records.lifetimeReactions.toString(),
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(28.dp))

            PlayerProgressCard(records.totalXp)

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onAchievementsSelected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF62E6B5))
            ) {
                Text(
                    "ACHIEVEMENTS · ${records.achievements.unlocked.size} / ${Achievement.entries.size}",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onStatisticsSelected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF62E6B5))
            ) {
                Text("STATISTICS", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))

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
                best = records.bestFor(GameMode.QUICK_DRAW),
                accent = Color(0xFF4DA3FF),
                onClick = { onModeSelected(GameMode.QUICK_DRAW) }
            )

            Spacer(Modifier.height(12.dp))

            ModeCard(
                mode = GameMode.CLASSIC_RUSH,
                best = records.bestFor(GameMode.CLASSIC_RUSH),
                accent = Color(0xFF62E6B5),
                onClick = { onModeSelected(GameMode.CLASSIC_RUSH) }
            )

            Spacer(Modifier.height(12.dp))

            ModeCard(
                mode = GameMode.ENDURANCE_RUSH,
                best = records.bestFor(GameMode.ENDURANCE_RUSH),
                accent = Color(0xFFFFB454),
                onClick = { onModeSelected(GameMode.ENDURANCE_RUSH) }
            )

            Spacer(Modifier.height(28.dp))

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
    best: Int,
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

                Spacer(Modifier.height(8.dp))

                Text(
                    "${mode.recordLabel}: ${formatRecord(best)}",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
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
    records: PersonalRecords,
    foregroundMonitor: ForegroundMonitor,
    saveStatus: SaveStatus,
    onRetrySave: () -> Unit,
    onStartSession: (GameMode) -> String,
    onAbandonSession: (String) -> Unit,
    onSessionInterrupted: () -> Unit,
    onFalseStart: (String) -> Unit,
    onReaction: (String, GameMode, Int, List<Int>) -> ReactionUpdate,
    onBackHome: () -> Unit
) {
    val context = LocalContext.current
    val roundScope = rememberCoroutineScope()
    val timing = remember(mode) { RoundTimingGuard() }

    var state by remember(mode) { mutableStateOf(GameState.READY) }
    var currentRound by remember(mode) { mutableIntStateOf(1) }
    var lastScore by remember(mode) { mutableIntStateOf(0) }
    var roundTicket by remember(mode) { mutableStateOf<RoundTicket?>(null) }
    var waitJob by remember(mode) { mutableStateOf<Job?>(null) }
    var interactionEpoch by remember(mode) { mutableStateOf(Any()) }

    var isNewRecord by remember(mode) { mutableStateOf(false) }
    var sessionId by remember(mode) { mutableStateOf("") }
    var sessionReward by remember(mode) { mutableStateOf<SessionReward?>(null) }
    val abandonSession by rememberUpdatedState(onAbandonSession)
    val notifyInterruption by rememberUpdatedState(onSessionInterrupted)

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

    fun interruptSession() {
        interactionEpoch = Any()
        waitJob?.cancel()
        waitJob = null
        timing.cancel()
        roundTicket = null
        context.getSystemService(Vibrator::class.java)?.cancel()
        if (sessionId.isNotBlank() && sessionReward == null) {
            abandonSession(sessionId)
            sessionId = ""
            sessionScores = emptyList()
            falseStarts = 0
            lastScore = 0
            currentRound = 1
            isNewRecord = false
            state = GameState.INTERRUPTED
            notifyInterruption()
        }
    }

    fun beginRound() {
        interactionEpoch = Any()
        waitJob?.cancel()
        val ticket = timing.beginWaiting()
        if (ticket == null) {
            interruptSession()
            return
        }
        roundTicket = ticket
        state = GameState.WAITING
        waitJob = roundScope.launch {
            delay(Random.nextLong(1500, 4501))
            if (state == GameState.WAITING && roundTicket === ticket &&
                timing.signalGo(ticket, SystemClock.elapsedRealtime())
            ) {
                state = GameState.GO
                buzz(35)
            }
        }
    }

    fun resetSession() {
        interactionEpoch = Any()
        waitJob?.cancel()
        waitJob = null
        timing.cancel()
        roundTicket = null
        currentRound = 1
        lastScore = 0
        sessionScores = emptyList()
        falseStarts = 0
        isNewRecord = false
        sessionReward = null
        sessionId = ""
        state = GameState.READY
    }

    fun startSession() {
        currentRound = 1
        lastScore = 0
        sessionScores = emptyList()
        falseStarts = 0
        isNewRecord = false
        sessionReward = null
        sessionId = onStartSession(mode)
        beginRound()
    }

    DisposableEffect(mode, foregroundMonitor) {
        val unregister = foregroundMonitor.observe { foreground ->
            val wasForeground = timing.isForeground
            timing.setForeground(foreground)
            // The initial subscription may precede the first window-focus callback.
            // With no live session, that is not an interruption of the rendered READY action.
            if (!foreground && (wasForeground || sessionId.isNotBlank())) interruptSession()
        }
        onDispose {
            unregister()
            waitJob?.cancel()
            timing.setForeground(false)
            context.getSystemService(Vibrator::class.java)?.cancel()
            abandonSession(sessionId)
        }
    }

    val averageScore =
        if (sessionScores.isEmpty()) 0
        else sessionScores.average().roundToInt()

    val sessionBest = sessionScores.minOrNull() ?: 0
    // Bind each rendered handler to its phase/session/timing ticket. A captured old
    // callback cannot act on a newer session or on a signal not yet drawn on screen.
    val renderedState = state
    val renderedSessionId = sessionId
    val renderedTicket = roundTicket
    val renderedEpoch = interactionEpoch
    val onHomeTap: () -> Boolean = homeTap@{
        if (!timing.isForeground || interactionEpoch !== renderedEpoch ||
            sessionId != renderedSessionId
        ) return@homeTap false
        onAbandonSession(sessionId)
        onBackHome()
        true
    }
    val onGameTap: () -> Boolean = gameTap@{
        if (!timing.isForeground || interactionEpoch !== renderedEpoch || state != renderedState ||
            sessionId != renderedSessionId || roundTicket !== renderedTicket
        ) return@gameTap false
        when (renderedState) {
            GameState.READY, GameState.INTERRUPTED -> startSession()
            GameState.WAITING -> {
                waitJob?.cancel()
                timing.cancel()
                onFalseStart(renderedSessionId)
                if (falseStarts < Int.MAX_VALUE) falseStarts++
                state = GameState.FALSE_START
                buzz(90)
            }
            GameState.GO -> {
                val ticket = renderedTicket ?: return@gameTap false
                val score = timing.react(ticket, SystemClock.elapsedRealtime())
                if (score == null) {
                    interruptSession()
                    return@gameTap false
                }
                lastScore = score
                sessionScores = sessionScores + score
                val update = onReaction(sessionId, mode, score, sessionScores)
                isNewRecord = update.isNewRecord
                sessionReward = update.reward
                state = GameState.ROUND_RESULT
                buzz(45)
            }
            GameState.ROUND_RESULT -> {
                if (currentRound >= mode.rounds) state = GameState.SESSION_RESULT
                else {
                    currentRound++
                    beginRound()
                }
            }
            GameState.FALSE_START -> beginRound()
            GameState.SESSION_RESULT -> resetSession()
        }
        true
    }

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
                    modifier = Modifier
                        .semantics(mergeDescendants = true) { onClick(action = onHomeTap) }
                        .clickable { onHomeTap() },
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
                state != GameState.INTERRUPTED &&
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
                    mode.recordLabel,
                    formatRecord(records.bestFor(mode)),
                    Modifier.weight(1f)
                )

                StatCard(
                    "REACTIONS",
                    records.lifetimeReactions.toString(),
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
                    // Bind accessibility actions to the same immutable render snapshot.
                    .semantics(mergeDescendants = true) { onClick(action = onGameTap) }
                    .clickable { onGameTap() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(26.dp)
                ) {
                    val title = when (state) {
                        GameState.READY -> mode.title
                        GameState.WAITING -> "WAIT…"
                        GameState.GO -> "TAP!"
                        GameState.ROUND_RESULT -> "$lastScore ms"
                        GameState.FALSE_START -> "TOO SOON!"
                        GameState.INTERRUPTED -> "SESSION INTERRUPTED"
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

                        GameState.INTERRUPTED ->
                            "This unfinished session has ended. Saved reactions are kept.\nTap to restart this mode."

                        GameState.SESSION_RESULT ->
                            "Tap to play this mode again."
                    }

                    Text(
                        title,
                        color = Color.White,
                        fontSize = when (state) {
                            GameState.ROUND_RESULT -> 40.sp
                            GameState.SESSION_RESULT, GameState.INTERRUPTED -> 28.sp
                            else -> 34.sp
                        },
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

                    if ((state == GameState.ROUND_RESULT && sessionReward != null) ||
                        state == GameState.SESSION_RESULT
                    ) {
                        Spacer(Modifier.height(12.dp))
                        ProgressSaveNotice(saveStatus, onRetrySave)
                    }

                    sessionReward?.let { reward ->
                        if (reward.newLevel > reward.previousLevel && (
                            state == GameState.ROUND_RESULT || state == GameState.SESSION_RESULT
                        )) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "LEVEL UP! LEVEL ${reward.newLevel}",
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                color = Color(0xFFFFD166),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (isNewRecord && (
                        state == GameState.ROUND_RESULT || state == GameState.SESSION_RESULT
                    )) {
                        Spacer(Modifier.height(18.dp))

                        Text(
                            "NEW PERSONAL RECORD!\n${mode.recordLabel}: ${formatRecord(records.bestFor(mode))}",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = Color(0xFF62E6B5),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                    }

                    sessionReward?.let { reward ->
                        if (reward.newlyUnlocked.isNotEmpty() && (
                            state == GameState.ROUND_RESULT || state == GameState.SESSION_RESULT
                        )) {
                            Spacer(Modifier.height(14.dp))
                            AchievementUnlockFeedback(reward.newlyUnlocked)
                        }
                    }

                    if (state == GameState.SESSION_RESULT) {
                        Spacer(Modifier.height(26.dp))

                        sessionReward?.let { reward ->
                            Text(
                                "MEDAL: ${reward.medal.title}",
                                color = medalColor(reward.medal),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(10.dp))

                            Text(
                                "+${reward.awardedXp} XP",
                                color = Color(0xFF62E6B5),
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Black
                            )

                            Text(
                                "Base ${reward.xp.baseXp} XP • Speed +${reward.xp.speedBonusXp} XP\n" +
                                    "Consistency +${reward.xp.consistencyBonusXp} XP",
                                color = Color(0xFFAAB4D0),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )

                            if (reward.awardedXp < reward.xp.totalXp) {
                                Text(
                                    "XP limit reached",
                                    color = Color(0xFFAAB4D0),
                                    fontSize = 13.sp
                                )
                            }

                            Spacer(Modifier.height(18.dp))
                            PlayerProgressCard(records.totalXp)
                            Spacer(Modifier.height(18.dp))

                            if (reward.newlyUnlocked.isNotEmpty()) {
                                NewAchievementsCard(reward.newlyUnlocked)
                                Spacer(Modifier.height(18.dp))
                            }
                        }

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

                    GameState.INTERRUPTED ->
                        "Tap to restart, or choose HOME for another mode."

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
private fun StartupScreen(failed: Boolean, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1020)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                if (failed) "Player progress could not be loaded." else "Loading player progress…",
                color = Color.White,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            if (failed) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("RETRY LOAD") }
            }
        }
    }
}

@Composable
internal fun ProgressSaveNotice(
    status: SaveStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status == SaveStatus.IDLE) return
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when (status) {
                SaveStatus.IDLE -> ""
                SaveStatus.SAVING -> "Saving completed progress…"
                SaveStatus.SAVED -> "Completed progress saved"
                SaveStatus.FAILED -> "Completed progress could not be saved. Keep the app open and retry."
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (status == SaveStatus.FAILED) Color(0xFFFFD166) else Color(0xFF62E6B5),
            fontSize = if (status == SaveStatus.FAILED) 14.sp else 12.sp,
            textAlign = TextAlign.Center
        )
        if (status == SaveStatus.FAILED) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text("RETRY SAVE") }
        }
    }
}

private fun formatRecord(scoreMs: Int): String = if (scoreMs == 0) "—" else "$scoreMs ms"

private fun medalColor(medal: PerformanceMedal): Color = when (medal) {
    PerformanceMedal.ELITE -> Color(0xFF62E6B5)
    PerformanceMedal.PLATINUM -> Color(0xFFAFD8F8)
    PerformanceMedal.GOLD -> Color(0xFFFFD166)
    PerformanceMedal.SILVER -> Color(0xFFCCD3DF)
    PerformanceMedal.BRONZE -> Color(0xFFE4A16B)
    PerformanceMedal.UNRANKED -> Color(0xFFAAB4D0)
}

@Composable
private fun PlayerProgressCard(totalXp: Long) {
    val progress = PlayerProgression.levelProgress(totalXp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151D35)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "LEVEL ${progress.level}",
                color = Color(0xFF62E6B5),
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "TOTAL XP: ${progress.totalXp}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF62E6B5),
                trackColor = Color(0xFF2A3554)
            )
            Text(
                if (progress.isMaxLevel) "MAX LEVEL"
                else "${progress.xpIntoLevel} / ${progress.xpToNextLevel} XP TO LEVEL ${progress.level + 1}",
                color = Color(0xFFAAB4D0),
                fontSize = 12.sp
            )
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
