package dev.phonecast

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends packets on its own thread over any OutputStream (TCP socket or USB accessory).
 * If the link can't keep up, it drops video frames until the next keyframe (and asks for
 * one) instead of letting latency pile up.
 */
class PacketWriter(
    output: OutputStream,
    private val closeAction: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private class Packet(val type: Int, val pts: Long, val data: ByteArray, val done: CountDownLatch? = null)

    private val queue = LinkedBlockingQueue<Packet>()
    private val queuedBytes = AtomicLong()
    private val out = DataOutputStream(BufferedOutputStream(output, 16 * 1024))
    private val thread = Thread(::loop, "pc-writer")

    @Volatile private var running = true
    @Volatile private var dropUntilKeyframe = false

    /** Called (from any thread) when frames are being dropped and a keyframe is needed. */
    @Volatile var onBacklog: (() -> Unit)? = null

    fun start() {
        queue.offer(Packet(HANDSHAKE, 0, EMPTY))
        thread.start()
    }

    /** Re-sends MAGIC + VERSION so a receiver that joined mid-stream can lock on. */
    fun sendHandshake() {
        if (running) queue.offer(Packet(HANDSHAKE, 0, EMPTY))
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
                if (p.type == HANDSHAKE) {
                    out.writeInt(Protocol.MAGIC)
                    out.writeInt(Protocol.VERSION)
                } else {
                    queuedBytes.addAndGet(-p.data.size.toLong())
                    out.writeByte(p.type)
                    out.writeInt(p.data.size)
                    out.writeLong(p.pts)
                    out.write(p.data)
                }
                if (queue.isEmpty() || p.done != null) out.flush()
                p.done?.countDown()
            }
        } catch (e: IOException) {
            if (running) {
                running = false
                onFailure()
            }
        } catch (_: InterruptedException) {
        }
    }

    /** Sends BYE (waiting briefly for it to go out), then closes. */
    fun finish() {
        if (running && thread.isAlive) {
            val latch = CountDownLatch(1)
            queue.offer(Packet(Protocol.BYE, 0, EMPTY, latch))
            try { latch.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        }
        close()
    }

    fun close() {
        running = false
        try { closeAction() } catch (_: Exception) {}
        thread.interrupt()
        try { thread.join(500) } catch (_: InterruptedException) {}
    }

    private companion object {
        const val HANDSHAKE = -1
        val EMPTY = ByteArray(0)
        const val MAX_PACKETS = 60
        const val MAX_BYTES = 3L * 1024 * 1024
    }
}
