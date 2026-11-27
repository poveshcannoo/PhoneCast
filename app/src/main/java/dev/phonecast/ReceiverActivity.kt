package dev.phonecast

import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen receiver for the projector / TV. Accepts a phone over Wi-Fi (TCP) or over a
 * USB cable (Android Open Accessory). While a USB phone is attached, USB has priority and
 * the Wi-Fi server is paused. Controllable with just a remote (Back exits).
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback, ReceiverServer.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var addressText: TextView
    private lateinit var statusText: TextView
    private lateinit var usbLine: TextView

    // Transport objects are only touched on the coordinator thread, so start/stop never race.
    private val coordinator: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var surface: Surface? = null
    private var server: ReceiverServer? = null
    private var usbSession: UsbReceiver? = null
    @Volatile private var usbLinked = false

    private var usbWatcher: UsbHostWatcher? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var videoW = 0
    private var videoH = 0

    private val deviceName: String by lazy {
        val n = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        ("PhoneCast · " + (n?.takeIf { it.isNotBlank() } ?: Build.MODEL)).take(60)
    }

    private val usbSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        addressText = findViewById(R.id.address)
        statusText = findViewById(R.id.status)
        usbLine = findViewById(R.id.usb_line)
        findViewById<TextView>(R.id.name).text = getString(R.string.receiver_name, deviceName)
        usbLine.setText(if (usbSupported) R.string.receiver_usb_hint else R.string.receiver_usb_unsupported)

        if (usbSupported) {
            usbWatcher = UsbHostWatcher(this, ::onUsbAccessory) { msg -> statusText.text = msg }
        }

        surfaceView.holder.addCallback(this)
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                root.post { applyVideoLayout() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshAddresses()
        registerNsd()
        wifiLock = NetUtils.acquireWifiLock(this, "phonecast-receiver")
        usbWatcher?.start()
    }

    override fun onStop() {
        usbWatcher?.stop()
        unregisterNsd()
        NetUtils.release(wifiLock)
        wifiLock = null
        super.onStop()
    }

    override fun onDestroy() {
        coordinator.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------------------------------------------------------------- surface -> transports

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        onCoordinator { startWifiIfIdle() }
        usbWatcher?.scan()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        // Block until every decoder has released the surface.
        try {
            coordinator.submit {
                usbSession?.stopAndWait()
                usbSession = null
                server?.stop()
                server = null
            }.get()
        } catch (e: Exception) {
            Log.w(TAG, "Stopping transports failed", e)
        }
    }

    private fun onCoordinator(block: () -> Unit) {
        try {
            coordinator.execute(block)
        } catch (_: Exception) {
            // Activity is being destroyed.
        }
    }

    /** Coordinator thread. */
    private fun startWifiIfIdle() {
        val s = surface ?: return
        if (server != null || usbSession != null) return
        try {
            server = ReceiverServer(s, this).also { it.start() }
            runOnUiThread { if (!usbLinked) statusText.setText(R.string.receiver_waiting) }
        } catch (e: IOException) {
            runOnUiThread { statusText.text = getString(R.string.receiver_port_error, Protocol.PORT, e.message ?: "") }
        }
    }

    /** UI thread: a phone in PhoneCast USB mode is attached and we have permission. */
    private fun onUsbAccessory(device: UsbDevice) {
        if (Build.VERSION.SDK_INT < 26) return
        val usb = getSystemService(USB_SERVICE) as UsbManager
        onCoordinator {
            if (usbSession != null) return@onCoordinator
            val s = surface ?: return@onCoordinator
            // USB wins: pause Wi-Fi so only one stream uses the surface.
            server?.stop()
            server = null
            val link = try {
                UsbStreamLink(usb, device)
            } catch (e: Exception) {
                Log.w(TAG, "USB link failed", e)
                runOnUiThread { statusText.text = getString(R.string.receiver_usb_error, e.message ?: "") }
                startWifiIfIdle()
                return@onCoordinator
            }
            usbLinked = true
            runOnUiThread { statusText.setText(R.string.receiver_usb_ready) }
            usbSession = UsbReceiver(s, link, this) {
                onCoordinator {
                    usbSession = null
                    usbLinked = false
                    runOnUiThread {
                        if (!isFinishing) {
                            overlay.visibility = View.VISIBLE
                            statusText.setText(R.string.receiver_usb_unplugged)
                        }
                    }
                    startWifiIfIdle()
                }
            }.also { it.start() }
        }
    }

    // ---------------------------------------------------------------- stream callbacks (background threads)

    override fun onClientConnected(address: String) = runOnUiThread {
        statusText.text = getString(R.string.receiver_connected, address)
    }

    override fun onVideoSize(width: Int, height: Int) = runOnUiThread {
        videoW = width
        videoH = height
        applyVideoLayout()
        overlay.visibility = View.GONE
    }

    override fun onClientDisconnected(reason: String?) = runOnUiThread {
        if (isFinishing) return@runOnUiThread
        overlay.visibility = View.VISIBLE
        refreshAddresses()
        statusText.text = when {
            reason != null -> getString(R.string.receiver_disconnected, reason)
            usbLinked -> getString(R.string.receiver_usb_ready)
            else -> getString(R.string.receiver_waiting)
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Letterbox the video inside the screen, keeping the phone's aspect ratio. */
    private fun applyVideoLayout() {
        if (videoW <= 0 || videoH <= 0 || root.width <= 0 || root.height <= 0) return
        val scale = min(root.width.toFloat() / videoW, root.height.toFloat() / videoH)
        val w = (videoW * scale).roundToInt()
        val h = (videoH * scale).roundToInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    private fun refreshAddresses() {
        val ips = NetUtils.localIpv4()
        addressText.text = if (ips.isEmpty()) getString(R.string.receiver_no_network) else ips.joinToString("\n")
    }

    private fun registerNsd() {
        if (registration != null) return
        val nsd = getSystemService(NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = Protocol.SERVICE_TYPE
            port = Protocol.PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdManager = nsd
            registration = l
        } catch (e: Exception) {
            Log.w(TAG, "NSD unavailable", e)
        }
    }

    private fun unregisterNsd() {
        registration?.let {
            try { nsdManager?.unregisterService(it) } catch (_: Exception) {}
        }
        registration = null
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private companion object {
        const val TAG = "ReceiverActivity"
    }
}
