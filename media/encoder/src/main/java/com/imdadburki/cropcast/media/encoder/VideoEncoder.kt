package com.imdadburki.cropcast.media.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * H.264 video encoder (Phase 3, spec §3, §7; muxer shared via [MuxerSession]
 * since Phase 5 so an audio track can join the same file).
 *
 * The encoder takes frames from [inputSurface] (fed by [com.imdadburki.cropcast.media.renderer.CropRenderer]).
 * Presentation timestamps are rebased so the file starts at 0 — players never
 * see a long initial delay.
 *
 * Threading: the caller owns start/stop; a drain thread moves codec output
 * into the session. All public methods are safe to call from the service thread.
 */
class VideoEncoder(
    width: Int,
    height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val muxerSession: MuxerSession,
    private val onError: (Throwable) -> Unit = {},
) {
    /** H.264 needs even dimensions; align down to the nearest even value. */
    val width: Int = width - (width % 2)
    val height: Int = height - (height % 2)

    private var codec: MediaCodec? = null
    private var trackIndex = -1

    @Volatile
    private var draining = false
    private var drainThread: Thread? = null

    /** First frame's presentation time (us) — subtracted so the file starts at 0. */
    private var firstPresentationTimeUs = -1L

    private var framesEncoded = 0L
    private var lastError: Throwable? = null

    /** Number of frames handed to the muxer so far. */
    val encodedFrames: Long get() = framesEncoded

    fun start() {
        check(codec == null) { "VideoEncoder already started" }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // createInputSurface must be called after configure, before start.
        val surface = c.createInputSurface()
        c.start()
        codec = c
        // Keep the surface alive for the renderer's lifetime.
        encoderSurface = surface
        draining = true
        drainThread = Thread({ drainLoop() }, "cropcast-encoder-drain").apply { start() }
    }

    private var encoderSurface: Surface? = null

    /** The input surface the renderer draws into (same object as [inputSurface]). */
    fun surface(): Surface = encoderSurface
        ?: throw IllegalStateException("VideoEncoder not started")

    /**
     * Finish encoding. Returns true when at least one frame was written.
     * The [MuxerSession] itself is stopped by the caller, after the audio
     * encoder has also finished.
     */
    fun stop(): Boolean {
        val c = codec ?: return false
        draining = false
        try {
            c.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            drainThread?.join(5000)
        } catch (_: InterruptedException) {
        }
        drainThread = null
        try {
            c.stop()
        } catch (_: Exception) {
        }
        c.release()
        codec = null
        encoderSurface?.release()
        encoderSurface = null
        return framesEncoded > 0 && lastError == null
    }

    fun release() {
        if (codec != null) stop()
    }

    private fun drainLoop() {
        val c = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (draining || true) {
                val index = c.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxerSession.registerVideoTrack(c.outputFormat)
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!draining) break
                    }
                    index >= 0 -> {
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null && bufferInfo.size > 0 && muxerSession.isStarted) {
                            if (firstPresentationTimeUs < 0) {
                                firstPresentationTimeUs = bufferInfo.presentationTimeUs
                            }
                            bufferInfo.presentationTimeUs -= firstPresentationTimeUs
                            muxerSession.writeSampleData(trackIndex, buffer, bufferInfo)
                            framesEncoded++
                        }
                        c.releaseOutputBuffer(index, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            lastError = t
            onError(t)
        }
    }
}
