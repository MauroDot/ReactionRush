package com.maurointeractive.reactionrush

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** RR-005A: shared visual tokens. Gameplay timing and rewards never depend on animation. */
object RushColors {
    val Background = Color(0xFF0B1020)
    val Surface = Color(0xFF151D35)
    val SurfaceRaised = Color(0xFF1C2844)
    val Mint = Color(0xFF62E6B5)
    val Cyan = Color(0xFF4DA3FF)
    val Gold = Color(0xFFFFB454)
    val Text = Color(0xFFF5F7FF)
    val Muted = Color(0xFFAAB8D6)
    val Subtle = Color(0xFF95A5C6)
    val Border = Color(0xFF33415E)
    val Success = Color(0xFF0B6549)
    val Warning = Gold
    val Danger = Color(0xFFFF9AA8)
}

object RushSpace {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
}

object RushShapes {
    val Card = RoundedCornerShape(24.dp)
    val Small = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(50)
}

private val RushTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-2).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 1.2.sp)
)

@Composable
fun RushTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RushColors.Mint, onPrimary = RushColors.Background,
            secondary = RushColors.Cyan, onSecondary = RushColors.Background,
            tertiary = RushColors.Gold, onTertiary = RushColors.Background,
            background = RushColors.Background, onBackground = RushColors.Text,
            surface = RushColors.Surface, onSurface = RushColors.Text,
            surfaceVariant = RushColors.SurfaceRaised, onSurfaceVariant = RushColors.Muted,
            outline = RushColors.Border, error = RushColors.Danger, onError = RushColors.Background
        ),
        typography = RushTypography,
        shapes = Shapes(small = RushShapes.Small, medium = RushShapes.Small, large = RushShapes.Card),
        content = content
    )
}

@Composable
internal fun RushPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RushShapes.Card,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.38f) ?: RushColors.Border),
        colors = CardDefaults.cardColors(containerColor = RushColors.Surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(RushSpace.Lg),
            verticalArrangement = Arrangement.spacedBy(RushSpace.Sm),
            content = content
        )
    }
}

@Composable
internal fun ScreenHeader(title: String, backLabel: String = "‹ HOME", onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = RushSpace.Lg)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(backLabel, color = RushColors.Muted, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            title, modifier = Modifier.semantics { heading() },
            color = RushColors.Text,
            style = if (LocalDensity.current.fontScale > 1.3f) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.headlineMedium
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = RushSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RushSpace.Md)
    ) {
        Box(Modifier.size(4.dp, 16.dp).background(RushColors.Mint, RushShapes.Pill))
        Text(text, Modifier.weight(1f).semantics { heading() }, color = RushColors.Mint, style = MaterialTheme.typography.labelLarge)
    }
}

/** Decorative original pulse/target mark; adjacent text supplies its accessible name. */
@Composable
internal fun PulseMark(modifier: Modifier = Modifier, accent: Color = RushColors.Mint) {
    Canvas(modifier) {
        // Same 108-unit silhouette as rr_pulse_mark, cropped to its safe zone in-app.
        val unit = size.minDimension / 66f
        val origin = Offset(size.width / 2f - 54f * unit, size.height / 2f - 54f * unit)
        val ringOrigin = origin + Offset(27f * unit, 27f * unit)
        drawArc(RushColors.Cyan, -164f, 148f, false, ringOrigin, Size(54f * unit, 54f * unit), style = Stroke(4.5f * unit, cap = StrokeCap.Round))
        drawArc(RushColors.Cyan, 16f, 148f, false, ringOrigin, Size(54f * unit, 54f * unit), style = Stroke(4.5f * unit, cap = StrokeCap.Round))
        val bolt = Path().apply {
            moveTo(origin.x + 60.5f * unit, origin.y + 30f * unit)
            lineTo(origin.x + 40.5f * unit, origin.y + 57f * unit)
            lineTo(origin.x + 52f * unit, origin.y + 57f * unit)
            lineTo(origin.x + 47f * unit, origin.y + 78f * unit)
            lineTo(origin.x + 68.5f * unit, origin.y + 49f * unit)
            lineTo(origin.x + 57f * unit, origin.y + 49f * unit)
            close()
        }
        drawPath(bolt, accent)
    }
}

internal fun modeAccent(mode: GameMode): Color = when (mode) {
    GameMode.QUICK_DRAW -> RushColors.Cyan
    GameMode.CLASSIC_RUSH -> RushColors.Mint
    GameMode.ENDURANCE_RUSH -> RushColors.Gold
}

internal fun medalTint(medal: PerformanceMedal): Color = when (medal) {
    PerformanceMedal.ELITE -> RushColors.Mint
    PerformanceMedal.PLATINUM -> Color(0xFFB5DCFF)
    PerformanceMedal.GOLD -> RushColors.Gold
    PerformanceMedal.SILVER -> Color(0xFFCCD3DF)
    PerformanceMedal.BRONZE -> Color(0xFFE9AF7B)
    PerformanceMedal.UNRANKED -> RushColors.Muted
}

@Composable
internal fun MedalBadge(medal: PerformanceMedal, modifier: Modifier = Modifier) {
    val tint = medalTint(medal)
    val artwork = medalArtwork(medal)
    Column(
        modifier.background(tint.copy(alpha = 0.09f), RushShapes.Small)
            .border(1.dp, tint.copy(alpha = 0.5f), RushShapes.Small).padding(RushSpace.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RushSpace.Sm)
    ) {
        if (artwork != null) {
            Image(
                painter = painterResource(artwork),
                contentDescription = null,
                modifier = Modifier.size(64.dp).testTag("medal-art-${medal.name}"),
                contentScale = ContentScale.Fit
            )
        } else {
            Canvas(Modifier.size(38.dp)) {
                drawCircle(tint, style = Stroke(2.dp.toPx()))
                val diamond = Path().apply {
                    moveTo(size.width / 2, size.height * 0.2f)
                    lineTo(size.width * 0.73f, size.height / 2)
                    lineTo(size.width / 2, size.height * 0.8f)
                    lineTo(size.width * 0.27f, size.height / 2)
                    close()
                }
                drawPath(diamond, tint)
            }
        }
        Text("MEDAL: ${medal.title}", color = tint, style = MaterialTheme.typography.titleMedium)
    }
}

/** Finite, compositor-only entrance. Compose honors the system animator duration scale. */
@Composable
internal fun Entrance(content: @Composable (Modifier) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(180), label = "screen entrance")
    content(Modifier.graphicsLayer { this.alpha = alpha; translationY = (1f - alpha) * 12f })
}

/** Press response never defers the click; use only on navigation and mode cards. */
internal fun Modifier.rushPress(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(90), label = "card press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}
