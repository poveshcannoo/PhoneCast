package dev.phonecast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Hardware H.264 encoder fed by a Surface (the MediaProjection virtual display draws into it). */
class ScreenEncoder private constructor(
    codecName: String?,
    val width: Int,
    val height: Int,
    bitrate: Int,
    fps: Int,
) {
    private val codec: MediaCodec
    val inputSurface: Surface

    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        var configured: MediaCodec? = null
        var surface: Surface? = null
        var error: Exception? = null
        // First try with latency tweaks, then a plain config for picky encoders.
        for (tuned in booleanArrayOf(true, false)) {
            val c = if (codecName != null) MediaCodec.createByCodecName(codecName)
            else MediaCodec.createEncoderByType(Protocol.MIME)
            try {
                c.configure(format(width, height, bitrate, fps, tuned), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = c.createInputSurface()
                configured = c
                break
            } catch (e: Exception) {
                error = e
                c.release()
            }
        }
        codec = configured ?: throw (error ?: IllegalStateException("Encoder configuration failed"))
        inputSurface = surface!!
    }

    fun start(sink: (type: Int, pts: Long, data: ByteArray) -> Unit) {
        codec.start()
        running = true
        thread = Thread({ drain(sink) }, "pc-encoder").also { it.start() }
    }

    fun requestKeyframe() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        try { thread?.join(1000) } catch (_: InterruptedException) {}
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        inputSurface.release()
    }

    private fun drain(sink: (Int, Long, ByteArray) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var configSent = false
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val csd = csdOf(codec.outputFormat)
                    if (csd != null) {
                        sink(Protocol.VIDEO_CONFIG, 0, csd)
                        configSent = true
                    }
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buffer.get(data)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            if (!configSent) {
                                sink(Protocol.VIDEO_CONFIG, 0, data)
                                configSent = true
                            }
                        } else {
                            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            sink(if (key) Protocol.VIDEO_KEYFRAME else Protocol.VIDEO_FRAME, info.presentationTimeUs, data)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } catch (e: IllegalStateException) {
            if (running) Log.w(TAG, "Encoder stopped unexpectedly", e)
        }
    }

    companion object {
        private const val TAG = "ScreenEncoder"

        /** Creates an encoder for a source of srcW x srcH, scaled down to fit the preset and the hardware. */
        fun create(srcW: Int, srcH: Int, preset: Preset): ScreenEncoder {
            val info = pickEncoder()
            val caps = try {
                info?.getCapabilitiesForType(Protocol.MIME)?.videoCapabilities
            } catch (e: Exception) {
                null
            }
            var maxLong = preset.maxLongSide
            var lastError: Exception? = null
            for (attempt in 0 until 5) {
                var size = fitSize(srcW, srcH, maxLong)
                var guard = 0
                while (caps != null && !caps.isSizeSupported(size.first, size.second) && guard++ < 30 && maxLong > 160) {
                    maxLong = maxLong * 9 / 10
                    size = fitSize(srcW, srcH, maxLong)
                }
                val bitrate = caps?.bitrateRange?.clamp(preset.bitrate) ?: preset.bitrate
                try {
                    Log.i(TAG, "Encoder ${info?.name} ${size.first}x${size.second} @${preset.fps}fps ${bitrate / 1000}kbps")
                    return ScreenEncoder(info?.name, size.first, size.second, bitrate, preset.fps)
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Encoder failed at ${size.first}x${size.second}", e)
                }
                maxLong = maxLong * 3 / 4
            }
            throw IllegalStateException("Could not start the H.264 encoder", lastError)
        }

        private fun pickEncoder(): MediaCodecInfo? = try {
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals(Protocol.MIME, ignoreCase = true) }
            }
            fun isSoftware(ci: MediaCodecInfo) =
                if (Build.VERSION.SDK_INT >= 29) ci.isSoftwareOnly
                else ci.name.startsWith("OMX.google.") || ci.name.startsWith("c2.android.")
            all.firstOrNull { !isSoftware(it) } ?: all.firstOrNull()
        } catch (e: Exception) {
            null
        }

        private fun fitSize(srcW: Int, srcH: Int, maxLong: Int): Pair<Int, Int> {
            val scale = min(1.0, maxLong.toDouble() / max(srcW, srcH))
            val w = max(16, ((srcW * scale).toInt() / 16) * 16)
            val h = max(16, ((srcH * scale).toInt() / 16) * 16)
            return w to h
        }

        private fun format(w: Int, h: Int, bitrate: Int, fps: Int, tuned: Boolean) =
            MediaFormat.createVideoFormat(Protocol.MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (tuned) {
                    // Keep producing frames on a static screen so keyframe requests are served.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                    if (Build.VERSION.SDK_INT >= 29) {
                        setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                        setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                    }
                }
            }

        private fun csdOf(format: MediaFormat): ByteArray? {
            val out = ByteArrayOutputStream()
            for (key in arrayOf("csd-0", "csd-1")) {
                if (!format.containsKey(key)) continue
                val b = format.getByteBuffer(key) ?: continue
                val d = ByteArray(b.remaining())
                b.duplicate().get(d)
                out.write(d)
            }
            return if (out.size() > 0) out.toByteArray() else null
        }
    }
}
