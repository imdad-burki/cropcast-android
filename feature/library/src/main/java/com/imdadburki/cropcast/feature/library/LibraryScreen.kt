package com.imdadburki.cropcast.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imdadburki.cropcast.core.database.RecordingDao
import com.imdadburki.cropcast.core.database.RecordingEntity

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Video Library (spec §4, Screen 09). Real Room-backed list; search, sort
 * and multi-select land with the recordings in Phase 6. Until the first
 * recording exists the list is honestly empty.
 */
@Composable
fun LibraryScreen(
    dao: RecordingDao,
    onOpenRecording: (Long) -> Unit,
) {
    val recordings by dao.getAll().collectAsState(initial = emptyList())
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Library", style = MaterialTheme.typography.headlineMedium) }
        if (recordings.isEmpty()) {
            item {
                Text(
                    "No recordings yet. Finish your first recording and it will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(recordings) { rec: RecordingEntity ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onOpenRecording(rec.id)
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(rec.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${rec.width}×${rec.height} · ${formatDuration(rec.durationMs)} · ${formatSize(rec.fileSizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(rec.recordingStatus, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
