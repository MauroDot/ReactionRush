package com.maurointeractive.reactionrush


import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
    STATISTICS,
    SETTINGS
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
    RushTheme {
        Surface(color = RushColors.Background, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { ReactionRushContent(foregroundMonitor) }
        }
    }
}

@Composable
private fun ReactionRushContent(foregroundMonitor: ForegroundMonitor) {
    val applicationContext = LocalContext.current.applicationContext
    var repository by remember { mutableStateOf<RecordsRepository?>(null) }
    var playerRecords by remember { mutableStateOf<PersonalRecords?>(null) }
    var audioSettings by remember { mutableStateOf(AudioSettings()) }
    var audioSettingsDirty by remember { mutableStateOf(false) }
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
                Triple(store, store.load(), store.loadAudioSettings())
            }
            // Preferences are hydrated now. This memory-only invalidation runs after
            // cancellation checks on Main, so an obsolete startup cannot clear a newer game.
            val orphanedSession = loaded.first.recoverInterruptedSession()
            showInterruptedNotice = showInterruptedNotice || hadUnfinishedSession || orphanedSession
            hadUnfinishedSession = false
            repository = loaded.first
            playerRecords = loaded.second
            audioSettings = loaded.third
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
    // One owner for the loaded application, never one player per recomposition or round.
    val audio = remember(recordsRepository) {
        AudioController(AndroidAudioBackend(applicationContext), audioSettings)
    }
    fun persistAudioSettings() {
        if (audioSettingsDirty) {
            recordsRepository.saveAudioSettings(audioSettings)
            audioSettingsDirty = false
        }
    }
    val savePendingAudioSettings by rememberUpdatedState(::persistAudioSettings)
    DisposableEffect(audio, foregroundMonitor) {
        val unregister = foregroundMonitor.observe { foreground ->
            audio.setForeground(foreground)
            if (!foreground) savePendingAudioSettings()
        }
        onDispose {
            unregister()
            savePendingAudioSettings()
            audio.release()
        }
    }
    SideEffect {
        audio.updateSettings(audioSettings)
        if (screen != AppScreen.GAME) audio.showMenu()
    }

    Column(Modifier.fillMaxSize().background(RushColors.Background)) {
        if (screen != AppScreen.GAME && saveStatus != SaveStatus.IDLE) {
            ProgressSaveNotice(saveStatus, recordsRepository::retrySave, Modifier.padding(16.dp),
                settings = screen == AppScreen.SETTINGS)
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                AppScreen.HOME -> {
                    HomeScreen(
                        records = records,
                        interrupted = showInterruptedNotice,
                        onDismissInterruption = { showInterruptedNotice = false },
                        onAchievementsSelected = { audio.navigate(); screen = AppScreen.ACHIEVEMENTS },
                        onStatisticsSelected = { audio.navigate(); screen = AppScreen.STATISTICS },
                        onSettingsSelected = { audio.navigate(); screen = AppScreen.SETTINGS },
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
                        audio = audio,
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
                    AchievementsScreen(records = records, onBackHome = { audio.navigate(); screen = AppScreen.HOME })
                }

                AppScreen.STATISTICS -> {
                    StatisticsScreen(records = records, onBackHome = { audio.navigate(); screen = AppScreen.HOME })
                }

                AppScreen.SETTINGS -> {
                    AudioSettingsScreen(
                        settings = audioSettings,
                        onSettingsChanged = { updated ->
                            val normalized = updated.normalized()
                            if (audioSettings != normalized) audioSettingsDirty = true
                            audioSettings = normalized
                            audio.updateSettings(audioSettings)
                        },
                        onSaveSettings = ::persistAudioSettings,
                        onBackHome = { persistAudioSettings(); audio.navigate(); screen = AppScreen.HOME }
                    )
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
    onSettingsSelected: () -> Unit,
    onModeSelected: (GameMode) -> Unit
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val homeScroll = rememberScrollState()
    val navigationScope = rememberCoroutineScope()
    var modeSectionOffset by remember { mutableIntStateOf(0) }
    CyberArcadeBackground(ArcadeBackdrop.HOME) {
        Entrance { entrance ->
            Column(
                modifier = entrance.fillMaxSize()
                    .verticalScroll(homeScroll).padding(RushSpace.Xl),
                verticalArrangement = Arrangement.spacedBy(RushSpace.Lg)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mauro Interactive", color = RushColors.Muted, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(RushSpace.Sm))
                        Text("REACTION RUSH", color = RushColors.Text,
                            style = if (largeText) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayLarge)
                    }
                    if (!largeText) PulseMark(Modifier.padding(start = RushSpace.Md).size(56.dp))
                }
                Text("TEST YOUR REFLEXES", color = RushColors.Mint, style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = { navigationScope.launch { homeScroll.animateScrollTo(modeSectionOffset) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RushShapes.Small
                ) { Text("CHOOSE A MODE  ↓", style = MaterialTheme.typography.labelLarge) }
                if (interrupted) {
                    RushPanel(accent = RushColors.Gold) {
                        Text("An unfinished session was interrupted. Choose a mode to start again.",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = RushColors.Gold, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onDismissInterruption, modifier = Modifier.heightIn(min = 48.dp)) { Text("DISMISS") }
                    }
                }
                PlayerProgressCard(records.totalXp)
                if (largeText) Column(verticalArrangement = Arrangement.spacedBy(RushSpace.Md)) {
                    StatCard("OVERALL BEST", formatRecord(records.lifetimeBestMs), Modifier.fillMaxWidth())
                    StatCard("REACTIONS", records.lifetimeReactions.toString(), Modifier.fillMaxWidth())
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RushSpace.Md)) {
                    StatCard("OVERALL BEST", formatRecord(records.lifetimeBestMs), Modifier.weight(1f))
                    StatCard("REACTIONS", records.lifetimeReactions.toString(), Modifier.weight(1f))
                }
                OutlinedButton(
                    onClick = onAchievementsSelected, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RushShapes.Small, border = BorderStroke(1.dp, RushColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RushColors.Gold)
                ) {
                    Text("ACHIEVEMENTS · ${records.achievements.unlocked.size} / ${Achievement.entries.size}",
                        style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onStatisticsSelected, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RushShapes.Small, border = BorderStroke(1.dp, RushColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RushColors.Cyan)
                ) { Text("STATISTICS", style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(
                    onClick = onSettingsSelected, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RushShapes.Small, border = BorderStroke(1.dp, RushColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RushColors.Muted)
                ) { Text("AUDIO & HAPTICS", style = MaterialTheme.typography.labelLarge) }
                Box(Modifier.onGloballyPositioned { modeSectionOffset = it.positionInParent().y.roundToInt() }) {
                    SectionLabel("SELECT MODE")
                }
                GameMode.entries.forEach { mode ->
                    ModeCard(mode, records.bestFor(mode), modeAccent(mode)) { onModeSelected(mode) }
                }
                Text("PRECISION. FOCUS. ONE MORE ROUND.", color = RushColors.Subtle,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = RushSpace.Md))
            }
        }
    }
}

@Composable
private fun ModeCard(mode: GameMode, best: Int, accent: Color, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("mode-${mode.name}").rushPress(press)
            .clickable(interactionSource = press, indication = null, role = Role.Button,
                onClickLabel = "Play ${mode.title}", onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RushColors.Surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)), shape = RushShapes.Card
    ) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent)))
            .padding(RushSpace.Xl), verticalArrangement = Arrangement.spacedBy(RushSpace.Md)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("0${mode.ordinal + 1} / ${when (mode) {
                    GameMode.QUICK_DRAW -> "SINGLE SHOT"
                    GameMode.CLASSIC_RUSH -> "FIND YOUR RHYTHM"
                    GameMode.ENDURANCE_RUSH -> "STAY SHARP"
                }}", modifier = Modifier.weight(1f), color = accent, style = MaterialTheme.typography.labelSmall)
                Text("${mode.rounds} ${if (mode.rounds == 1) "ROUND" else "ROUNDS"}",
                    color = RushColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(mode.title, color = RushColors.Text, style = MaterialTheme.typography.headlineMedium)
            Text(mode.description, color = RushColors.Muted, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = accent.copy(alpha = 0.2f))
            Text("${mode.recordLabel}: ${formatRecord(best)}", color = accent, style = MaterialTheme.typography.labelLarge)
            Text("PLAY  →", color = RushColors.Text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun GameScreen(
    mode: GameMode,
    records: PersonalRecords,
    foregroundMonitor: ForegroundMonitor,
    audio: AudioController,
    saveStatus: SaveStatus,
    onRetrySave: () -> Unit,
    onStartSession: (GameMode) -> String,
    onAbandonSession: (String) -> Unit,
    onSessionInterrupted: () -> Unit,
    onFalseStart: (String) -> Unit,
    onReaction: (String, GameMode, Int, List<Int>) -> ReactionUpdate,
    onBackHome: () -> Unit
) {
    val roundScope = rememberCoroutineScope()
    val audioOwner = remember(mode, audio) { Any() }
    val gameInteraction = remember { MutableInteractionSource() }
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

    fun interruptSession() {
        interactionEpoch = Any()
        waitJob?.cancel()
        waitJob = null
        timing.cancel()
        roundTicket = null
        if (sessionId.isNotBlank() && sessionReward == null) {
            audio.interrupt(audioOwner)
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
        // Cancel transient cues before the randomized interval; keep mode music continuous.
        // No audio operation is placed ahead of GO timestamp capture or reaction scoring.
        audio.beginCompetitive(audioOwner, sessionId)
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
                audio.goHaptic(audioOwner)
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
        audio.ready(audioOwner, mode)
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

    DisposableEffect(mode, foregroundMonitor, audio) {
        audio.enterGame(audioOwner, mode)
        audio.select()
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
            audio.leaveGame(audioOwner)
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
        audio.showMenu()
        audio.navigate()
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
                audio.falseStart(audioOwner, renderedSessionId, falseStarts)
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
                audio.reaction(audioOwner, sessionId, currentRound, update)
            }
            GameState.ROUND_RESULT -> {
                if (currentRound >= mode.rounds) {
                    state = GameState.SESSION_RESULT
                    audio.showResults(audioOwner)
                }
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
        GameState.GO -> RushColors.Success
        GameState.FALSE_START -> Color(0xFF502235)
        else -> Color.Transparent
    }

    CyberArcadeBackground(sessionBackdrop(mode, state, sessionReward != null)) {
        // Immediate solid signal colors cover the fixed mode artwork without changing it.
        Box(Modifier.matchParentSize().background(bg))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 640.dp || LocalDensity.current.fontScale > 1.2f
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = RushSpace.Lg, vertical = RushSpace.Sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "‹ HOME",
                        modifier = Modifier
                            .testTag("game-home")
                            .semantics(mergeDescendants = true) { onClick(action = onHomeTap) }
                            .clickable(role = Role.Button) { onHomeTap() }
                            .sizeIn(minWidth = 64.dp, minHeight = 48.dp)
                            .wrapContentSize(Alignment.CenterStart),
                        color = RushColors.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.width(RushSpace.Md))

                    Text(
                        mode.title,
                        modifier = Modifier.weight(1f),
                        color = if (state == GameState.GO) RushColors.Text else modeAccent(mode),
                        fontSize = if (compact) 14.sp else 18.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.End
                    )
                }

                Spacer(Modifier.height(RushSpace.Sm))

                if (
                    state != GameState.READY &&
                    state != GameState.INTERRUPTED &&
                    state != GameState.SESSION_RESULT
                ) {
                    Text(
                        "ROUND $currentRound / ${mode.rounds}",
                        color = if (state == GameState.GO) RushColors.Text else RushColors.Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(RushSpace.Sm))
                }

                if (compact) {
                    Text("${mode.recordLabel}: ${formatRecord(records.bestFor(mode))}",
                        color = if (state == GameState.GO) RushColors.Text else RushColors.Muted,
                        style = MaterialTheme.typography.bodySmall)
                } else Row(
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

                Spacer(Modifier.height(RushSpace.Md))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("game-action")
                        .clip(RushShapes.Card)
                        .background(
                            if (state == GameState.GO) RushColors.Text.copy(alpha = 0.04f) else RushColors.Surface,
                            RushShapes.Card
                        )
                        .border(1.dp, when (state) {
                            GameState.GO -> RushColors.Mint
                            GameState.FALSE_START -> RushColors.Danger
                            GameState.INTERRUPTED -> RushColors.Gold
                            GameState.WAITING -> RushColors.Gold.copy(alpha = 0.65f)
                            else -> modeAccent(mode).copy(alpha = 0.4f)
                        }, RushShapes.Card)
                        // Bind accessibility actions to the same immutable render snapshot.
                        .semantics(mergeDescendants = true) { onClick(action = onGameTap) }
                        // No ripple may linger from a retry into the timing-critical GO field.
                        .clickable(interactionSource = gameInteraction, indication = null,
                            role = Role.Button) { onGameTap() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                            .padding(if (compact) RushSpace.Lg else RushSpace.Xl)
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

                        if (state == GameState.READY) {
                            PulseMark(Modifier.size(if (compact) 48.dp else 72.dp), modeAccent(mode))
                            Spacer(Modifier.height(RushSpace.Lg))
                        }
                        Text(
                            when (state) {
                                GameState.READY -> "YOUR NEXT CHALLENGE"
                                GameState.WAITING -> "HOLD YOUR FOCUS"
                                GameState.GO -> "REACT NOW"
                                GameState.FALSE_START -> "EARLY TAP"
                                GameState.INTERRUPTED -> "ROUND ENDED SAFELY"
                                GameState.ROUND_RESULT -> "REACTION LOCKED"
                                GameState.SESSION_RESULT -> "SESSION SUMMARY"
                            },
                            color = if (state == GameState.GO) RushColors.Text else modeAccent(mode),
                            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(RushSpace.Md))
                        Text(
                            title,
                            color = when (state) {
                                GameState.FALSE_START -> RushColors.Danger
                                GameState.INTERRUPTED -> RushColors.Gold
                                else -> RushColors.Text
                            },
                            fontSize = when (state) {
                                GameState.GO -> if (compact) 38.sp else 64.sp
                                GameState.ROUND_RESULT -> if (compact) 34.sp else 46.sp
                                GameState.SESSION_RESULT, GameState.INTERRUPTED -> if (compact) 20.sp else 24.sp
                                else -> if (compact) 24.sp else 34.sp
                            },
                            fontFamily = if (state == GameState.GO || state == GameState.ROUND_RESULT) FontFamily.Monospace else FontFamily.SansSerif,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(14.dp))

                        Text(
                            subtitle,
                            color = if (state == GameState.GO) RushColors.Text else RushColors.Muted,
                            fontSize = if (compact) 15.sp else 17.sp,
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
                                    }.background(RushColors.Gold.copy(alpha = 0.08f), RushShapes.Small)
                                        .padding(RushSpace.Md),
                                    color = RushColors.Gold,
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
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                    .background(RushColors.Mint.copy(alpha = 0.08f), RushShapes.Small)
                                    .border(1.dp, RushColors.Mint.copy(alpha = 0.4f), RushShapes.Small)
                                    .padding(RushSpace.Md),
                                color = RushColors.Mint,
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
                                MedalBadge(reward.medal, Modifier.fillMaxWidth())

                                Spacer(Modifier.height(10.dp))

                                Text(
                                    "+${reward.awardedXp} XP",
                                    color = RushColors.Mint,
                                    fontSize = 30.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black
                                )

                                Text(
                                    "Base ${reward.xp.baseXp} XP • Speed +${reward.xp.speedBonusXp} XP\n" +
                                        "Consistency +${reward.xp.consistencyBonusXp} XP",
                                    color = RushColors.Muted,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )

                                if (reward.awardedXp < reward.xp.totalXp) {
                                    Text(
                                        "XP limit reached",
                                        color = RushColors.Muted,
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
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = RushSpace.Xs),
                                        color = RushColors.SurfaceRaised, shape = RushShapes.Small
                                    ) {
                                        Text(
                                            "Round ${index + 1}: $score ms",
                                            modifier = Modifier.padding(RushSpace.Md),
                                            color = RushColors.Text, style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(RushSpace.Sm))

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
                    color = if (state == GameState.GO) RushColors.Text else RushColors.Subtle,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(RushSpace.Xs))
            }
        }
    }
}

@Composable
private fun StartupScreen(failed: Boolean, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = RushColors.Background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            PulseMark(Modifier.size(64.dp))
            Spacer(Modifier.height(RushSpace.Xl))
            Text(
                if (failed) "Player progress could not be loaded." else "Loading player progress…",
                color = RushColors.Text,
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
    modifier: Modifier = Modifier,
    settings: Boolean = false
) {
    if (status == SaveStatus.IDLE) return
    Column(
        modifier = modifier.fillMaxWidth()
            .background(RushColors.Mint.copy(alpha = 0.06f), RushShapes.Small)
            .border(1.dp, if (status == SaveStatus.FAILED) RushColors.Gold else RushColors.Border, RushShapes.Small)
            .padding(RushSpace.Md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when (status) {
                SaveStatus.IDLE -> ""
                SaveStatus.SAVING -> if (settings) "Saving audio settings…" else "Saving completed progress…"
                SaveStatus.SAVED -> if (settings) "Audio settings saved" else "Completed progress saved"
                SaveStatus.FAILED -> if (settings) "Audio settings could not be saved. Keep the app open and retry."
                    else "Completed progress could not be saved. Keep the app open and retry."
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (status == SaveStatus.FAILED) RushColors.Gold else RushColors.Mint,
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

@Composable
private fun PlayerProgressCard(totalXp: Long) {
    val progress = PlayerProgression.levelProgress(totalXp)
    Card(
        modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = RushColors.Surface),
        border = BorderStroke(1.dp, RushColors.Mint.copy(alpha = 0.4f)), shape = RushShapes.Card
    ) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(RushColors.Mint.copy(alpha = 0.09f), Color.Transparent)))
                .padding(RushSpace.Lg), verticalArrangement = Arrangement.spacedBy(RushSpace.Md)
        ) {
            Text("PLAYER PROGRESSION", color = RushColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text("LEVEL ${progress.level}", color = RushColors.Mint, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("TOTAL XP: ${progress.totalXp}", color = RushColors.Text, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp),
                color = RushColors.Mint, trackColor = RushColors.Border, drawStopIndicator = {}
            )
            Text(
                if (progress.isMaxLevel) "MAX LEVEL"
                else "${progress.xpIntoLevel} / ${progress.xpToNextLevel} XP TO LEVEL ${progress.level + 1}",
                color = RushColors.Muted, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier, colors = CardDefaults.cardColors(containerColor = RushColors.Surface),
        border = BorderStroke(1.dp, RushColors.Border), shape = RushShapes.Small
    ) {
        Column(Modifier.fillMaxWidth().padding(RushSpace.Lg), verticalArrangement = Arrangement.spacedBy(RushSpace.Sm)) {
            Text(label, color = RushColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = RushColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun MiniResult(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier, colors = CardDefaults.cardColors(containerColor = RushColors.SurfaceRaised),
        border = BorderStroke(1.dp, RushColors.Border), shape = RushShapes.Small
    ) {
        Column(
            Modifier.fillMaxWidth().padding(RushSpace.Md), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RushSpace.Sm)
        ) {
            Text(label, color = RushColors.Muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text(value, color = RushColors.Text, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        }
    }
}
