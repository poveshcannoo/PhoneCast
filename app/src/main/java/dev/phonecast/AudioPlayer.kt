package dev.phonecast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Plays incoming raw PCM. Drops queued audio if it falls behind, to stay in sync with video. */
class AudioPlayer(sampleRate: Int, channels: Int) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val track: AudioTrack
    @Volatile private var running = false
    private val thread = Thread(::loop, "pc-audio-out")

    init {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf * 2, sampleRate * channels * 2 / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        track = builder.build()
    }

    fun start() {
        track.play()
        running = true
        thread.start()
    }

    fun write(pcm: ByteArray) {
        if (!running) return
        if (queue.size > MAX_QUEUED) queue.clear() // ~200 ms behind: skip ahead
        queue.offer(pcm)
    }

    private fun loop() {
        try {
            while (running) {
                val d = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                track.write(d, 0, d.size)
            }
        } catch (_: InterruptedException) {
        }
    }

    fun release() {
        running = false
        thread.interrupt()
        try { thread.join(500) } catch (_: InterruptedException) {}
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }

    private companion object {
        const val MAX_QUEUED = 10 // 10 x 20 ms chunks
    }
}
