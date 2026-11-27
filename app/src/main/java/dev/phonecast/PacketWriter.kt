package dev.phonecast

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends packets on its own thread. If the network can't keep up, it drops
 * video frames until the next keyframe (and asks for one) instead of letting
 * latency pile up.
 */
class PacketWriter(private val socket: Socket, private val onFailure: () -> Unit) {

    private class Packet(val type: Int, val pts: Long, val data: ByteArray)

    private val queue = LinkedBlockingQueue<Packet>()
    private val queuedBytes = AtomicLong()
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
    private val thread = Thread(::loop, "pc-writer")

    @Volatile private var running = true
    @Volatile private var dropUntilKeyframe = false

    /** Called (from any thread) when frames are being dropped and a keyframe is needed. */
    @Volatile var onBacklog: (() -> Unit)? = null

    fun start() {
        out.writeInt(Protocol.MAGIC)
        out.writeInt(Protocol.VERSION)
        out.flush()
        thread.start()
    }

    fun send(type: Int, pts: Long, data: ByteArray) {
        if (!running) return
        val congested = queue.size > MAX_PACKETS || queuedBytes.get() > MAX_BYTES
        when (type) {
            Protocol.VIDEO_FRAME -> {
                if (dropUntilKeyframe) return
                if (congested) {
                    dropUntilKeyframe = true
                    onBacklog?.invoke()
                    return
                }
            }
            Protocol.VIDEO_KEYFRAME -> dropUntilKeyframe = false
            Protocol.AUDIO_DATA -> if (congested) return
        }
        queuedBytes.addAndGet(data.size.toLong())
        queue.offer(Packet(type, pts, data))
    }

    private fun loop() {
        try {
            while (running) {
                val p = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                queuedBytes.addAndGet(-p.data.size.toLong())
                out.writeByte(p.type)
                out.writeInt(p.data.size)
                out.writeLong(p.pts)
                out.write(p.data)
                if (queue.isEmpty()) out.flush()
            }
        } catch (e: IOException) {
            if (running) {
                running = false
                onFailure()
            }
        } catch (_: InterruptedException) {
        }
    }

    fun close() {
        running = false
        try { socket.close() } catch (_: IOException) {}
        thread.interrupt()
        try { thread.join(500) } catch (_: InterruptedException) {}
    }

    private companion object {
        const val MAX_PACKETS = 60
        const val MAX_BYTES = 3L * 1024 * 1024
    }
}
