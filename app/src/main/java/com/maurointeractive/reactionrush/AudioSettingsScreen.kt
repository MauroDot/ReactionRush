package com.maurointeractive.reactionrush

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Slider feedback is immediate; disk checkpoints are requested only when a drag finishes. */
@Composable
internal fun AudioSettingsScreen(
    settings: AudioSettings,
    onSettingsChanged: (AudioSettings) -> Unit,
    onSaveSettings: () -> Unit,
    onBackHome: () -> Unit
) {
    BackHandler(onBack = onBackHome)
    val normalized = settings.normalized()
    Surface(modifier = Modifier.fillMaxSize(), color = RushColors.Background) {
        Column(Modifier.fillMaxSize().padding(horizontal = RushSpace.Xl)) {
            ScreenHeader(title = "AUDIO & HAPTICS", onBack = onBackHome)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RushSpace.Md)
            ) {
                RushPanel(accent = RushColors.Mint) {
                    SectionLabel("MUSIC")
                    AudioToggle("Music", normalized.musicEnabled, "audio-music-enabled") { enabled ->
                        onSettingsChanged(normalized.copy(musicEnabled = enabled))
                        onSaveSettings()
                    }
                    AudioVolume(
                        label = "Music volume", tag = "audio-music-volume", value = normalized.musicVolume,
                        enabled = normalized.musicEnabled,
                        onValueChange = { onSettingsChanged(normalized.copy(musicVolume = it)) },
                        onFinished = onSaveSettings
                    )
                }
                RushPanel(accent = RushColors.Cyan) {
                    SectionLabel("SOUND EFFECTS")
                    AudioToggle("Sound effects", normalized.sfxEnabled, "audio-sfx-enabled") { enabled ->
                        onSettingsChanged(normalized.copy(sfxEnabled = enabled))
                        onSaveSettings()
                    }
                    AudioVolume(
                        label = "Sound-effects volume", tag = "audio-sfx-volume", value = normalized.sfxVolume,
                        enabled = normalized.sfxEnabled,
                        onValueChange = { onSettingsChanged(normalized.copy(sfxVolume = it)) },
                        onFinished = onSaveSettings
                    )
                    Text(
                        "Includes celebration sounds.",
                        color = RushColors.Muted, style = MaterialTheme.typography.bodySmall
                    )
                }
                RushPanel(accent = RushColors.Gold) {
                    SectionLabel("HAPTICS")
                    AudioToggle("Haptics", normalized.hapticsEnabled, "audio-haptics-enabled") { enabled ->
                        onSettingsChanged(normalized.copy(hapticsEnabled = enabled))
                        onSaveSettings()
                    }
                    Text(
                        "Vibration feedback when supported by your device.",
                        color = RushColors.Muted, style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Gameplay music continues through WAITING and GO. " +
                        "GO does not change the music or play a sound effect. " +
                        "These controls do not change your device's system volume.",
                    color = RushColors.Muted, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(RushSpace.Xl))
            }
        }
    }
}

@Composable
private fun AudioToggle(label: String, enabled: Boolean, tag: String, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RushSpace.Md)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = RushColors.Text, style = MaterialTheme.typography.titleMedium)
            Text(
                if (enabled) "On" else "Off",
                color = RushColors.Muted, style = MaterialTheme.typography.bodySmall
            )
        }
        // The row owns the single accessible toggle action and its full touch target.
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun AudioVolume(
    label: String,
    tag: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Text(
        "$label: ${(value * 100).roundToInt()}%",
        color = RushColors.Text, style = MaterialTheme.typography.bodyLarge
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onFinished,
        enabled = enabled,
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .semantics { contentDescription = label }
    )
}
