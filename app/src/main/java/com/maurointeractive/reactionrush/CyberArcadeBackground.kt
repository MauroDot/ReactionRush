package com.maurointeractive.reactionrush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource

internal enum class ArcadeBackdrop(val resourceId: Int, val tag: String) {
    HOME(R.drawable.rr_main_menu_background, "home"),
    QUICK_DRAW(R.drawable.rr_quick_draw_background, "quick-draw"),
    CLASSIC_RUSH(R.drawable.rr_classic_rush_background, "classic-rush"),
    ENDURANCE_RUSH(R.drawable.rr_endurance_rush_background, "endurance-rush"),
    RESULTS(R.drawable.rr_results_background, "results")
}

internal fun gameBackdrop(mode: GameMode): ArcadeBackdrop = when (mode) {
    GameMode.QUICK_DRAW -> ArcadeBackdrop.QUICK_DRAW
    GameMode.CLASSIC_RUSH -> ArcadeBackdrop.CLASSIC_RUSH
    GameMode.ENDURANCE_RUSH -> ArcadeBackdrop.ENDURANCE_RUSH
}

internal fun sessionBackdrop(
    mode: GameMode,
    state: GameState,
    hasVerifiedReward: Boolean
): ArcadeBackdrop = if (state == GameState.SESSION_RESULT && hasVerifiedReward) {
    ArcadeBackdrop.RESULTS
} else {
    gameBackdrop(mode)
}

internal const val ARCADE_BACKDROP_SCRIM_ALPHA = 0.86f

/** Static decoration beneath the existing controls; it owns no input, timing, or lifecycle. */
@Composable
internal fun CyberArcadeBackground(
    asset: ArcadeBackdrop,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = ARCADE_BACKDROP_SCRIM_ALPHA,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(modifier = modifier.fillMaxSize(), color = RushColors.Background) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(asset.resourceId),
                contentDescription = null,
                modifier = Modifier.matchParentSize().testTag("background-${asset.tag}"),
                alignment = Alignment.Center,
                contentScale = ContentScale.Crop
            )
            Box(Modifier.matchParentSize().background(RushColors.Background.copy(alpha = scrimAlpha)))
            content()
        }
    }
}
