package dev.phonecast

import android.annotation.TargetApi
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import kotlin.math.min

/*
 * Projector side of the USB cable link. The projector is the USB host; it asks the phone to
 * switch into Android Open Accessory mode, then reads the same PhoneCast stream over bulk USB.
 */

object Aoa {
    private const val GOOGLE_VID = 0x18D1
    private val ACCESSORY_PIDS = 0x2D00..0x2D05

    fun isAccessory(d: UsbDevice) = d.vendorId == GOOGLE_VID && d.productId in ACCESSORY_PIDS

    /** Skips obvious non-phones (keyboards, remotes, USB sticks, hubs, webcams, sound cards). */
    fun looksLikePhone(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_HUB) return false
        if (d.interfaceCount == 0) return true
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_HID,
                UsbConstants.USB_CLASS_MASS_STORAGE,
                UsbConstants.USB_CLASS_HUB,
                UsbConstants.USB_CLASS_AUDIO,
                UsbConstants.USB_CLASS_VIDEO,
                UsbConstants.USB_CLASS_PRINTER -> Unit
                // 0xE0/1/1 is a Bluetooth dongle; other wireless-controller interfaces (e.g. RNDIS) may be a phone.
                UsbConstants.USB_CLASS_WIRELESS_CONTROLLER ->
                    if (intf.interfaceSubclass != 1 || intf.interfaceProtocol != 1) return true
                else -> return true
            }
        }
        return false
    }

    /** Sends the AOA "start accessory" sequence. The phone then re-attaches as 18D1:2D0x. */
    fun switchToAccessory(conn: UsbDeviceConnection): Boolean {
        val buf = ByteArray(2)
        val r = conn.controlTransfer(0xC0, 51, 0, 0, buf, 2, 1000) // GET_PROTOCOL
        if (r < 2) return false
        val version = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        if (version < 1) return false
        val strings = arrayOf(
            Protocol.AOA_MANUFACTURER,
            Protocol.AOA_MODEL,
            "PhoneCast projector receiver",
            "1.0",
            "https://github.com/",
            "PHONECAST1",
        )
        for ((index, s) in strings.withIndex()) {
            val bytes = (s + "\u0000").toByteArray(Charsets.UTF_8)
            if (conn.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, 1000) < 0) return false // SEND_STRING
        }
        conn.controlTransfer(0x40, 53, 0, 0, null, 0, 1000) // START (device disconnects right away)
        return true
    }
}

/** Bulk-endpoint streams to a phone that is in accessory mode. Requires Android 8+ (UsbRequest timeouts). */
@TargetApi(26)
class UsbStreamLink(usb: UsbManager, device: UsbDevice) {

    private val conn: UsbDeviceConnection
    private val intf: UsbInterface
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private val request = UsbRequest()
    private val inBuf = ByteBuffer.allocate(BUF_SIZE)

    @Volatile var closed = false
        private set

    init {
        val c = usb.openDevice(device) ?: throw IOException("Could not open the USB device")
        val parts = try {
            setUp(c, device)
        } catch (e: Exception) {
            c.close()
            throw e
        }
        conn = c
        intf = parts.first
        epIn = parts.second
        epOut = parts.third
    }

    private fun setUp(c: UsbDeviceConnection, device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint> {
        val i = device.getInterface(0)
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (k in 0 until i.endpointCount) {
            val ep = i.getEndpoint(k)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) input = ep else output = ep
        }
        if (input == null || output == null) throw IOException("USB accessory endpoints not found")
        if (!c.claimInterface(i, true)) throw IOException("Could not claim the USB interface")
        if (!request.initialize(c, input)) throw IOException("Could not set up USB transfers")
        return Triple(i, input, output)
    }

