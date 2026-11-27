package dev.phonecast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/** Finds PhoneCast receivers on the local network via mDNS / DNS-SD. Callbacks on the main thread. */
class ReceiverDiscovery(context: Context, private val onChange: (List<Found>) -> Unit) {

    data class Found(val name: String, val host: String, val port: Int)

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (listener != null) return
        val gen = ++generation
        multicastLock = try {
            (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("phonecast-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            null
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (gen == generation) {
                        pending.addLast(info)
                        resolveNext(gen)
                    }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (gen == generation && found.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
            listener = null
        }
    }

    // Older Android versions only allow one resolve at a time, so resolve sequentially.
    private fun resolveNext(gen: Int) {
        if (resolving || gen != generation) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (gen != generation) return@post
                    resolving = false
                    resolveNext(gen)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (gen != generation) return@post
                    resolving = false
                    val host = hostOf(serviceInfo)
                    if (host != null) {
                        val name = serviceInfo.serviceName.replace("\\032", " ")
                        found[serviceInfo.serviceName] = Found(name, host, serviceInfo.port)
                        publish()
                    }
                    resolveNext(gen)
                }
            }
        })
    }

    private fun hostOf(si: NsdServiceInfo): String? {
        val addrs: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses
        else listOfNotNull(@Suppress("DEPRECATION") si.host)
        return (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull())?.hostAddress
    }

    private fun publish() = onChange(found.values.toList())

    fun stop() {
        generation++
        listener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        listener = null
        pending.clear()
        resolving = false
        found.clear()
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private companion object {
        const val TAG = "ReceiverDiscovery"
    }
}
