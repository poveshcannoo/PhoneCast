package dev.phonecast

import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * TCP server on the projector. One phone at a time; a new phone replaces the current one.
 * Listener callbacks arrive on background threads.
 */
class ReceiverServer(private val surface: Surface, private val listener: Listener) {

    interface Listener {
        fun onClientConnected(address: String)
        fun onVideoSize(width: Int, height: Int)
        fun onClientDisconnected(reason: String?)
    }

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(Protocol.PORT))
    }
    @Volatile private var running = true
    @Volatile private var session: Session? = null
    private val acceptThread = Thread(::acceptLoop, "pc-accept")

    fun start() = acceptThread.start()

    fun stop() {
        running = false
        try { serverSocket.close() } catch (_: IOException) {}
        try { acceptThread.join(1000) } catch (_: InterruptedException) {}
        session?.closeAndWait()
        session = null
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                serverSocket.accept()
            } catch (e: IOException) {
                break
            }
            // The previous decoder must let go of the Surface before a new one can use it.
            session?.closeAndWait()
            if (!running) {
                try { client.close() } catch (_: IOException) {}
                break
            }
            session = Session(client).also { it.start() }
        }
    }

    private inner class Session(private val socket: Socket) {
        @Volatile private var closed = false
        private val thread = Thread(::serve, "pc-session")

        fun start() = thread.start()

        fun closeAndWait() {
            closed = true
            try { socket.close() } catch (_: IOException) {}
            try { thread.join(3000) } catch (_: InterruptedException) {}
        }

        private fun serve() {
            var decoder: VideoDecoder? = null
            var audio: AudioPlayer? = null
            var reason: String? = null
            try {
                socket.tcpNoDelay = true
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
                val output = socket.getOutputStream()
                if (input.readInt() != Protocol.MAGIC) throw IOException("Not a PhoneCast sender")
                input.readInt() // protocol version (only v1 exists)
                listener.onClientConnected(socket.inetAddress?.hostAddress ?: "?")

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

                while (!closed) {
                    val type = input.readUnsignedByte()
                    val length = input.readInt()
                    val pts = input.readLong()
                    if (length < 0 || length > Protocol.MAX_PACKET) throw IOException("Corrupt stream")
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
                                if (++resets > 5) throw IOException("Video decoder keeps failing on this device")
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
                                // Dropped a frame: picture would smear until the next keyframe, so ask now.
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
                    }
                }
            } catch (_: EOFException) {
            } catch (e: IOException) {
                if (!closed) reason = e.message
            } catch (e: Exception) {
                Log.e(TAG, "Session failed", e)
                if (!closed) reason = e.toString()
            } finally {
                decoder?.release()
                audio?.release()
                try { socket.close() } catch (_: IOException) {}
                listener.onClientDisconnected(reason)
            }
        }
    }

    private companion object {
        const val TAG = "ReceiverServer"
    }
}
