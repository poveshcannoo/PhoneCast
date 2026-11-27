package dev.phonecast

import android.Manifest
import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Sender UI (phone). On a TV/projector it jumps straight to receiver mode. */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var stopButton: Button
    private lateinit var searchingText: TextView
    private lateinit var receiversBox: LinearLayout
    private lateinit var hostInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var audioCheck: CheckBox

    private var discovery: ReceiverDiscovery? = null
    private var pendingHost: String? = null
    private var pendingPort = Protocol.PORT
    private val stateListener: (StreamState.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && isTelevision()) {
            startActivity(Intent(this, ReceiverActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("phonecast", MODE_PRIVATE)

        statusText = findViewById(R.id.status)
        stopButton = findViewById(R.id.stop)
        searchingText = findViewById(R.id.searching)
        receiversBox = findViewById(R.id.receivers)
        hostInput = findViewById(R.id.host)
        qualitySpinner = findViewById(R.id.quality)
        audioCheck = findViewById(R.id.audio)

        qualitySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Presets.ALL.map { it.label }
        )
        qualitySpinner.setSelection(prefs.getInt(PREF_PRESET, Presets.DEFAULT).coerceIn(0, Presets.ALL.lastIndex))

        if (Build.VERSION.SDK_INT >= 29) {
            audioCheck.isChecked = prefs.getBoolean(PREF_AUDIO, true)
        } else {
            audioCheck.isChecked = false
            audioCheck.isEnabled = false
            audioCheck.setText(R.string.audio_unsupported)
        }

        hostInput.setText(prefs.getString(PREF_HOST, ""))
        hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connectManual(); true } else false
        }
        findViewById<Button>(R.id.connect).setOnClickListener { connectManual() }
        stopButton.setOnClickListener { CastService.stop(this) }
        findViewById<Button>(R.id.receive).setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }

        savedInstanceState?.let {
            pendingHost = it.getString(STATE_HOST)
            pendingPort = it.getInt(STATE_PORT, Protocol.PORT)
        }
    }

    override fun onStart() {
        super.onStart()
        StreamState.observe(stateListener)
        discovery = ReceiverDiscovery(this, ::showReceivers).also { it.start() }
    }

    override fun onStop() {
        StreamState.remove(stateListener)
        discovery?.stop()
        discovery = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_HOST, pendingHost)
        outState.putInt(STATE_PORT, pendingPort)
    }

    private fun render(s: StreamState.State) {
        statusText.text = when (s.phase) {
            StreamState.Phase.IDLE -> s.message ?: getString(R.string.status_idle)
            StreamState.Phase.CONNECTING -> getString(R.string.status_connecting, s.target)
            StreamState.Phase.LIVE -> getString(R.string.status_live, s.target) + (s.message?.let { "\n$it" } ?: "")
        }
        stopButton.visibility = if (s.phase == StreamState.Phase.IDLE) View.GONE else View.VISIBLE
    }

    private fun showReceivers(list: List<ReceiverDiscovery.Found>) {
        receiversBox.removeAllViews()
        searchingText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val margin = (8 * resources.displayMetrics.density).toInt()
        for (r in list) {
            val b = Button(this, null, 0, R.style.Btn)
            b.text = getString(R.string.receiver_item, r.name, r.host)
            b.setOnClickListener { beginCast(r.host, r.port) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = margin
            receiversBox.addView(b, lp)
        }
    }

    private fun connectManual() {
        val raw = hostInput.text.toString().trim().removePrefix("http://").trimEnd('/')
        if (raw.isEmpty()) {
            hostInput.error = getString(R.string.enter_address)
            return
        }
        val colon = raw.lastIndexOf(':')
        val (host, port) = if (colon > 0 && raw.indexOf(':') == colon) {
            raw.substring(0, colon) to (raw.substring(colon + 1).toIntOrNull() ?: Protocol.PORT)
        } else {
            raw to Protocol.PORT
        }
        prefs.edit().putString(PREF_HOST, raw).apply()
        beginCast(host, port)
    }

    private fun beginCast(host: String, port: Int) {
        if (StreamState.current.phase != StreamState.Phase.IDLE) {
            Toast.makeText(this, R.string.already_sharing, Toast.LENGTH_SHORT).show()
            return
        }
        pendingHost = host
        pendingPort = port
        prefs.edit()
            .putInt(PREF_PRESET, qualitySpinner.selectedItemPosition)
            .putBoolean(PREF_AUDIO, audioCheck.isChecked)
            .apply()

        val needed = ArrayList<String>()
        if (audioCheck.isChecked && Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) askForCapture() else requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Continue either way: without RECORD_AUDIO we just stream video only.
        if (requestCode == REQ_PERMISSIONS && pendingHost != null) askForCapture()
    }

    private fun askForCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            statusText.setText(R.string.capture_unavailable)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val host = pendingHost ?: return
        if (resultCode == RESULT_OK && data != null) {
            val audio = audioCheck.isChecked && Build.VERSION.SDK_INT >= 29 && granted(Manifest.permission.RECORD_AUDIO)
            CastService.start(this, resultCode, data, host, pendingPort, qualitySpinner.selectedItemPosition, audio)
        } else {
            statusText.setText(R.string.capture_denied)
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isTelevision(): Boolean {
        val ui = getSystemService(UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private companion object {
        const val REQ_CAPTURE = 10
        const val REQ_PERMISSIONS = 11
        const val PREF_HOST = "host"
        const val PREF_PRESET = "preset"
        const val PREF_AUDIO = "audio"
        const val STATE_HOST = "pendingHost"
        const val STATE_PORT = "pendingPort"
    }
}
