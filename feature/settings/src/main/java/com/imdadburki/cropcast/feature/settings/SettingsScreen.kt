package com.imdadburki.cropcast.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imdadburki.cropcast.core.database.RecordingDao
import com.imdadburki.cropcast.core.datastore.SettingsStore
import com.imdadburki.cropcast.core.common.ThemeMode
import kotlinx.coroutines.launch

/**
 * Settings (spec §4, Screen 12). Appearance / Storage / Privacy / About.
 * Every toggle writes to DataStore; storage info comes from the real Room DB.
 */
@Composable
fun SettingsScreen(
    store: SettingsStore,
    recordingDao: RecordingDao,
    appVersion: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val themeMode by store.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val haptics by store.hapticsEnabled.collectAsState(initial = true)
    val floating by store.floatingControlsEnabled.collectAsState(initial = true)
    val destination by store.saveDestination.collectAsState(initial = "movies")
    val recordings by recordingDao.getAll().collectAsState(initial = emptyList())

    val totalSizeMb = recordings.sumOf { it.fileSizeBytes } / 1_048_576.0

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        Switch(
                            checked = themeMode == mode,
                            onCheckedChange = { if (it) scope.launch { store.setThemeMode(mode) } },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Haptic feedback")
                    Switch(checked = haptics, onCheckedChange = { scope.launch { store.setHaptics(it) } })
                }
            }
        }

        Text("Recording", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Floating controller", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Small draggable control during recording",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = floating, onCheckedChange = { scope.launch { store.setFloatingControls(it) } })
            }
        }

        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save destination: ${if (destination == "movies") "Movies (shared)" else "App-private"}")
                Text("${recordings.size} recordings · %.1f MB used".format(totalSizeMb))
                Text(
                    "Temporary processing files are deleted after a successful export.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "CROPCAST records only when you start a recording, only after Android asks " +
                    "for screen-capture permission, and stores everything locally. Nothing is " +
                    "uploaded and no screen content is ever sent anywhere.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text("About", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CROPCAST $appVersion", style = MaterialTheme.typography.titleMedium)
                Text("Selective Screen Recording Studio", style = MaterialTheme.typography.bodyMedium)
                Text("Open source (MIT) — github.com/imdad-burki/cropcast-android", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
