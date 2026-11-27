package dev.phonecast

import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Plays one stream (after the MAGIC/VERSION handshake) from any transport: TCP or USB.
 * Owns the decoder and audio player for its lifetime and always releases them.
 */
class StreamSession(private val surface: Surface, private val listener: ReceiverServer.Listener) {

    /** bye = sender stopped cleanly; linkError = the transport itself failed. */
    class End(val bye: Boolean, val linkError: Boolean, val reason: String?)

    /** [input] must support mark/reset (wrap the transport in a BufferedInputStream). */
    fun run(input: DataInputStream, output: OutputStream, isClosed: () -> Boolean): End {
        var decoder: VideoDecoder? = null
        var audio: AudioPlayer? = null
        try {
            var buf = ByteArray(1 shl 20)
            var width = 0
            var height = 0
            var config: ByteArray? = null
            var waitingForKey = true
            var resets = 0

            fun requestKeyframe() {
                try {
                    output.write(Protocol.REQ_KEYFRAME)
                    output.flush()
                } catch (_: IOException) {
                }
            }

            fun newDecoder(): VideoDecoder {
                val d = VideoDecoder(surface, width, height)
                config?.let { d.queue(it, it.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
                return d
            }

            while (!isClosed()) {
                // A fresh handshake mid-stream (sender resynced): skip it and carry on.
                input.mark(8)
                if (input.readInt() == Protocol.MAGIC) {
                    input.readInt()
                    continue
                }
                input.reset()
                val type = input.readUnsignedByte()
                val length = input.readInt()
                val pts = input.readLong()
                if (length < 0 || length > Protocol.MAX_PACKET) throw IllegalStateException("Corrupt stream")
                if (buf.size < length) buf = ByteArray(length)
                input.readFully(buf, 0, length)

                when (type) {
                    Protocol.VIDEO_HEADER -> {
                        val bb = ByteBuffer.wrap(buf, 0, length)
                        width = bb.int
                        height = bb.int
                        decoder?.release()
                        decoder = null
                        config = null
                        waitingForKey = true
                        decoder = VideoDecoder(surface, width, height)
                        listener.onVideoSize(width, height)
                    }

                    Protocol.VIDEO_CONFIG -> {
                        config = buf.copyOf(length)
                        decoder?.queue(buf, length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    }

                    Protocol.VIDEO_KEYFRAME, Protocol.VIDEO_FRAME -> {
                        var d = decoder ?: continue
                        if (d.failed) {
                            if (++resets > 5) throw IllegalStateException("Video decoder keeps failing on this device")
                            d.release()
                            d = newDecoder()
                            decoder = d
                            waitingForKey = true
                            requestKeyframe()
                        }
                        val isKey = type == Protocol.VIDEO_KEYFRAME
                        if (!isKey && waitingForKey) continue
                        if (d.queue(buf, length, pts, 0)) {
                            if (isKey) waitingForKey = false
                        } else {
                            // Dropped a frame: the picture would smear until the next keyframe, so ask now.
                            waitingForKey = true
                            requestKeyframe()
                        }
                    }

                    Protocol.AUDIO_HEADER -> {
                        val bb = ByteBuffer.wrap(buf, 0, length)
                        val rate = bb.int
                        val channels = bb.int
                        audio?.release()
                        audio = try {
                            AudioPlayer(rate, channels).also { it.start() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Audio output failed", e)
                            null
                        }
                    }

                    Protocol.AUDIO_DATA -> audio?.write(buf.copyOf(length))

                    Protocol.BYE -> return End(bye = true, linkError = false, reason = null)
                }
            }
            return End(bye = false, linkError = true, reason = null)
        } catch (e: EOFException) {
            return End(bye = false, linkError = true, reason = null)
        } catch (e: IOException) {
            return End(bye = false, linkError = true, reason = if (isClosed()) null else e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            return End(bye = false, linkError = false, reason = if (isClosed()) null else (e.message ?: e.toString()))
        } finally {
            decoder?.release()
            audio?.release()
        }
    }

    private companion object {
        const val TAG = "StreamSession"
    }
}
