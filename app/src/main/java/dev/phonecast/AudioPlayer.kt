package dev.phonecast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Plays incoming raw PCM with as little delay as possible.
 *
 * Latency control: AudioTrack's (small) buffer blocks writes when full, so audio only piles
 * up in our queue when we're behind — after a network hiccup, or because the phone's and
 * projector's audio clocks drift apart. Whenever more than ~40 ms is waiting we drop 20 ms
 * chunks, which keeps sound locked to the video instead of slowly falling behind.
 */
class AudioPlayer(sampleRate: Int, channels: Int) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val track: AudioTrack
    private val bytesPerFrame = channels * 2
    @Volatile private var running = false
    private val thread = Thread(::loop, "pc-audio-out")

    init {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // Smallest buffer the device allows, but at least 40 ms so cheap chips don't crackle.
        val bufBytes = max(minBuf, sampleRate * bytesPerFrame * 40 / 1000)
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
            .setBufferSizeInBytes(bufBytes)
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
        queue.offer(pcm)
    }

    private fun loop() {
        try {
            while (running) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (queue.size > MAX_WAITING_CHUNKS) continue // behind: skip this chunk to catch up
                track.write(chunk, 0, chunk.size)
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
        const val MAX_WAITING_CHUNKS = 2 // x 20 ms
    }
}
