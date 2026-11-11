package com.imdadburki.cropcast.service.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.imdadburki.cropcast.core.common.NormalizedRect
import com.imdadburki.cropcast.core.common.recordingFileName
import com.imdadburki.cropcast.core.database.DatabaseProvider
import com.imdadburki.cropcast.core.database.RecordingEntity
import com.imdadburki.cropcast.core.media.RecordingState
import com.imdadburki.cropcast.core.media.RecordingStateManager
import com.imdadburki.cropcast.media.audio.AudioEncoder
import com.imdadburki.cropcast.media.encoder.MuxerSession
import com.imdadburki.cropcast.media.encoder.VideoEncoder
import com.imdadburki.cropcast.media.renderer.CropRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Recording foreground service (spec §3, §13) — Phase 3 engine.
 *
 * Pipeline: MediaProjection (created HERE in the service, as Android 14
 * requires) → VirtualDisplay at native display size → SurfaceTexture →
 * [CropRenderer] (EGL crop to the selected region only) → [VideoEncoder]
 * (H.264 → MediaMuxer MP4). No full-screen intermediate is ever saved.
 *
 * Phase 3 records VIDEO ONLY. Audio modes (mic / internal) land in Phase 5;
 * until then recordings honestly store audioMode "NONE".
 *
 * Phase 5: audio is captured by [AudioEncoder] (mic or internal, API 29+)
 * into the same file through a shared [MuxerSession]. If audio cannot start
 * (no mic permission, internal capture unsupported), the muxer stops waiting
 * for it and the recording continues as silent video — never a failure.
 *
 * Lifecycle: the activity requests MediaProjection consent, then starts this
 * service with the grant (resultCode + data). Pause freezes the muxed
 * timeline (no gap). Stop finalizes the file, inserts the Room row and, for
 * the "movies" destination, publishes it to MediaStore.
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "cropcast_recording"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.imdadburki.cropcast.action.START"
        const val ACTION_PAUSE = "com.imdadburki.cropcast.action.PAUSE"
        const val ACTION_RESUME = "com.imdadburki.cropcast.action.RESUME"
        const val ACTION_STOP = "com.imdadburki.cropcast.action.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CROP_LEFT = "crop_left"
        const val EXTRA_CROP_TOP = "crop_top"
        const val EXTRA_CROP_WIDTH = "crop_width"
        const val EXTRA_CROP_HEIGHT = "crop_height"
        const val EXTRA_OUT_WIDTH = "out_width"
        const val EXTRA_OUT_HEIGHT = "out_height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_AUDIO_MODE = "audio_mode" // "NONE" | "MICROPHONE" | "INTERNAL"
    }

    private val stateManager = RecordingStateManager()
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var renderer: CropRenderer? = null
    private var encoder: VideoEncoder? = null
    private var muxerSession: MuxerSession? = null
    private var audioEncoder: AudioEncoder? = null
    /** The audio mode that actually made it into the file (for the Library row). */
    private var audioModeActual = "NONE"

    /**
     * Android REQUIRES a callback registered on the MediaProjection before
     * createVirtualDisplay is called (IllegalStateException otherwise —
     * caught by the VibeView device test, 2026-11-10). The callback also
     * tells us when the system revokes the projection, so the session can
     * stop cleanly instead of hanging.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Runs on the main thread; do the teardown off it (encoder.stop
            // blocks while the drain thread finishes).
            serviceScope.launch {
                if (stateManager.isActive()) stopRecording()
            }
        }
    }

    private var outputTarget: OutputTarget? = null
    private var currentCrop = NormalizedRect(0f, 0f, 1f, 1f)
    private var outWidth = 0
    private var outHeight = 0
    private var outFps = 30
    private var sessionStartRealtime = 0L
    private var pausedTotalMs = 0L
    private var pauseStartRealtime = 0L
    private var tickerRunning = false

    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun service(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTicker()
        teardownEngine()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- Public controls (UI + floating controller) -------------------------

    fun pauseRecording() {
        if (!stateManager.canPause()) return
        setState(RecordingState.PAUSED)
        pauseStartRealtime = SystemClock.elapsedRealtime()
        renderer?.setPaused(true)
        updateNotification()
    }

    fun resumeRecording() {
        if (!stateManager.canResume()) return
        pausedTotalMs += SystemClock.elapsedRealtime() - pauseStartRealtime
        pauseStartRealtime = 0L
        setState(RecordingState.RECORDING)
        renderer?.setPaused(false)
        updateNotification()
    }

    // ---- Start ---------------------------------------------------------------

    private fun startRecording(intent: Intent) {
        if (stateManager.state != RecordingState.IDLE) return
        setState(RecordingState.REQUESTING_PERMISSION)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        currentCrop = NormalizedRect(
            intent.getFloatExtra(EXTRA_CROP_LEFT, 0f),
            intent.getFloatExtra(EXTRA_CROP_TOP, 0f),
            intent.getFloatExtra(EXTRA_CROP_WIDTH, 1f),
            intent.getFloatExtra(EXTRA_CROP_HEIGHT, 1f),
        ).clamped()
        val presetW = intent.getIntExtra(EXTRA_OUT_WIDTH, 1280)
        val presetH = intent.getIntExtra(EXTRA_OUT_HEIGHT, 720)
        outFps = intent.getIntExtra(EXTRA_FPS, 30)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 6_000_000)
        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: "movies"
        val audioMode = intent.getStringExtra(EXTRA_AUDIO_MODE) ?: "NONE"

        if (resultData == null) {
            failRecording()
            return
        }

        // Native display size: capture full-res, crop in EGL.
        val metrics = resources.displayMetrics
        val displayW = metrics.widthPixels
        val displayH = metrics.heightPixels
        val dpi = metrics.densityDpi

        // Output size: crop pixels, scaled down to the quality preset cap.
        val cropPx = currentCrop.toPixelRect(displayW, displayH)
        val scale = minOf(
            1f,
            presetW.toFloat() / cropPx.width,
            presetH.toFloat() / cropPx.height,
        )
        outWidth = ((cropPx.width * scale).toInt() and 1.inv()).coerceAtLeast(2)
        outHeight = ((cropPx.height * scale).toInt() and 1.inv()).coerceAtLeast(2)

        setState(RecordingState.INITIALIZING)
        try {
            startForegroundWithType(recordingNotification(0L))
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("getMediaProjection returned null")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val target = openOutputTarget(destination)
            outputTarget = target
            val session = MuxerSession(target.fd, expectAudio = audioMode != "NONE")
            muxerSession = session
            val enc = VideoEncoder(outWidth, outHeight, outFps, bitrate, session) {
                failRecording()
            }
            encoder = enc
            enc.start()

            // Audio (Phase 5): start the audio encoder into the same file.
            // Any failure falls back to silent video — the muxer must not
            // wait forever for a track that will never arrive.
            audioModeActual = "NONE"
            if (audioMode != "NONE") {
                try {
                    val audioEnc = when (audioMode) {
                        "MICROPHONE" -> {
                            if (ContextCompat.checkSelfPermission(
                                    this, Manifest.permission.RECORD_AUDIO,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                throw IllegalStateException("RECORD_AUDIO not granted")
                            }
                            AudioEncoder(AudioEncoder.Source.MICROPHONE)
                        }
                        "INTERNAL" -> AudioEncoder(AudioEncoder.Source.INTERNAL, projection)
                        else -> null
                    }
                    if (audioEnc != null) {
                        audioEnc.start(session)
                        audioEncoder = audioEnc
                        audioModeActual = audioMode
                    } else {
                        session.dropAudioExpectation()
                    }
                } catch (_: Throwable) {
                    session.dropAudioExpectation()
                }
            }

            val rend = CropRenderer(outWidth, outHeight, currentCrop, displayW, displayH)
            renderer = rend
            rend.start(enc.surface())

            val vd = projection.createVirtualDisplay(
                "CROPCAST",
                displayW,
                displayH,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rend.inputSurface,
                null,
                null,
            )
            virtualDisplay = vd

            sessionStartRealtime = SystemClock.elapsedRealtime()
            pausedTotalMs = 0L
            setState(RecordingState.RECORDING)
            startTicker()
            updateNotification()
        } catch (t: Throwable) {
            failRecording()
        }
    }

    // ---- Stop ------------------------------------------------------------------

    fun stopRecording() {
        if (!stateManager.canStop()) {
            if (stateManager.state == RecordingState.IDLE) stopSelf()
            return
        }
        setState(RecordingState.STOPPING)
        stopTicker()
        // Audio first (its drain must finish before the muxer closes),
        // then video. The muxer session itself is stopped in teardownEngine.
        // If audio never delivered a sample (e.g. no mic on this device),
        // the recording is honestly silent — the Library row says NONE even
        // though Microphone was requested.
        val audioEnc = audioEncoder
        try {
            audioEnc?.stop()
        } catch (_: Exception) {
        }
        if (audioEnc != null && (audioEnc.audioFailed || audioEnc.encodedSamples == 0L)) {
            audioModeActual = "NONE"
        }
        val enc = encoder
        val ok = try {
            enc?.stop() == true
        } catch (_: Exception) {
            false
        }
        teardownEngine()
        val target = outputTarget
        outputTarget = null
        val duration = currentElapsedMs()
        if (ok && target != null) {
            serviceScope.launch {
                try {
                    val fileSize = target.finishAndSize()
                    val uri = target.contentUri?.toString()
                        ?: target.file?.absolutePath.orEmpty()
                    val db = DatabaseProvider.get(applicationContext)
                    db.recordingDao().insert(
                        RecordingEntity(
                            fileUri = uri,
                            title = target.fileName,
                            createdAt = System.currentTimeMillis(),
                            durationMs = duration,
                            width = outWidth,
                            height = outHeight,
                            frameRate = outFps,
                            videoCodec = "H.264",
                            audioMode = audioModeActual,
                            fileSizeBytes = fileSize,
                            thumbnailUri = null,
                            recordingStatus = "ready",
                        ),
                    )
                    setState(RecordingState.COMPLETED)
                } catch (_: Throwable) {
                    target.discard()
                    setState(RecordingState.FAILED)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            target?.discard()
            setState(if (ok) RecordingState.IDLE else RecordingState.FAILED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun failRecording() {
        teardownEngine()
        outputTarget?.discard()
        outputTarget = null
        if (stateManager.state != RecordingState.IDLE) {
            if (stateManager.transitionTo(RecordingState.FAILED)) {
                _state.value = RecordingState.FAILED
            }
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun teardownEngine() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            renderer?.release()
        } catch (_: Exception) {
        }
        renderer = null
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        audioEncoder = null
        try {
            muxerSession?.stop()
        } catch (_: Exception) {
        }
        muxerSession = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    // ---- Output destination -----------------------------------------------------

    private class OutputTarget(
        val fd: java.io.FileDescriptor,
        val fileName: String,
        val contentUri: Uri?,
        val file: File?,
        private val pfd: ParcelFileDescriptor?,
        private val stream: FileOutputStream?,
        private val context: Context,
    ) {
        /** Close writers, publish (MediaStore) and return the final size. */
        fun finishAndSize(): Long {
            try {
                stream?.flush()
                stream?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            if (contentUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                try {
                    context.contentResolver.update(contentUri, values, null, null)
                } catch (_: Exception) {
                }
                return querySize(context, contentUri)
            }
            return file?.length() ?: 0L
        }

        fun discard() {
            try {
                stream?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            if (contentUri != null) {
                try {
                    context.contentResolver.delete(contentUri, null, null)
                } catch (_: Exception) {
                }
            } else {
                try {
                    file?.delete()
                } catch (_: Exception) {
                }
            }
        }

        private fun querySize(context: Context, uri: Uri): Long {
            return try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Video.Media.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    private fun openOutputTarget(destination: String): OutputTarget {
        val now = Calendar.getInstance()
        val name = recordingFileName(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            now.get(Calendar.SECOND),
        )
        if (destination == "movies" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CROPCAST")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open MediaStore file")
            return OutputTarget(pfd.fileDescriptor, name, uri, null, pfd, null, this)
        }
        // API 26–28, or the private destination: a plain file, no MediaStore.
        val dir = if (destination == "private") {
            File(filesDir, "CROPCAST").apply { mkdirs() }
        } else {
            File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "CROPCAST",
            ).apply { mkdirs() }
        }
        val file = File(dir, name)
        val stream = FileOutputStream(file)
        return OutputTarget(stream.fd, name, null, file, null, stream, this)
    }

    // ---- State + notification ---------------------------------------------------

    private fun setState(next: RecordingState) {
        if (stateManager.transitionTo(next)) {
            _state.value = next
        }
    }

    private fun currentElapsedMs(): Long {
        if (sessionStartRealtime == 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val paused = pausedTotalMs + if (stateManager.state == RecordingState.PAUSED) {
            now - pauseStartRealtime
        } else {
            0L
        }
        return (now - sessionStartRealtime - paused).coerceAtLeast(0L)
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!tickerRunning) return
                _elapsedMs.value = currentElapsedMs()
                updateNotification()
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, recordingNotification(currentElapsedMs()))
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while CROPCAST is recording your screen."
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /** Ongoing notification shown while a session is active (spec §6). */
    private fun recordingNotification(elapsedMs: Long): Notification {
        val minutes = elapsedMs / 60_000
        val seconds = (elapsedMs / 1000) % 60
        val time = "%02d:%02d".format(minutes, seconds)
        val isPaused = stateManager.state == RecordingState.PAUSED
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (isPaused) "CROPCAST paused" else "CROPCAST is recording")
            .setContentText("Region ${outWidth}x$outHeight · $time · tap to open")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseIntent,
            )
            .addAction(android.R.drawable.ic_media_play, "Stop", stopIntent)
            .build()
    }
}
