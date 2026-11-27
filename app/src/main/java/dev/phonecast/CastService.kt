package dev.phonecast

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Foreground service that owns the MediaProjection, the encoder and the connection.
 * All state changes run on one "control" thread to keep things race-free.
 */
class CastService : Service() {

    companion object {
        private const val TAG = "CastService"
        const val ACTION_START = "dev.phonecast.action.START"
        const val ACTION_STOP = "dev.phonecast.action.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PRESET = "preset"
        private const val EXTRA_AUDIO = "audio"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, resultCode: Int, data: Intent, host: String, port: Int, preset: Int, audio: Boolean) {
            val i = Intent(context, CastService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_PRESET, preset)
                .putExtra(EXTRA_AUDIO, audio)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var controlThread: HandlerThread
    private lateinit var control: Handler

    private var projection: MediaProjection? = null
    private var writer: PacketWriter? = null
    @Volatile private var encoder: ScreenEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audio: AudioCapturer? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var preset = Presets.get(Presets.DEFAULT)
    private var srcW = 0
    private var srcH = 0
    private var dpi = 320
    private var started = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controlThread = HandlerThread("pc-control").apply { start() }
        control = Handler(controlThread.looper)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (started) control.post { shutdown(null) } else stopSelf()
            ACTION_START -> if (!started) {
                begin(intent)
            } else {
                // A previous session is still winding down on this instance. We must still call
                // startForeground() (we were started with startForegroundService) to avoid a crash.
                startForegroundCompat(buildNotification(getString(R.string.status_idle)))
                StreamState.update(StreamState.Phase.IDLE, null, getString(R.string.try_again))
                if (stopping) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        control.post { shutdown(null) }
        controlThread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    private fun begin(intent: Intent) {
        started = true
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.PORT)
        val wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        preset = Presets.get(intent.getIntExtra(EXTRA_PRESET, Presets.DEFAULT))

        // Must be foreground (type mediaProjection) BEFORE getMediaProjection() on Android 14+.
        startForegroundCompat(buildNotification(getString(R.string.notif_connecting, host)))
        StreamState.update(StreamState.Phase.CONNECTING, host, null)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            if (data != null && host.isNotEmpty()) mpm.getMediaProjection(resultCode, data) else null
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (mp == null) {
            control.post { shutdown(getString(R.string.err_projection)) }
            return
        }
        projection = mp
        // Callbacks are delivered on the control thread.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = shutdown(null)

            // Android 14+: called when capture starts and whenever the captured area changes
            // size (rotation, or single-app sharing).
            override fun onCapturedContentResize(width: Int, height: Int) = onSourceSize(width, height)
        }, control)

        control.post { connectAndStream(host, port, wantAudio) }
    }

    private fun connectAndStream(host: String, port: Int, wantAudio: Boolean) {
        if (stopping) return
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5000)

            val w = PacketWriter(s) { control.post { shutdown(getString(R.string.err_connection_lost)) } }
            w.onBacklog = { encoder?.requestKeyframe() }
            w.start()
            writer = w
            startBackChannel(s)
            wifiLock = NetUtils.acquireWifiLock(this, "phonecast-sender")

            val m = displayMetrics()
            dpi = m.densityDpi
            startVideo(m.widthPixels, m.heightPixels)
            if (wantAudio) startAudio(w)
            if (Build.VERSION.SDK_INT < 34) watchRotation()

            updateNotification(getString(R.string.notif_live, host))
            val note = if (wantAudio && audio == null) getString(R.string.audio_unavailable) else null
            StreamState.update(StreamState.Phase.LIVE, host, note)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start streaming", e)
            shutdown(getString(R.string.err_connect, host, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Reads single-byte requests from the receiver; EOF means the receiver went away. */
    private fun startBackChannel(s: Socket) {
        Thread({
            try {
                val input = s.getInputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == Protocol.REQ_KEYFRAME) encoder?.requestKeyframe()
                }
            } catch (_: IOException) {
            }
            control.post { shutdown(getString(R.string.receiver_closed)) }
        }, "pc-backchannel").apply {
            isDaemon = true
            start()
        }
    }

    // ---------------------------------------------------------------- video

    private fun startVideo(width: Int, height: Int) {
        val mp = projection ?: return
        val w = writer ?: return
        srcW = width
        srcH = height
        val enc = ScreenEncoder.create(width, height, preset)
        // Header first so the receiver can set up its decoder before config/frames arrive.
        w.send(Protocol.VIDEO_HEADER, 0, ByteBuffer.allocate(8).putInt(enc.width).putInt(enc.height).array())
        enc.start(w::send)
        encoder = enc
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "PhoneCast", enc.width, enc.height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.inputSurface, null, null
            )
        } else {
            // Android 14+ only allows one createVirtualDisplay per projection, so resize instead.
            vd.resize(enc.width, enc.height, dpi)
            vd.surface = enc.inputSurface
        }
    }

    /** Phone rotated (or captured area changed): rebuild the encoder at the new shape. */
    private fun onSourceSize(width: Int, height: Int) {
        if (stopping || encoder == null || width <= 0 || height <= 0) return
        if (width == srcW && height == srcH) return
        Log.i(TAG, "Source size ${srcW}x$srcH -> ${width}x$height")
        val old = encoder
        encoder = null
        virtualDisplay?.surface = null
        old?.stop()
        try {
            startVideo(width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Re-creating encoder failed", e)
            shutdown(getString(R.string.err_projection))
        }
    }

    private fun watchRotation() {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val m = displayMetrics()
                onSourceSize(m.widthPixels, m.heightPixels)
            }
        }
        dm.registerDisplayListener(l, control)
        displayListener = l
    }

    private fun displayMetrics(): DisplayMetrics {
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(m)
        return m
    }

    // ---------------------------------------------------------------- audio

    private fun startAudio(w: PacketWriter) {
        if (Build.VERSION.SDK_INT < 29) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val mp = projection ?: return
        audio = try {
            AudioCapturer(mp, w::send).also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture unavailable", e)
            null
        }
    }

    // ---------------------------------------------------------------- stop

    private fun shutdown(message: String?) {
        if (stopping) return
        stopping = true
        displayListener?.let { (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        displayListener = null
        audio?.stop()
        audio = null
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        writer?.close()
        writer = null
        projection?.let { p ->
            projection = null
            try { p.stop() } catch (_: Exception) {}
        }
        NetUtils.release(wifiLock)
        wifiLock = null
        StreamState.update(StreamState.Phase.IDLE, null, message)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, getString(R.string.stop), stop).build())
            .build()
    }
}
