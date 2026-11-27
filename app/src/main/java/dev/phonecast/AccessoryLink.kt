package dev.phonecast

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Phone side of the USB cable link (Android Open Accessory). The projector is the USB host;
 * the phone opens the "accessory" once and keeps it open across sharing sessions, because the
 * kernel only allows one open at a time and blocked reads can't be interrupted.
 */
object AccessoryLink {
    interface Listener {
        fun onByte(b: Int)
        fun onLost()
    }

    @Volatile var listener: Listener? = null
    @Volatile var output: OutputStream? = null
        private set

    private var pfd: ParcelFileDescriptor? = null
    private var accessory: UsbAccessory? = null

    val isOpen: Boolean get() = output != null

    @Synchronized
    fun open(usb: UsbManager, acc: UsbAccessory): Boolean {
        if (output != null && acc == accessory) return true
        closeLocked()
        val fd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            Log.w(TAG, "openAccessory failed", e)
            null
        } ?: return false
        pfd = fd
        accessory = acc
        output = FileOutputStream(fd.fileDescriptor)
        // Read with a 16 KB buffer: small reads on the accessory endpoint can fail.
        val input = BufferedInputStream(FileInputStream(fd.fileDescriptor), 16 * 1024)
        Thread({
            try {
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    listener?.onByte(b)
                }
            } catch (_: IOException) {
            }
            lost(fd)
        }, "pc-usb-reader").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    private fun lost(fd: ParcelFileDescriptor) {
        if (pfd !== fd) return
        closeLocked()
        listener?.onLost()
    }

    @Synchronized
    fun close() = closeLocked()

    private fun closeLocked() {
        output = null
        try { pfd?.close() } catch (_: IOException) {}
        pfd = null
        accessory = null
    }

    private const val TAG = "AccessoryLink"
}
