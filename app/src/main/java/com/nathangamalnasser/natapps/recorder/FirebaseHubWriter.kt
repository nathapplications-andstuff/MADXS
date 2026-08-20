package com.nathangamalnasser.natapps.recorder

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.util.UUID

/**
 * Writes live sensor data to Firebase RTDB (sessions/{id}/...) at 10Hz and
 * a session summary to Firestore on stop, per the schema in CLAUDE.md.
 * uid is the real signed-in Firebase Auth uid (MainActivity gates recording
 * behind sign-in, so currentUser should never be null in practice here —
 * the SharedPreferences UUID is kept only as a last-resort fallback so a
 * stray call never crashes instead of just mis-attributing one session).
 */
class FirebaseHubWriter(context: Context) {

    companion object {
        private const val RTDB_HZ_MS = 100L   // 10 Hz
        // Excludes 0/O/1/I/L — avoids ambiguous characters when read aloud or handwritten
        private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val CODE_LEN = 5
    }

    // Short alias for sessionId so people can type/say something shorter than a 13-digit
    // timestamp. DEBT: no collision check on write — at ~33^5 combinations and low session
    // volume the odds are negligible, but a colliding code would silently point at the wrong
    // session. Add a read-before-write retry if this ever needs to be bulletproof.
    var lastShortCode: String = ""; private set

    private val db = FirebaseDatabase.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val uid: String = FirebaseAuth.getInstance().currentUser?.uid ?: context
        .getSharedPreferences("madxs_prefs", Context.MODE_PRIVATE)
        .let { prefs ->
            prefs.getString("device_uid", null) ?: UUID.randomUUID().toString()
                .also { prefs.edit().putString("device_uid", it).apply() }
        }

    private var sessionId: Long = 0L
    private var lastImuWriteMs = mutableMapOf<String, Long>()

    fun startSession(sessionId: Long, sport: String, contrailStyle: String) {
        this.sessionId = sessionId
        lastImuWriteMs.clear()
        lastShortCode = (1..CODE_LEN).map { CODE_CHARS.random() }.joinToString("")
        sessionRef().child("meta").setValue(
            mapOf(
                "uid" to uid,
                "sport" to sport,
                "startTime" to sessionId,
                "status" to "recording",
                "contrailStyle" to contrailStyle
            )
        )
        db.getReference("codes").child(lastShortCode).setValue(sessionId)
        // Lets a linked friend's FilmActivity find "is this person live right now" without
        // needing a code — keyed by uid so it survives across sessions, cleared on endSession.
        val liveRef = db.getReference("liveByUser").child(uid)
        liveRef.setValue(mapOf("sessionId" to sessionId, "shortCode" to lastShortCode, "sport" to sport))
        // Safety net: if the app dies without calling endSession (crash, killed process),
        // don't leave a friend's quick-join pointing at a dead session forever.
        liveRef.onDisconnect().removeValue()
    }

    // Lets a friend filming this session (ar-film.html) render the same contrail style live
    fun setContrailStyle(style: String) {
        if (sessionId == 0L) return
        sessionRef().child("meta").child("contrailStyle").setValue(style)
    }

    // Lets any live viewer (tracer-real.html, boxing-realtime-viewer.html, ar-film.html)
    // show an "on break" indicator while the double-tap pause is active.
    fun setPaused(paused: Boolean) {
        if (sessionId == 0L) return
        sessionRef().child("meta").child("paused").setValue(paused)
    }

    fun writeImu(side: String, sample: JSONObject) {
        if (sessionId == 0L) return
        val now = System.currentTimeMillis()
        val last = lastImuWriteMs[side] ?: 0L
        if (now - last < RTDB_HZ_MS) return
        lastImuWriteMs[side] = now
        sessionRef().child(side).child("latest").setValue(jsonToMap(sample))
    }

    fun writeGpsOrigin(lat: Double, lng: Double) {
        if (sessionId == 0L) return
        sessionRef().child("gps").child("origin").setValue(mapOf("lat" to lat, "lng" to lng))
    }

    fun writeGps(t: Long, lat: Double, lng: Double, alt: Double, acc: Float) {
        if (sessionId == 0L) return
        sessionRef().child("gps").child("latest").setValue(
            mapOf("t" to t, "lat" to lat, "lng" to lng, "alt" to alt, "acc" to acc.toDouble())
        )
    }

    fun endSession(sport: String, durationMs: Long, uisRaw: Double, uisScore: Int, localCount: Int) {
        if (sessionId == 0L) return
        sessionRef().child("meta").child("status").setValue("ended")
        db.getReference("liveByUser").child(uid).removeValue()

        val doc = mapOf(
            "uid" to uid,
            "sport" to sport,
            "startTime" to sessionId,
            "durationMs" to durationMs,
            "uisRaw" to uisRaw,
            "uisScore" to uisScore,
            "localCount" to localCount,
            "remoteCount" to 0,
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        firestore.collection("sessions").document(sessionId.toString()).set(doc)

        val userRef = firestore.collection("users").document(uid)
        firestore.runTransaction { txn ->
            val snap = txn.get(userRef)
            val current = snap.getLong("uisHighScore") ?: 0L
            if (uisScore > current) txn.set(userRef, mapOf("uisHighScore" to uisScore), com.google.firebase.firestore.SetOptions.merge())
        }

        sessionId = 0L
    }

    private fun sessionRef() = db.getReference("sessions").child(sessionId.toString())

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { key -> map[key] = obj.get(key) }
        return map
    }
}
