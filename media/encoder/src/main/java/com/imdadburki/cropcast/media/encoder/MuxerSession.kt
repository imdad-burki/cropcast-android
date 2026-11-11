package com.imdadburki.cropcast.media.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Shared MP4 muxer session (Phase 5, spec §3, §7).
 *
 * The muxer can only start once ALL expected tracks are registered, and no
 * track can be added after it starts. VideoEncoder and AudioEncoder each
 * register their track when their codec first reports its output format;
 * the session starts itself the moment the last expected track arrives.
 *
 * If an expected track never arrives (e.g. audio fails to start), the caller
 * must call [dropAudioExpectation] so the video track alone can start.
 *
 * Threading: all public methods are synchronized — the video drain thread
 * and the audio drain thread write concurrently.
 */
class MuxerSession(
    outputFd: FileDescriptor,
    private val expectAudio: Boolean,
) {
    private val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var audioDropped = false
    private var started = false

    val isStarted: Boolean get() = synchronized(this) { started }

    /** Register the video track; returns its muxer track index. */
    @Synchronized
    fun registerVideoTrack(format: MediaFormat): Int {
        if (videoTrackIndex >= 0) return videoTrackIndex
        videoTrackIndex = muxer.addTrack(format)
        startWhenReadyLocked()
        return videoTrackIndex
    }

    /** Register the audio track; returns its muxer track index. */
    @Synchronized
    fun registerAudioTrack(format: MediaFormat): Int {
        if (audioTrackIndex >= 0) return audioTrackIndex
        audioTrackIndex = muxer.addTrack(format)
        startWhenReadyLocked()
        return audioTrackIndex
    }

    /**
     * The audio encoder failed to start — stop waiting for it. If the video
     * track is already registered, the muxer starts now.
     */
    @Synchronized
    fun dropAudioExpectation() {
        audioDropped = true
        startWhenReadyLocked()
    }

    @Synchronized
    private fun startWhenReadyLocked() {
        if (started) return
        if (videoTrackIndex < 0) return
        if (expectAudio && !audioDropped && audioTrackIndex < 0) return
        muxer.start()
        started = true
    }

    /** Write one encoded sample. No-op until the muxer has started. */
    @Synchronized
    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || trackIndex < 0) return
        muxer.writeSampleData(trackIndex, buffer, info)
    }

    /** Stop and release the muxer. Safe to call even if it never started. */
    @Synchronized
    fun stop() {
        try {
            if (started) muxer.stop()
        } catch (_: Exception) {
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
        started = false
    }
}
