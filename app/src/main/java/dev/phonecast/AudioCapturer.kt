package dev.phonecast

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.annotation.TargetApi
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Captures the sound other apps are playing (Android 10+), as raw 48 kHz stereo PCM.
 * Raw PCM is ~1.5 Mbit/s — trivial on Wi-Fi and avoids codec latency.
 */
@TargetApi(29)
@SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
class AudioCapturer(projection: MediaProjection, private val sink: (Int, Long, ByteArray) -> Unit) {

    private val record: AudioRecord
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuf * 2, CHUNK * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord could not be initialised")
        }
    }

    fun start() {
        record.startRecording()
        resendHeader()
        running = true
        thread = Thread({
            val buf = ByteArray(CHUNK)
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) sink(Protocol.AUDIO_DATA, System.nanoTime() / 1000, buf.copyOf(n))
                else if (n < 0) break
            }
        }, "pc-audio").also { it.start() }
    }

    fun resendHeader() {
        sink(Protocol.AUDIO_HEADER, 0, ByteBuffer.allocate(8).putInt(SAMPLE_RATE).putInt(CHANNELS).array())
    }

    fun stop() {
        running = false
        try { record.stop() } catch (_: Exception) {}
        try { thread?.join(500) } catch (_: InterruptedException) {}
        record.release()
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK = SAMPLE_RATE * CHANNELS * 2 / 50 // 20 ms
    }
}
