package com.imdadburki.cropcast.feature.recording

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.imdadburki.cropcast.core.common.AudioMode
import com.imdadburki.cropcast.core.common.NormalizedRect
import com.imdadburki.cropcast.core.common.QualityPreset
import com.imdadburki.cropcast.core.common.RecordingMode
import com.imdadburki.cropcast.core.database.RecordingSettingsDao
import com.imdadburki.cropcast.core.database.RecordingSettingsEntity
import com.imdadburki.cropcast.core.datastore.SettingsStore
import com.imdadburki.cropcast.core.media.RecordingState
import com.imdadburki.cropcast.service.recording.RecordingService
import kotlinx.coroutines.launch

/**
 * Recording Session (spec §4, Screen 07) — Phase 3 engine, Phase 5 audio.
 *
 * Two phases in one screen: (1) ready — shows the region/quality summary and
 * asks Android for screen-capture consent; (2) live — bound to the foreground
 * service, shows the running timer and Pause/Resume/Stop. On COMPLETED it
 * shows the saved result and a way into the Library.
 *
 * Audio (Phase 5): mic and internal audio are captured into the same file.
 * If the mic permission is denied, the recording still happens — as silent
 * video — and the screen says so.
 */
@Composable
fun RecordingSessionScreen(
    settingsDao: RecordingSettingsDao,
    settingsStore: SettingsStore,
    onOpenLibrary: () -> Unit,
    onBackHome: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<RecordingSettingsEntity?>(null) }
    var destination by remember { mutableStateOf("movies") }
    var consentDenied by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    var service by remember { mutableStateOf<RecordingService?>(null) }

    LaunchedEffect(Unit) {
        settings = settingsDao.get()
        settingsStore.saveDestination.collect { destination = it }
    }

    // Bind to the service whenever it is running (survives rotation/re-entry).
    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? RecordingService.LocalBinder)?.service()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val bound = try {
            context.bindService(
                Intent(context, RecordingService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (_: Exception) {
            false
        }
        onDispose {
            if (bound) {
                try {
                    context.unbindService(connection)
                } catch (_: Exception) {
                }
            }
        }
    }

    // Mirror the bound service's flows into local state (never collect
    // conditionally — that breaks Compose's call order when binding lands).
    var liveState by remember { mutableStateOf(RecordingState.IDLE) }
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(service) {
        service?.state?.collect { liveState = it }
    }
    LaunchedEffect(service) {
        service?.elapsedMs?.collect { elapsedMs = it }
    }

    // Screen-capture consent (MediaProjection): Android asks fresh every
    // session. Declared FIRST — the mic launcher and the Start button call it.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            consentDenied = false
            startServiceWithGrant(context, settings, destination, result.resultCode, result.data!!)
            // Re-bind now that the service is starting.
            scope.launch {
                try {
                    context.bindService(
                        Intent(context, RecordingService::class.java),
                        object : ServiceConnection {
                            override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
                                service = (b as? RecordingService.LocalBinder)?.service()
                            }

                            override fun onServiceDisconnected(n: ComponentName?) {
                                service = null
                            }
                        },
                        Context.BIND_AUTO_CREATE,
                    )
                } catch (_: Exception) {
                }
            }
        } else {
            consentDenied = true
        }
    }

    // Mic permission (Phase 5): asked when the user picks Microphone audio.
    // Either way the screen-capture consent follows — a denied mic just
    // means the recording is silent video, never a blocked recording.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micDenied = !granted
        val manager = context.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE,
        ) as MediaProjectionManager
        consentLauncher.launch(manager.createScreenCaptureIntent())
    }

    val launchCaptureConsent: () -> Unit = {
        val audio = settings?.audioMode?.let {
            try {
                AudioMode.valueOf(it)
            } catch (_: Exception) {
                AudioMode.NONE
            }
        } ?: AudioMode.NONE
        if (audio == AudioMode.MICROPHONE &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val manager = context.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE,
            ) as MediaProjectionManager
            consentLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    val isLive = liveState == RecordingState.RECORDING || liveState == RecordingState.PAUSED ||
        liveState == RecordingState.INITIALIZING || liveState == RecordingState.REQUESTING_PERMISSION
    val isDone = liveState == RecordingState.COMPLETED
    val isFailed = liveState == RecordingState.FAILED

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when {
                isDone -> "Recording saved"
                isFailed -> "Recording failed"
                isLive -> "Recording"
                else -> "Ready to record"
            },
            style = MaterialTheme.typography.headlineMedium,
        )

        when {
            isDone -> {
                Text(
                    "Your cropped recording is saved. Find it in the Library.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                    Text("View in Library")
                }
                OutlinedButton(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to Home")
                }
            }
            isFailed -> {
                Text(
                    "The recording could not be started or was interrupted. " +
                        "Try again — check the screen-capture permission and storage space.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to Home")
                }
            }
            isLive -> {
                val minutes = elapsedMs / 60_000
                val seconds = (elapsedMs / 1000) % 60
                Text(
                    "%02d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    if (liveState == RecordingState.PAUSED) "Paused — timeline frozen, no gap"
                    else "Recording your selected region",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val s = settings
                        Text(
                            "Region: ${s?.let { regionSummary(it) } ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Quality: ${s?.resolutionPreset ?: "—"} · ${s?.frameRate ?: 30} fps",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Audio: ${settings?.audioMode ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (liveState == RecordingState.PAUSED) {
                    Button(
                        onClick = { service?.resumeRecording() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Resume") }
                } else if (liveState == RecordingState.RECORDING) {
                    Button(
                        onClick = { service?.pauseRecording() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pause") }
                }
                Button(
                    onClick = { service?.stopRecording() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop & Save") }
                Text(
                    "You can leave the app — the recording keeps going. " +
                        "Use the notification to pause or stop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val s = settings
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Region: ${s?.let { regionSummary(it) } ?: "loading…"}")
                        Text("Quality: ${s?.resolutionPreset ?: "—"} · ${s?.frameRate ?: 30} fps")
                        Text("Save to: ${if (destination == "movies") "Movies / CROPCAST" else "Private (in-app)"}")
                        val audio = s?.audioMode?.let { AudioMode.valueOf(it) } ?: AudioMode.NONE
                        Text(
                            when (audio) {
                                AudioMode.NONE ->
                                    "Audio: none — this recording will be silent."
                                AudioMode.MICROPHONE ->
                                    "Audio: microphone — your voice will be recorded."
                                AudioMode.INTERNAL ->
                                    "Audio: internal — the audio of the apps you record " +
                                        "will be captured (Android 10+)."
                            },
                            color = if (audio == AudioMode.NONE) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                if (consentDenied) {
                    Text(
                        "Screen-capture permission was denied. Tap Start again to allow it — " +
                            "Android asks every time, for your privacy.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (micDenied) {
                    Text(
                        "Microphone permission was denied — this recording will be " +
                            "silent. You can allow it in the app's Settings.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { launchCaptureConsent() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = s != null,
                ) { Text("Start recording") }
                OutlinedButton(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
                Text(
                    "Android will ask for screen-capture permission. CROPCAST records " +
                        "only the region you selected — nothing outside it is saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun regionSummary(s: RecordingSettingsEntity): String {
    return if (s.selectedMode == RecordingMode.FULL_SCREEN.name ||
        s.selectedMode == RecordingMode.APP_WINDOW.name
    ) {
        "Full screen"
    } else {
        val rect = NormalizedRect(s.regionLeft, s.regionTop, s.regionWidth, s.regionHeight)
        "%.0f%% × %.0f%% of screen".format(rect.width * 100, rect.height * 100)
    }
}

private fun startServiceWithGrant(
    context: Context,
    settings: RecordingSettingsEntity?,
    destination: String,
    resultCode: Int,
    resultData: Intent,
) {
    val s = settings ?: return
    val crop = if (s.selectedMode == RecordingMode.FULL_SCREEN.name ||
        s.selectedMode == RecordingMode.APP_WINDOW.name
    ) {
        NormalizedRect(0f, 0f, 1f, 1f)
    } else {
        NormalizedRect(s.regionLeft, s.regionTop, s.regionWidth, s.regionHeight).clamped()
    }
    val preset = try {
        QualityPreset.valueOf(s.resolutionPreset)
    } catch (_: Exception) {
        QualityPreset.HIGH
    }
    val intent = Intent(context, RecordingService::class.java).apply {
        action = RecordingService.ACTION_START
        putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
        putExtra(RecordingService.EXTRA_RESULT_DATA, resultData)
        putExtra(RecordingService.EXTRA_CROP_LEFT, crop.left)
        putExtra(RecordingService.EXTRA_CROP_TOP, crop.top)
        putExtra(RecordingService.EXTRA_CROP_WIDTH, crop.width)
        putExtra(RecordingService.EXTRA_CROP_HEIGHT, crop.height)
        putExtra(RecordingService.EXTRA_OUT_WIDTH, preset.width)
        putExtra(RecordingService.EXTRA_OUT_HEIGHT, preset.height)
        putExtra(RecordingService.EXTRA_FPS, s.frameRate)
        putExtra(RecordingService.EXTRA_BITRATE, s.bitrate)
        putExtra(RecordingService.EXTRA_DESTINATION, destination)
        putExtra(RecordingService.EXTRA_AUDIO_MODE, s.audioMode)
    }
    ContextCompat.startForegroundService(context, intent)
}
