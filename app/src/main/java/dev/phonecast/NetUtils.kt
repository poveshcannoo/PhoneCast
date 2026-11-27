package dev.phonecast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** IPv4 addresses of this device on local networks (Wi-Fi/Ethernet first). */
    fun localIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    /** Keeps Wi-Fi out of power-save while streaming (best effort). */
    fun acquireWifiLock(context: Context, tag: String): WifiManager.WifiLock? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wm.createWifiLock(mode, tag).apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (e: Exception) {
        null
    }

    fun release(lock: WifiManager.WifiLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: Exception) {
        }
    }
}
