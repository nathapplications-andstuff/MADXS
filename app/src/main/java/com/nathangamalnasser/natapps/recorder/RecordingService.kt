package com.nathangamalnasser.natapps.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean  // Bug 4

class RecordingService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL_ID = "rec_channel"
        private const val NOTIF_ID   = 1
        private const val SAMPLE_MS  = 20L   // 50 Hz
    }

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() { fun get() = this@RecordingService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── State ─────────────────────────────────────────────────────────────────

    enum class RecState { IDLE, RECORDING }

    var recState   = RecState.IDLE; private set
    var deviceSide = "left"
    var sport      = "rollerblade"
    var contrailStyle = "NEON"; private set

    // Separate from the property above on purpose: firebaseHub is lateinit (ready only after
    // onCreate), so this can't be a property setter — that would fire during construction,
    // before onCreate runs, and crash with UninitializedPropertyAccessException.
    fun setContrailStyle(style: String) {
        contrailStyle = style
        firebaseHub.setContrailStyle(style)
    }

    // Double-tap-to-pause: no on-screen indicator by design (phone stays in the pocket) —
    // only a haptic pulse confirms the toggle, and remote viewers see an "on break" badge.
    var isPaused = false; private set
    private var pausedAccumMs = 0L
    private var pauseStartMs  = 0L
    private var tapDetector   = DoubleTapDetector()

    var onStateChanged: ((RecState) -> Unit)?                           = null
    var onTimerTick:    ((Long, Int, Double) -> Unit)?                  = null
    var onSensorUpdate: ((Float, Float, Float, Float, Float, Float) -> Unit)? = null
    var onGyroStatus:   ((Boolean) -> Unit)?                            = null
    var onGpsStatus:    ((Boolean, Int) -> Unit)?                       = null
    var onHubError:     ((String) -> Unit)?                             = null

    var sessionName = ""

    // ── Internals ─────────────────────────────────────────────────────────────

    private lateinit var sensorManager:   SensorManager
    private var accelSensor: Sensor?    = null
    private var gyroSensor:  Sensor?    = null
    private lateinit var wakeLock:        PowerManager.WakeLock
    private lateinit var locationManager: LocationManager
    private lateinit var peerClient:      PeerJSClient
    private lateinit var relayServer:     RelayServer
    private lateinit var firebaseHub:     FirebaseHubWriter

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    @Volatile private var ax = 0f; @Volatile private var ay = 9.81f; @Volatile private var az = 0f
    @Volatile private var gx = 0f; @Volatile private var gy = 0f;    @Volatile private var gz = 0f

    private var startTime    = 0L
    val sessionId: Long get() = startTime
    val shortCode: String get() = firebaseHub.lastShortCode
    private var lastSampleMs = 0L
    private var peakAccel    = 0.0
    private var peakGyro     = 0.0

    private var gyroSampleCount  = 0
    private var gyroNonZeroCount = 0
    private val gyroStatusFired  = AtomicBoolean(false)  // Bug 4 fix: was @Volatile Boolean

    private val localSamples = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val gpsTrack     = Collections.synchronizedList(mutableListOf<JSONObject>())
    private var gpsFixCount  = 0
    private var gpsRegistered   = false   // Bug 3 fix: track whether listener was registered
    private var gpsOriginSent   = false   // Live streaming: send origin once per session

    private val scope    = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timerJob: Job? = null

    // ── GPS listener ──────────────────────────────────────────────────────────

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (recState != RecState.RECORDING || isPaused) return
            gpsFixCount++
            val t = System.currentTimeMillis() - startTime
            val pt = JSONObject().apply {
                put("t",   t)
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("alt", loc.altitude.round(1))
                put("acc", loc.accuracy.toDouble().round(1))
            }
            gpsTrack.add(pt)

            // Live streaming: send GPS origin on very first fix
            if (!gpsOriginSent) {
                gpsOriginSent = true
                peerClient.sendGpsOrigin(loc.latitude, loc.longitude)
                firebaseHub.writeGpsOrigin(loc.latitude, loc.longitude)
            }
            // Live streaming: stream every GPS fix to viewer
            peerClient.sendGps(t, loc.latitude, loc.longitude, loc.altitude, loc.accuracy)
            firebaseHub.writeGps(t, loc.latitude, loc.longitude, loc.altitude, loc.accuracy)

            scope.launch(Dispatchers.Main) { onGpsStatus?.invoke(true, gpsFixCount) }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        sensorManager   = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor     = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor      = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MADXS:WL")

        peerClient = PeerJSClient(this)

        relayServer = RelayServer(this)
        relayServer.start()

        firebaseHub = FirebaseHubWriter(this)
        firebaseHub.onWriteResult = { ok, message ->
            if (!ok) onHubError?.invoke(message)
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        peerClient.disconnect()
        relayServer.stop()
        scope.cancel()
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    fun getLocalIp(): String =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "?"

    fun startRecording(name: String = "") {
        if (recState == RecState.RECORDING) return
        sessionName = name
        if (!peerClient.isConnected()) peerClient.connect("localhost", deviceSide)
        recState         = RecState.RECORDING
        startTime        = System.currentTimeMillis()
        firebaseHub.startSession(startTime, sport, contrailStyle)
        lastSampleMs     = 0L
        peakAccel        = 0.0
        peakGyro         = 0.0
        gyroSampleCount  = 0
        gyroNonZeroCount = 0
        gyroStatusFired.set(false)   // Bug 4 fix
        gpsOriginSent    = false
        localSamples.clear()
        gpsTrack.clear()
        gpsFixCount      = 0
        isPaused         = false
        pausedAccumMs    = 0L
        pauseStartMs     = 0L
        tapDetector      = DoubleTapDetector()

        if (!wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60 * 1000L)

        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroSensor,  SensorManager.SENSOR_DELAY_GAME)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 1f, gpsListener, mainLooper)
            gpsRegistered = true   // Bug 3 fix: track registration
            scope.launch(Dispatchers.Main) { onGpsStatus?.invoke(false, 0) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ requires the foreground service type at startForeground time;
            // only claim LOCATION if the permission was actually granted
            var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (gpsRegistered) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, buildNotif("● Recording…  00:00"), fgType)
        } else {
            startForeground(NOTIF_ID, buildNotif("● Recording…  00:00"))
        }

        timerJob = scope.launch {
            while (recState == RecState.RECORDING) {
                delay(1000)
                val elapsed = activeElapsedMs(System.currentTimeMillis())
                val min = elapsed / 60000; val sec = (elapsed / 1000) % 60
                updateNotif("● Recording…  %02d:%02d".format(min, sec))
                withContext(Dispatchers.Main) {
                    onTimerTick?.invoke(elapsed, localSamples.size, peakAccel)
                }
            }
        }
        onStateChanged?.invoke(recState)
    }

    fun stopRecording() {
        if (recState != RecState.RECORDING) return
        recState = RecState.IDLE
        timerJob?.cancel()
        sensorManager.unregisterListener(this)
        // Bug 3 fix: only remove GPS updates if we actually registered
        if (gpsRegistered) {
            try { locationManager.removeUpdates(gpsListener) } catch (_: Exception) {}
            gpsRegistered = false
        }
        if (wakeLock.isHeld) wakeLock.release()
        saveSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        onStateChanged?.invoke(recState)
    }

    /** Wall-clock elapsed since start, minus any time spent paused (including an open pause). */
    private fun activeElapsedMs(nowMs: Long): Long {
        val openPause = if (isPaused) nowMs - pauseStartMs else 0L
        return (nowMs - startTime) - pausedAccumMs - openPause
    }

    private fun togglePause() {
        val now = System.currentTimeMillis()
        isPaused = !isPaused
        if (isPaused) {
            pauseStartMs = now
        } else {
            pausedAccumMs += now - pauseStartMs
        }
        firebaseHub.setPaused(isPaused)
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 60, 70), -1))
        }
    }

    // ── Sensor callbacks ──────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2]
                // Tap detection runs on every raw event (not throttled to SAMPLE_MS) — a
                // tap is only a few tens of ms wide and needs full resolution to catch.
                if (recState == RecState.RECORDING) {
                    val tax = ax.toDouble(); val tay = ay.toDouble(); val taz = az.toDouble()
                    val tgx = gx.toDouble(); val tgy = gy.toDouble(); val tgz = gz.toDouble()
                    val accelMag = Math.sqrt(tax*tax + tay*tay + taz*taz)
                    val gyroMag  = Math.sqrt(tgx*tgx + tgy*tgy + tgz*tgz)
                    if (tapDetector.onSample(System.currentTimeMillis(), accelMag, gyroMag)) togglePause()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2]
                // Bug 4 fix: AtomicBoolean.compareAndSet prevents double-fire from concurrent callbacks
                if (!gyroStatusFired.get()) {
                    gyroSampleCount++
                    if (gx != 0f || gy != 0f || gz != 0f) gyroNonZeroCount++
                    if (gyroSampleCount >= 60 && gyroStatusFired.compareAndSet(false, true)) {
                        val working = gyroNonZeroCount >= 5
                        scope.launch(Dispatchers.Main) { onGyroStatus?.invoke(working) }
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        if (recState != RecState.RECORDING || isPaused || now - lastSampleMs < SAMPLE_MS) return
        lastSampleMs = now

        val dax = ax.toDouble(); val day = ay.toDouble(); val daz = az.toDouble()
        val dgx = gx.toDouble(); val dgy = gy.toDouble(); val dgz = gz.toDouble()
        val accelMag = Math.sqrt(dax*dax + day*day + daz*daz)
        val gyroMag  = Math.sqrt(dgx*dgx + dgy*dgy + dgz*dgz)

        if (accelMag > peakAccel) peakAccel = accelMag
        if (gyroMag  > peakGyro)  peakGyro  = gyroMag

        val sample = JSONObject().apply {
            put("t",         now - startTime)
            put("ax",        dax.round(3));  put("ay", day.round(3));  put("az", daz.round(3))
            put("gx",        dgx.round(4));  put("gy", dgy.round(4));  put("gz", dgz.round(4))
            put("accel_mag", accelMag.round(3))
            put("gyro_mag",  gyroMag.round(4))
        }
        localSamples.add(sample)
        peerClient.send(sample.toString())   // stream to relay → viewer
        firebaseHub.writeImu(deviceSide, sample)   // stream to Firebase RTDB, throttled to 10Hz internally

        scope.launch(Dispatchers.Main) { onSensorUpdate?.invoke(ax, ay, az, gx, gy, gz) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Session persistence ───────────────────────────────────────────────────

    private fun saveSession() {
        val duration = activeElapsedMs(System.currentTimeMillis())
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .also { it.timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(startTime))

        // Bug 5 fix: hold the list's own lock during iteration to prevent ConcurrentModificationException
        val arr = JSONArray()
        synchronized(localSamples) { localSamples.forEach { arr.put(it) } }
        val gpsArr = JSONArray()
        synchronized(gpsTrack) { gpsTrack.forEach { gpsArr.put(it) } }

        val uis = UisScorer.compute(sport, peakAccel, peakGyro)

        val session = JSONObject().apply {
            put("id",            startTime);   put("device",       deviceSide)
            if (sessionName.isNotEmpty()) put("name", sessionName)
            put("timestamp",     iso);         put("duration_ms",  duration)
            put("sport",         sport)
            put("peak_accel_ms2", peakAccel.round(3))
            put("peak_gyro_rads", peakGyro.round(4))
            put("uis_score",     uis.score)
            put("sample_count",  localSamples.size)
            put("gps_count",     gpsTrack.size)
            if (gpsTrack.isNotEmpty()) {
                val first = synchronized(gpsTrack) { gpsTrack[0] }
                put("gps_origin", JSONObject().apply {
                    put("lat", first.getDouble("lat"))
                    put("lng", first.getDouble("lng"))
                })
            }
            put("samples", arr)
            put("gps",     gpsArr)
        }
        File(filesDir, "session_${startTime}.json").writeText(JSONArray().put(session).toString())

        firebaseHub.endSession(sport, duration, uis.raw, uis.score, localSamples.size)
    }

    fun getSessionFiles(): List<File> =
        filesDir.listFiles { f -> f.name.startsWith("session_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun deleteSession(file: File) { file.delete() }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Sensor Recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MADXS").setContentText(text)
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }
}

private fun Double.round(d: Int): Double {
    val f = Math.pow(10.0, d.toDouble())
    return Math.round(this * f) / f
}
