package com.imdadburki.cropcast.feature.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imdadburki.cropcast.core.common.AspectPreset
import com.imdadburki.cropcast.core.common.AudioMode
import com.imdadburki.cropcast.core.common.QualityPreset
import com.imdadburki.cropcast.core.common.RecordingMode
import com.imdadburki.cropcast.core.database.RecordingSettingsDao
import com.imdadburki.cropcast.core.database.RecordingSettingsEntity
import kotlinx.coroutines.launch

private fun defaultSettings() = RecordingSettingsEntity(
    selectedMode = RecordingMode.CUSTOM_REGION.name,
    regionLeft = 0.10f,
    regionTop = 0.15f,
    regionWidth = 0.60f,
    regionHeight = 0.55f,
    aspectRatio = null,
    resolutionPreset = QualityPreset.HIGH.name,
    frameRate = 30,
    bitrate = 12_000_000,
    audioMode = AudioMode.NONE.name,
)

/**
 * Recording Setup (spec §4, Screen 04). Mode, quality, frame rate and audio
 * are real controls persisted to Room — they drive the recording engine.
 * Custom Region is the primary mode. The Continue button opens the region
 * selector; the actual capture engine lands in Phase 3.
 */
@Composable
fun SetupScreen(
    settingsDao: RecordingSettingsDao,
    onContinueToRegion: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(defaultSettings()) }
    LaunchedEffect(Unit) {
        settings = settingsDao.get() ?: defaultSettings().also { settingsDao.save(it) }
    }

    fun update(mutator: (RecordingSettingsEntity) -> RecordingSettingsEntity) {
        val next = mutator(settings)
        settings = next
        scope.launch { settingsDao.save(next) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Recording Setup", style = MaterialTheme.typography.headlineMedium)

        Text("Mode", style = MaterialTheme.typography.titleMedium)
        RecordingMode.entries.forEach { mode ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    update { it.copy(selectedMode = mode.name) }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.selectedMode == mode.name,
                        onClick = { update { it.copy(selectedMode = mode.name) } },
                    )
                    Column {
                        Text(mode.label, style = MaterialTheme.typography.titleMedium)
                        if (mode == RecordingMode.CUSTOM_REGION) {
                            Text(
                                "Primary mode — draw any rectangle",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (mode == RecordingMode.APP_WINDOW) {
                            Text(
                                "Uses Android's system capture picker where supported",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (settings.selectedMode == RecordingMode.FIXED_ASPECT.name) {
            Text("Aspect ratio", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectPreset.entries.filter { it.ratio != null }.forEach { preset ->
                    Card(
                        modifier = Modifier.clickable {
                            update { it.copy(aspectRatio = preset.ratio) }
                        },
                    ) {
                        Text(
                            preset.label,
                            modifier = Modifier.padding(12.dp),
                            color = if (settings.aspectRatio == preset.ratio) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        Text("Quality", style = MaterialTheme.typography.titleMedium)
        QualityPreset.entries.forEach { preset ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    update {
                        it.copy(
                            resolutionPreset = preset.name,
                            frameRate = preset.fps,
                            bitrate = preset.bitrate,
                        )
                    }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.resolutionPreset == preset.name,
                        onClick = {
                            update {
                                it.copy(
                                    resolutionPreset = preset.name,
                                    frameRate = preset.fps,
                                    bitrate = preset.bitrate,
                                )
                            }
                        },
                    )
                    Text(preset.label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Text("Audio", style = MaterialTheme.typography.titleMedium)
        AudioMode.entries.forEach { mode ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    update { it.copy(audioMode = mode.name) }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.audioMode == mode.name,
                        onClick = { update { it.copy(audioMode = mode.name) } },
                    )
                    Column {
                        Text(mode.label, style = MaterialTheme.typography.titleMedium)
                        if (mode == AudioMode.INTERNAL) {
                            Text(
                                "Android 10+ only, where the app allows it",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onContinueToRegion, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (settings.selectedMode == RecordingMode.FULL_SCREEN.name) {
                    "Continue"
                } else {
                    "Select Region"
                },
            )
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
