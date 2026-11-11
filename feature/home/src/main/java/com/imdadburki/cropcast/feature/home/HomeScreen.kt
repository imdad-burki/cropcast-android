package com.imdadburki.cropcast.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imdadburki.cropcast.core.database.RecordingDao
import com.imdadburki.cropcast.core.database.RecordingEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeStats(
    val totalRecordings: Int = 0,
    val totalDurationMs: Long = 0,
    val totalSizeBytes: Long = 0,
)

class HomeViewModel(private val dao: RecordingDao) : ViewModel() {
    val recent: StateFlow<List<RecordingEntity>> =
        dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<HomeStats> = combine(
        dao.getAll(),
        dao.getAll(),
        dao.getAll(),
    ) { list, _, _ ->
        HomeStats(
            totalRecordings = list.size,
            totalDurationMs = list.sumOf { it.durationMs },
            totalSizeBytes = list.sumOf { it.fileSizeBytes },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats())
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Home dashboard (spec §4, Screen 03). All statistics come from the local
 * Room database — never fabricated. The record button opens Recording Setup.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRecording: (Long) -> Unit,
) {
    val stats by viewModel.stats.collectAsState()
    val recent by viewModel.recent.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CROPCAST", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = onStartRecording,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.size(96.dp),
                    ) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Start Recording", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose an area of your screen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${stats.totalRecordings} recordings", style = MaterialTheme.typography.titleMedium)
                Text(formatDuration(stats.totalDurationMs), style = MaterialTheme.typography.titleMedium)
                Text(formatSize(stats.totalSizeBytes), style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent recordings", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onOpenLibrary) {
                    Icon(Icons.Filled.VideoLibrary, contentDescription = "Open library")
                }
            }
        }
        if (recent.isEmpty()) {
            item {
                Text(
                    "No recordings yet. Your finished recordings will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(recent.take(5)) { rec ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(rec.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${rec.width}×${rec.height} · ${formatDuration(rec.durationMs)} · ${formatSize(rec.fileSizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { onOpenRecording(rec.id) }) { Text("Open") }
                    }
                }
            }
        }
    }
}
