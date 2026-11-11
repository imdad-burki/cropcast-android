package com.imdadburki.cropcast.media.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import com.imdadburki.cropcast.media.encoder.MuxerSession

/**
 * AAC audio encoder (Phase 5, spec §3, §7).
 *
 * Two sources (spec §3.3):
 * - [Source.MICROPHONE]: device mic via AudioRecord (API 26+). Needs the
 *   RECORD_AUDIO runtime permission; the caller checks that before starting.
 * - [Source.INTERNAL]: the audio of the apps being recorded, via
 *   AudioPlaybackCaptureConfiguration (API 29+). Needs the same
 *   MediaProjection the video capture uses.
 *
 * Audio presentation timestamps are sample-count based, starting at 0, so
 * they line up with the video track (whose first frame is also rebased to 0).
 *
 * Threading: the caller owns start/stop; a record thread feeds PCM into the
 * codec and a drain thread moves codec output into the [MuxerSession].
 */
class AudioEncoder(
    private val source: Source,
    private val projection: MediaProjection? = null,
    private val sampleRate: Int = 44_100,
    private val bitrate: Int = 128_000,
    private val onError: (Throwable) -> Unit = {},
) {
    enum class Source { MICROPHONE, INTERNAL }

    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var trackIndex = -1

    @Volatile
    private var recording = false
    private var recordThread: Thread? = null
    private var drainThread: Thread? = null

    /** Samples read so far — drives the presentation timestamps. */
    private var samplesRead = 0L
    private var samplesEncoded = 0L
    private var lastError: Throwable? = null

    val encodedSamples: Long get() = samplesEncoded

    /**
     * True when the audio source never delivered a single usable sample
     * (e.g. a cloud emulator with no microphone: read() keeps failing).
     * The caller uses it to report the recording honestly as silent.
     */
    @Volatile
    var audioFailed = false
        private set

    /**
     * Start capturing. Throws [IllegalStateException] when the source cannot
     * be opened (no mic permission, internal capture below API 29, …) — the
     * caller treats that as "audio unavailable, record video only".
     */
    fun start(muxerSession: MuxerSession) {
        check(codec == null) { "AudioEncoder already started" }
        val record = createAudioRecord()
        val state = record.state
        if (state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord not initialized (state=$state)")
        }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        audioRecord = record
        record.startRecording()
        recording = true
        drainThread = Thread({ drainLoop(muxerSession) }, "cropcast-audio-drain").apply { start() }
        recordThread = Thread({ recordLoop() }, "cropcast-audio-record").apply { start() }
    }

    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8192)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return when (source) {
            Source.MICROPHONE -> AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
            Source.INTERNAL -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projection == null) {
                    throw IllegalStateException(
                        "Internal audio needs Android 10+ and a MediaProjection",
                    )
                }
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
            }
        }
    }

    /** Stop capturing and flush the codec. Returns true when audio was written. */
    fun stop(): Boolean {
        val c = codec ?: return false
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            recordThread?.join(2000)
        } catch (_: InterruptedException) {
        }
        recordThread = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        val inputBuffers = try {
            c.inputBuffers
        } catch (_: Exception) {
            null
        }
        if (inputBuffers != null) {
            try {
                val index = c.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    c.queueInputBuffer(
                        index, 0, 0,
                        (samplesRead * 1_000_000L) / sampleRate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                }
            } catch (_: Exception) {
            }
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
        return samplesEncoded > 0 && lastError == null
    }

    fun release() {
        if (codec != null) stop()
    }

    private fun recordLoop() {
        val c = codec ?: return
        val record = audioRecord ?: return
        // 1024 samples = one AAC frame (~23 ms at 44.1 kHz) and 2048 bytes —
        // small enough for any AAC encoder input buffer.
        val pcm = ShortArray(1024)
        var consecutiveErrors = 0
        try {
            while (recording && !audioFailed) {
                val read = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    // No mic on this device (cloud emulator) or a read error:
                    // spinning here would feed the encoder nothing forever.
                    // After enough consecutive failures, give up on audio
                    // instead of writing an empty track.
                    if (++consecutiveErrors >= 100) {
                        audioFailed = true
                        lastError = IllegalStateException(
                            "AudioRecord.read failed $consecutiveErrors times in a row (last=$read)",
                        )
                        onError(lastError!!)
                    }
                    continue
                }
                consecutiveErrors = 0
                val index = c.dequeueInputBuffer(10_000)
                if (index < 0) continue
                val buffer = c.getInputBuffer(index) ?: continue
                buffer.clear()
                // Never write more than the codec's input buffer holds —
                // overflowing it throws and kills this thread silently.
                val writable = minOf(read, buffer.remaining() / 2)
                if (writable <= 0) continue
                buffer.asShortBuffer().put(pcm, 0, writable)
                val ptsUs = (samplesRead * 1_000_000L) / sampleRate
                samplesRead += writable
                c.queueInputBuffer(index, 0, writable * 2, ptsUs, 0)
            }
        } catch (t: Throwable) {
            audioFailed = true
            lastError = t
            onError(t)
        }
    }

    private fun drainLoop(muxerSession: MuxerSession) {
        val c = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (recording || true) {
                val index = c.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxerSession.registerAudioTrack(c.outputFormat)
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!recording) break
                    }
                    index >= 0 -> {
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null && bufferInfo.size > 0 && muxerSession.isStarted) {
                            muxerSession.writeSampleData(trackIndex, buffer, bufferInfo)
                            samplesEncoded += bufferInfo.size
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
