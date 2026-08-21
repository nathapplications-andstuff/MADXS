package com.nathangamalnasser.natapps.recorder

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/**
 * Writes live sensor data to Firebase RTDB (sessions/{id}/...) at 10Hz and
 * a session summary to Firestore on stop.
 * uid is always the current Firebase Auth uid. A device UUID must never be
 * written — RTDB rules require meta.uid === auth.uid.
 */
class FirebaseHubWriter(@Suppress("UNUSED_PARAMETER") context: Context) {

    companion object {
        private const val RTDB_HZ_MS = 100L   // 10 Hz
        // Excludes 0/O/1/I/L — avoids ambiguous characters when read aloud or handwritten
        private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val CODE_LEN = 5
    }

    var lastShortCode: String = ""; private set
    var lastWriteError: String? = null; private set
    var onWriteResult: ((ok: Boolean, message: String) -> Unit)? = null

    private val db = FirebaseDatabase.getInstance(HubAuth.DATABASE_URL)
    private val firestore = FirebaseFirestore.getInstance()

    private var sessionId: Long = 0L
    private var lastImuWriteMs = mutableMapOf<String, Long>()

    private fun uid(): String? = HubAuth.uidForWrite(FirebaseAuth.getInstance().currentUser?.uid)

    fun startSession(sessionId: Long, sport: String, contrailStyle: String) {
        val uid = uid()
        if (uid == null) {
            reportWrite(false, "not signed in")
            return
        }
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
        ).addOnSuccessListener {
            reportWrite(true, "meta ok")
        }.addOnFailureListener { e ->
            reportWrite(false, e.message ?: "meta write failed")
        }
        db.getReference("codes").child(lastShortCode).setValue(sessionId)
            .addOnFailureListener { e -> reportWrite(false, e.message ?: "code write failed") }
        val liveRef = db.getReference("liveByUser").child(uid)
        liveRef.setValue(mapOf("sessionId" to sessionId, "shortCode" to lastShortCode, "sport" to sport))
        liveRef.onDisconnect().removeValue()
    }

    fun setContrailStyle(style: String) {
        if (sessionId == 0L) return
        sessionRef().child("meta").child("contrailStyle").setValue(style)
    }

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
        val uid = uid()
        sessionRef().child("meta").child("status").setValue("ended")
        if (uid != null) db.getReference("liveByUser").child(uid).removeValue()

        if (uid != null) {
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
        }

        sessionId = 0L
    }

    private fun reportWrite(ok: Boolean, message: String) {
        lastWriteError = if (ok) null else message
        onWriteResult?.invoke(ok, message)
    }

    private fun sessionRef() = db.getReference("sessions").child(sessionId.toString())

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { key -> map[key] = obj.get(key) }
        return map
    }
}
