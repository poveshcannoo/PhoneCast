package dev.phonecast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Hardware H.264 decoder rendering straight to a Surface; frames are shown as soon as decoded. */
class VideoDecoder(surface: Surface, width: Int, height: Int) {

    private val callbackThread = HandlerThread("pc-decoder").apply { start() }
    private val freeInputs = LinkedBlockingQueue<Int>()

    @Volatile var failed = false
        private set

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            freeInputs.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                codec.releaseOutputBuffer(index, info.size > 0) // render immediately
            } catch (_: IllegalStateException) {
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "Decoder error", e)
            failed = true
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    private val codec: MediaCodec = try {
        try {
            create(surface, width, height, tuned = true)
        } catch (e: Exception) {
            Log.w(TAG, "Tuned decoder config failed, retrying plain", e)
            freeInputs.clear()
            create(surface, width, height, tuned = false)
        }
    } catch (e: Exception) {
        callbackThread.quitSafely()
        throw e
    }

    private fun create(surface: Surface, width: Int, height: Int, tuned: Boolean): MediaCodec {
        val c = MediaCodec.createDecoderByType(Protocol.MIME)
        try {
            val format = MediaFormat.createVideoFormat(Protocol.MIME, width, height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max(width * height, 1 shl 20))
            if (tuned) {
                if (Build.VERSION.SDK_INT >= 30 &&
                    c.codecInfo.getCapabilitiesForType(Protocol.MIME)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                ) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            c.setCallback(callback, Handler(callbackThread.looper))
            c.configure(format, surface, null, 0)
            c.start()
            return c
        } catch (e: Exception) {
            c.release()
            throw e
        }
    }

    /** Copies one packet into the decoder. Returns false if it had to be dropped. */
    fun queue(data: ByteArray, length: Int, ptsUs: Long, flags: Int): Boolean {
        if (failed) return false
        val index = freeInputs.poll(200, TimeUnit.MILLISECONDS) ?: return false
        return try {
            val buffer = codec.getInputBuffer(index)
            if (buffer == null || length > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, 0)
                false
            } else {
                buffer.clear()
                buffer.put(data, 0, length)
                codec.queueInputBuffer(index, 0, length, ptsUs, flags)
                true
            }
        } catch (e: IllegalStateException) {
            failed = true
            false
        }
    }

    fun release() {
        failed = true
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        callbackThread.quitSafely()
    }

    private companion object {
        const val TAG = "VideoDecoder"
    }
}
