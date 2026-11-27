package dev.phonecast

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi (TCP) server on the projector. One phone at a time; a new phone replaces the current one.
 * Listener callbacks arrive on background threads.
 */
class ReceiverServer(private val surface: android.view.Surface, private val listener: Listener) {

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
            var reason: String? = null
            try {
                socket.tcpNoDelay = true
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
                if (input.readInt() != Protocol.MAGIC) throw IOException("Not a PhoneCast sender")
                input.readInt() // protocol version (only v1 exists)
                listener.onClientConnected(socket.inetAddress?.hostAddress ?: "?")
                reason = StreamSession(surface, listener).run(input, socket.getOutputStream()) { closed }.reason
            } catch (e: IOException) {
                if (!closed) reason = e.message
            } finally {
                try { socket.close() } catch (_: IOException) {}
                listener.onClientDisconnected(reason)
            }
        }
    }
}