    val input: InputStream = object : InputStream() {
        private val arr = inBuf.array()
        private var pos = 0
        private var limit = 0

        override fun read(): Int {
            fill()
            return arr[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            fill()
            val n = min(len, limit - pos)
            System.arraycopy(arr, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = limit - pos

        // Async request + timed wait: unlike bulkTransfer(), a timeout here never loses data.
        private fun fill() {
            while (pos >= limit) {
                if (closed) throw IOException("USB link closed")
                inBuf.clear()
                if (!request.queue(inBuf)) throw IOException("USB read failed")
                while (true) {
                    val done = try {
                        conn.requestWait(250)
                    } catch (e: TimeoutException) {
                        if (closed) {
                            request.cancel()
                            throw IOException("USB link closed")
                        }
                        continue
                    }
                    if (done == null) throw IOException("USB cable disconnected")
                    break
                }
                pos = 0
                limit = inBuf.position()
            }
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (closed) throw IOException("USB link closed")
                val n = conn.bulkTransfer(epOut, b, o, min(remaining, BUF_SIZE), 1000)
                if (n < 0) throw IOException("USB write failed (is PhoneCast open on the phone?)")
                o += n
                remaining -= n
            }
        }
    }

    /** Makes a blocked read return soon (from any thread). */
    fun shutdown() {
        closed = true
        try { request.cancel() } catch (_: Exception) {}
    }

    /** Frees the USB device. Call from the reading thread once it has stopped. */
    fun dispose() {
        closed = true
        try { request.close() } catch (_: Exception) {}
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }

    private companion object {
        const val BUF_SIZE = 16 * 1024
    }
}

/** Runs PhoneCast streams arriving over a USB link, one after another, until the cable is pulled. */
@TargetApi(26)
class UsbReceiver(
    private val surface: Surface,
    private val link: UsbStreamLink,
    private val listener: ReceiverServer.Listener,
    private val onEnded: () -> Unit,
) {
    private val thread = Thread(::serve, "pc-usb-session")

    fun start() = thread.start()

    fun stopAndWait() {
        link.shutdown()
        try { thread.join(3000) } catch (_: InterruptedException) {}
    }

    private fun hello() {
        try {
            link.output.write(Protocol.REQ_HELLO)
        } catch (_: IOException) {
            // Phone app not listening yet; it will start a fresh stream when the user taps Share.
        }
    }

    private fun serve() {
        try {
            hello() // if the phone is already mid-stream, it resends handshake + headers
            val input = DataInputStream(BufferedInputStream(link.input, 1 shl 16))
            while (!link.closed) {
                waitForMagic(input)
                input.readInt() // version
                listener.onClientConnected("USB")
                val end = StreamSession(surface, listener).run(input, link.output) { link.closed }
                listener.onClientDisconnected(end.reason)
                if (end.linkError) break
                if (!end.bye) hello() // decoder trouble: ask the phone to restart the stream
            }
        } catch (e: IOException) {
            Log.i(TAG, "USB link ended: ${e.message}")
        } finally {
            link.dispose()
            onEnded()
        }
    }

    /** Skips bytes until the MAGIC marker, so we can join a stream at any point. */
    private fun waitForMagic(input: DataInputStream) {
        var window = 0
        while (true) {
            window = (window shl 8) or input.readUnsignedByte()
            if (window == Protocol.MAGIC) return
        }
    }

    private companion object {
        const val TAG = "UsbReceiver"
    }
}

/**
 * Watches the projector's USB ports (UI thread). When a phone is plugged in it asks permission,
 * switches the phone into accessory mode, then reports the accessory device via [onAccessoryReady].
 */
class UsbHostWatcher(
    private val context: Context,
    private val onAccessoryReady: (UsbDevice) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val switched = HashSet<String>()
    private val denied = HashSet<String>()
    private var askingFor: String? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                ACTION_PERMISSION -> {
                    askingFor = null
                    if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        denied += device.deviceName
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.let {
                    switched -= it.deviceName
                    denied -= it.deviceName
                    if (askingFor == it.deviceName) askingFor = null
                }
            }
            scan()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
        scan()
    }

    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
    }

    fun scan() {
        if (askingFor != null) return
        val devices = try { usb.deviceList.values.toList() } catch (e: Exception) { emptyList() }

        // 1) A phone already in PhoneCast accessory mode?
        for (d in devices) {
            if (!Aoa.isAccessory(d) || d.deviceName in denied) continue
            if (usb.hasPermission(d)) onAccessoryReady(d) else ask(d)
            return
        }
        // 2) Otherwise, try to switch a plugged-in phone into accessory mode.
        for (d in devices) {
            if (d.deviceName in switched || d.deviceName in denied || !Aoa.looksLikePhone(d)) continue
            if (!usb.hasPermission(d)) {
                ask(d)
                return
            }
            switched += d.deviceName
            val conn = usb.openDevice(d) ?: continue
            val ok = try { Aoa.switchToAccessory(conn) } catch (e: Exception) { false } finally { conn.close() }
            if (ok) {
                onStatus(context.getString(R.string.usb_switching))
                return
            }
        }
    }

    private fun ask(d: UsbDevice) {
        askingFor = d.deviceName
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 1, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
        usb.requestPermission(d, pi)
    }

    private companion object {
        const val ACTION_PERMISSION = "dev.phonecast.USB_DEVICE_PERMISSION"
    }
}
