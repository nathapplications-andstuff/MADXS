package com.nathangamalnasser.natapps.recorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.nathangamalnasser.natapps.recorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: RecordingService? = null
    private var bound = false
    private var currentSport = "rollerblade"
    private var currentSide = "left"
    private var contrailStyleIdx = 0

    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, b: IBinder) {
            service = (b as RecordingService.LocalBinder).get()
            bound = true
            // Binding is async: sport/side toggles tapped (or defaulted) before this point
            // never reached the service. Re-sync so the UI selection is the single source of truth.
            service?.sport = currentSport
            service?.deviceSide = currentSide
            wireCallbacks()
            refreshUi()
        }
        override fun onServiceDisconnected(name: ComponentName) { bound = false; service = null }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        writeMyEmailToFirestore()
        requestNeededPermissions()
        val intent = Intent(this, RecordingService::class.java)
        startService(intent)
        bindService(intent, svcConn, Context.BIND_AUTO_CREATE)

        setupClicks()
        setSportMode("rollerblade")
        setMode("left")
        updateLiveUrl()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) unbindService(svcConn)
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !has(Manifest.permission.POST_NOTIFICATIONS)) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }

    private fun updateLiveUrl() {
        val svc = service
        binding.tvLiveUrl.text = if (svc?.recState == RecordingService.RecState.RECORDING) {
            val page = if (currentSport == "boxing") "boxing-realtime-viewer.html" else "tracer-real.html"
            "$LIVE_HOST/$page?sid=${svc.sessionId}"
        } else {
            LIVE_HOST.removePrefix("https://")
        }
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ── Service wiring ────────────────────────────────────────────────────────

    private fun wireCallbacks() {
        val svc = service ?: return
        svc.onStateChanged = { runOnUiThread { refreshUi() } }
        svc.onTimerTick    = { elapsed, count, peak ->
            runOnUiThread {
                val min = elapsed / 60000; val sec = (elapsed / 1000) % 60
                binding.tvTimer.text      = "%02d:%02d".format(min, sec)
                binding.tvSampleCount.text = "Samples: $count"
                binding.tvPeakAccel.text   = "Peak: ${"%.2f".format(peak / 9.81)}g"
            }
        }
        svc.onSensorUpdate = { ax, ay, az, gx, gy, gz ->
            runOnUiThread {
                binding.tvAx.text = "%.2f".format(ax); binding.tvAy.text = "%.2f".format(ay)
                binding.tvAz.text = "%.2f".format(az); binding.tvGx.text = "%.3f".format(gx)
                binding.tvGy.text = "%.3f".format(gy); binding.tvGz.text = "%.3f".format(gz)
            }
        }
        svc.onGyroStatus = { working ->
            runOnUiThread {
                if (working) {
                    binding.tvGyroStatus.text = "GYRO: OK  ●"
                    binding.tvGyroStatus.setTextColor(getColor(R.color.green))
                } else {
                    binding.tvGyroStatus.text = "GYRO: DEAD  ✕  (orientation unavailable)"
                    binding.tvGyroStatus.setTextColor(getColor(R.color.stop_red))
                }
            }
        }
        svc.onGpsStatus = { hasGps, count ->
            runOnUiThread {
                if (!hasGps) {
                    binding.tvGpsStatus.text = "GPS: waiting for fix…"
                    binding.tvGpsStatus.setTextColor(getColor(R.color.muted))
                } else {
                    binding.tvGpsStatus.text = "GPS: $count pts  ●"
                    binding.tvGpsStatus.setTextColor(getColor(R.color.green))
                }
            }
        }
        updateLiveUrl()
    }

    // ── Clicks ────────────────────────────────────────────────────────────────

    private fun setupClicks() {
        binding.btnLeft.setOnClickListener  { setMode("left") }
        binding.btnRight.setOnClickListener { setMode("right") }

        binding.btnRollerblade.setOnClickListener { setSportMode("rollerblade") }
        binding.btnBoxing.setOnClickListener      { setSportMode("boxing") }

        binding.btnRecord.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.recState == RecordingService.RecState.RECORDING) {
                svc.stopRecording()
            } else {
                val input = EditText(this).apply {
                    hint = "Session name (optional)"
                    setSingleLine(true)
                    setPadding(48, 32, 48, 16)
                }
                AlertDialog.Builder(this)
                    .setTitle("New Session")
                    .setView(input)
                    .setPositiveButton("START") { _, _ ->
                        svc.startRecording(input.text.toString().trim())
                        showLiveQr()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        binding.tvLiveUrl.setOnClickListener { showLiveQr() }
        binding.btnWatchLive.setOnClickListener { openLiveInBrowser() }
        binding.btnContrailStyle.setOnClickListener { cycleContrailStyle() }

        binding.btnSessions.setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }

        binding.btnFilmLive.setOnClickListener {
            startActivity(Intent(this, FilmActivity::class.java))
        }

        binding.btnFriends.setOnClickListener {
            startActivity(Intent(this, FriendsActivity::class.java))
        }
    }

    // Lets FriendsActivity's "add by email" search find this account —
    // merge, not overwrite, so it never clobbers other fields on this doc (e.g. uisHighScore).
    private fun writeMyEmailToFirestore() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        val email = user.email ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .set(mapOf("email" to email), com.google.firebase.firestore.SetOptions.merge())
    }

    // ── QR code ───────────────────────────────────────────────────────────────

    private fun liveViewerUrl(): String? {
        val svc = service
        if (svc == null || svc.recState != RecordingService.RecState.RECORDING) return null
        val page = if (currentSport == "boxing") "boxing-realtime-viewer.html" else "tracer-real.html"
        var url = "$LIVE_HOST/$page?sid=${svc.sessionId}"
        if (svc.sessionName.isNotEmpty()) {
            url += "&name=" + java.net.URLEncoder.encode(svc.sessionName, "UTF-8")
        }
        return url
    }

    private fun openLiveInBrowser() {
        val url = liveViewerUrl()
        if (url == null) {
            android.widget.Toast.makeText(this, "Start a recording first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No browser found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLiveQr() {
        val url = liveViewerUrl()
        if (url == null) {
            android.widget.Toast.makeText(this, "Start a recording first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        showQrDialog(url, "Scan to watch live from anywhere", service?.shortCode ?: "")
    }

    fun showQrDialog(url: String, subtitle: String, shortCode: String = "") {
        val qr = ImageView(this).apply {
            setImageBitmap(generateQr(url, 512))
            setPadding(48, 32, 48, 8)
        }
        val message = if (shortCode.isNotEmpty()) "$url\n\nOr type this code to watch: $shortCode" else url
        AlertDialog.Builder(this)
            .setTitle(subtitle)
            .setMessage(message)
            .setView(qr)
            .setPositiveButton("OK", null)
            .setNegativeButton("WATCH LIVE") { _, _ -> openLiveInBrowser() }
            .setNeutralButton("SHARE LINK") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                startActivity(Intent.createChooser(send, "Share live link"))
            }
            .show()
    }

    // ── Contrail style (for a friend's live-filming session, ar-film.html) ────

    private fun cycleContrailStyle() {
        contrailStyleIdx = (contrailStyleIdx + 1) % CONTRAIL_STYLES.size
        val style = CONTRAIL_STYLES[contrailStyleIdx]
        binding.btnContrailStyle.text = "✨  CONTRAIL: $style"
        service?.setContrailStyle(style)
    }

    // ── Mode ──────────────────────────────────────────────────────────────────

    // MaterialComponents buttons paint backgroundTint over setBackgroundColor,
    // so tint is the only channel that actually shows
    private fun android.widget.Button.tint(bgCol: Int, txtCol: Int) {
        backgroundTintList = android.content.res.ColorStateList.valueOf(bgCol)
        setTextColor(txtCol)
    }

    private fun setSportMode(sport: String) {
        currentSport = sport
        service?.sport = sport
        val accent  = getColor(R.color.accent)
        val surface = getColor(R.color.surface)
        val bg      = getColor(R.color.bg)

        if (sport == "rollerblade") {
            binding.btnRollerblade.tint(accent, bg)
            binding.btnBoxing.tint(surface, accent)
        } else {
            binding.btnRollerblade.tint(surface, accent)
            binding.btnBoxing.tint(accent, bg)
        }
        setMode(currentSide)   // refresh the pocket/wrist hint text for the new sport
    }

    private fun setMode(side: String) {
        currentSide = side
        service?.deviceSide = side
        val accent   = getColor(R.color.accent)
        val surface  = getColor(R.color.surface)
        val bg       = getColor(R.color.bg)

        if (side == "left") {
            binding.btnLeft.tint(accent, bg)
            binding.btnRight.tint(surface, accent)
            binding.tvModeHint.text = if (currentSport == "boxing") "Left wrist" else "Left pocket"
        } else {
            binding.btnLeft.tint(surface, accent)
            binding.btnRight.tint(accent, bg)
            binding.tvModeHint.text = if (currentSport == "boxing") "Right wrist" else "Right pocket"
        }
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private fun refreshUi() {
        val recording = service?.recState == RecordingService.RecState.RECORDING
        binding.btnRecord.text = if (recording) "■  STOP" else "●  START RECORDING"
        binding.btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (recording) R.color.stop_red else R.color.accent)
        )
        binding.btnWatchLive.visibility = if (recording) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnContrailStyle.visibility = if (recording) android.view.View.VISIBLE else android.view.View.GONE
        if (!recording) {
            binding.tvTimer.text       = "00:00"
            binding.tvSampleCount.text = "Samples: 0"
            binding.tvPeakAccel.text   = "Peak: 0.0g"
        }
        updateLiveUrl()
    }

    companion object {
        const val LIVE_HOST = "https://madxtreamsports.web.app"

        // Must match CONTRAIL_STYLES in ar-film.html / tracer-real.html exactly —
        // this is the value a friend's live-filming session reads from Firebase.
        val CONTRAIL_STYLES = listOf("NEON", "FIRE", "RAINBOW", "ELECTRIC", "SPARKLE", "PLASMA")

        fun generateQr(text: String, size: Int): Bitmap {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            return bmp
        }
    }
}
