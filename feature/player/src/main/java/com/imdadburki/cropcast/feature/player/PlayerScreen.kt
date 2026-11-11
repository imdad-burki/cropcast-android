package com.imdadburki.cropcast.feature.player

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.imdadburki.cropcast.core.database.RecordingDao
import com.imdadburki.cropcast.core.database.RecordingEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Recording Preview / Player (spec §4, Screen 10; §9) — Phase 6.
 *
 * Plays the recording with Media3 ExoPlayer (built-in controller: play/pause,
 * seek, fullscreen). Share sends the file via a content URI; Delete removes
 * the file and its Library row (with confirmation); Rename changes the title
 * shown in the Library.
 */
@Composable
fun PlayerScreen(
    recordingId: Long,
    dao: RecordingDao,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(recordingId) {
        recording = dao.getById(recordingId)
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(recording) {
        val uri = recording?.fileUri?.let { Uri.parse(it) } ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    val rec = recording
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (rec == null) {
            Text("Loading recording…")
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
            return@Column
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(rec.title)
                Text(
                    "${rec.width}×${rec.height} · ${rec.frameRate} fps · " +
                        "${formatDuration(rec.durationMs)} · ${formatSize(rec.fileSizeBytes)}",
                )
                Text("Recorded ${formatDate(rec.createdAt)} · Audio: ${rec.audioMode}")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { shareRecording(context, rec) },
                modifier = Modifier.weight(1f),
            ) { Text("Share") }
            OutlinedButton(
                onClick = {
                    renameText = rec.title
                    showRenameDialog = true
                },
                modifier = Modifier.weight(1f),
            ) { Text("Rename") }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.weight(1f),
            ) { Text("Delete") }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to Library")
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete recording?") },
            text = { Text("This removes the video file and its Library entry. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    rec?.let { deleteRecording(context, it, dao, onDeleted) }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename recording") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty()) {
                        rec?.let { r ->
                            // Room update on a background thread via the DAO's suspend fn.
                            kotlinx.coroutines.MainScope().launch {
                                dao.update(r.copy(title = name))
                                recording = dao.getById(recordingId)
                            }
                        }
                    }
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun shareRecording(context: android.content.Context, rec: RecordingEntity) {
    val uri = shareableUri(context, rec.fileUri) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share recording"))
}

private fun shareableUri(context: android.content.Context, fileUri: String): Uri? {
    val uri = Uri.parse(fileUri)
    if (uri.scheme == "content") return uri
    val file = File(uri.path ?: return null)
    if (!file.exists()) return null
    return try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    } catch (_: Exception) {
        null
    }
}

private fun deleteRecording(
    context: android.content.Context,
    rec: RecordingEntity,
    dao: RecordingDao,
    onDeleted: () -> Unit,
) {
    kotlinx.coroutines.MainScope().launch {
        try {
            val uri = Uri.parse(rec.fileUri)
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
            } else {
                try {
                    File(uri.path ?: "").delete()
                } catch (_: Exception) {
                }
            }
            dao.deleteById(rec.id)
        } catch (_: Exception) {
        }
        onDeleted()
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000f)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000f)
        else -> "$bytes B"
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
