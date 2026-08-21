package com.nathangamalnasser.natapps.recorder

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/**
 * Writes live sensor data to Firebase RTDB (sessions/{id}/...) at 10Hz and
 * a session summary to Firestore on stop.
 * uid is always the current Firebase Auth uid.
 */
class FirebaseHubWriter(@Suppress("UNUSED_PARAMETER") context: Context) {

    companion object {
        private const val RTDB_HZ_MS = 100L
        private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val CODE_LEN = 5
    }

    var lastShortCode: String = ""; private set
    var lastWriteError: String? = null; private set
    var onWriteResult: ((ok: Boolean, message: String) -> Unit)? = null
    var isHost: Boolean = true; private set

    private val db = FirebaseDatabase.getInstance(HubAuth.DATABASE_URL)
    private val firestore = FirebaseFirestore.getInstance()

    private var sessionId: Long = 0L
    private var lastImuWriteMs = mutableMapOf<String, Long>()

    private fun uid(): String? = HubAuth.uidForWrite(FirebaseAuth.getInstance().currentUser?.uid)

    fun startSession(
        sessionId: Long,
        sport: String,
        contrailStyle: String,
        side: String,
        onReady: () -> Unit = {},
        onErr: (String) -> Unit = {}
    ) {
        val uid = uid()
        if (uid == null) {
            val msg = "not signed in"
            reportWrite(false, msg)
            onErr(msg)
            return
        }
        this.sessionId = sessionId
        this.isHost = true
        lastImuWriteMs.clear()
        lastShortCode = (1..CODE_LEN).map { CODE_CHARS.random() }.joinToString("")
        val meta = mapOf(
            "uid" to uid,
            "sport" to sport,
            "startTime" to sessionId,
            "status" to "recording",
            "contrailStyle" to contrailStyle,
            "memberCount" to 1
        )
        sessionRef().child("meta").setValue(meta)
            .addOnSuccessListener {
                sessionRef().child("members").child(uid).setValue(
                    mapOf("role" to "host", "side" to side)
                ).addOnSuccessListener {
                    db.getReference("codes").child(lastShortCode).setValue(sessionId)
                        .addOnFailureListener { e -> reportWrite(false, e.message ?: "code write failed") }
                    val liveRef = db.getReference("liveByUser").child(uid)
                    liveRef.setValue(mapOf("sessionId" to sessionId, "shortCode" to lastShortCode, "sport" to sport))
                    liveRef.onDisconnect().removeValue()
                    reportWrite(true, "meta ok")
                    onReady()
                }.addOnFailureListener { e ->
                    val msg = e.message ?: "member write failed"
                    reportWrite(false, msg)
                    onErr(msg)
                }
            }
            .addOnFailureListener { e ->
                val msg = e.message ?: "meta write failed"
                reportWrite(false, msg)
                onErr(msg)
            }
    }

    fun joinSession(
        code: String,
        sport: String,
        side: String,
        onOk: (Long) -> Unit,
        onErr: (String) -> Unit
    ) {
        val uid = uid()
        if (uid == null) {
            onErr("not signed in")
            return
        }
        val anonymous = FirebaseAuth.getInstance().currentUser?.isAnonymous == true
        SessionMembership.canJoin(anonymous, 0)?.let { msg ->
            if (msg == SessionMembership.JOIN_NEEDS_SIGN_IN) {
                onErr(msg)
                return
            }
        }
        val key = code.trim().uppercase()
        db.getReference("codes").child(key).get()
            .addOnSuccessListener codeLookup@{ snap ->
                val sid = when (val raw = snap.value) {
                    is Long -> raw
                    is Int -> raw.toLong()
                    is String -> raw.toLongOrNull()
                    is Double -> raw.toLong()
                    else -> null
                }
                if (sid == null) {
                    onErr("Code not found")
                    return@codeLookup
                }
                val metaRef = db.getReference("sessions").child(sid.toString()).child("meta")
                metaRef.get().addOnSuccessListener metaLookup@{ meta ->
                    if (!meta.exists()) {
                        onErr("Session not found")
                        return@metaLookup
                    }
                    if (meta.child("status").getValue(String::class.java) == "ended") {
                        onErr("Session ended")
                        return@metaLookup
                    }
                    val count = meta.child("memberCount").getValue(Long::class.java)?.toInt() ?: 1
                    val gate = SessionMembership.canJoin(false, count)
                    if (gate != null) {
                        onErr(gate)
                        return@metaLookup
                    }
                    metaRef.runTransaction(object : Transaction.Handler {
                        override fun doTransaction(current: MutableData): Transaction.Result {
                            val n = current.child("memberCount").getValue(Long::class.java) ?: 1L
                            if (n >= SessionMembership.MAX_MEMBERS) return Transaction.abort()
                            current.child("memberCount").value = n + 1
                            return Transaction.success(current)
                        }
                        override fun onComplete(
                            error: com.google.firebase.database.DatabaseError?,
                            committed: Boolean,
                            currentData: com.google.firebase.database.DataSnapshot?
                        ) {
                            if (error != null || !committed) {
                                onErr(SessionMembership.SESSION_FULL)
                                return
                            }
                            sessionId = sid
                            isHost = false
                            lastShortCode = key
                            lastImuWriteMs.clear()
                            sessionRef().child("members").child(uid).setValue(
                                mapOf("role" to "member", "side" to side, "sport" to sport)
                            ).addOnSuccessListener {
                                val liveRef = db.getReference("liveByUser").child(uid)
                                liveRef.setValue(mapOf("sessionId" to sid, "shortCode" to key, "sport" to sport))
                                liveRef.onDisconnect().removeValue()
                                onOk(sid)
                            }.addOnFailureListener { e ->
                                onErr(e.message ?: "Join failed")
                            }
                        }
                    })
                }.addOnFailureListener { e -> onErr(e.message ?: "Join failed") }
            }
            .addOnFailureListener { e -> onErr(e.message ?: "Code not found") }
    }

    fun setContrailStyle(style: String) {
        if (sessionId == 0L || !isHost) return
        sessionRef().child("meta").child("contrailStyle").setValue(style)
    }

    fun setPaused(paused: Boolean) {
        if (sessionId == 0L || !isHost) return
        sessionRef().child("meta").child("paused").setValue(paused)
    }

    fun writeImu(side: String, sample: JSONObject) {
        if (sessionId == 0L) return
        val now = System.currentTimeMillis()
        val last = lastImuWriteMs[side] ?: 0L
        if (now - last < RTDB_HZ_MS) return
        lastImuWriteMs[side] = now
        val map = jsonToMap(sample)
        sessionRef().child(side).child("latest").setValue(map)
        val uid = uid() ?: return
        sessionRef().child("devices").child(uid).child("latest").setValue(map)
    }

    fun writeGpsOrigin(lat: Double, lng: Double) {
        if (sessionId == 0L || !isHost) return
        sessionRef().child("gps").child("origin").setValue(mapOf("lat" to lat, "lng" to lng))
    }

    fun writeGps(t: Long, lat: Double, lng: Double, alt: Double, acc: Float) {
        if (sessionId == 0L || !isHost) return
        sessionRef().child("gps").child("latest").setValue(
            mapOf("t" to t, "lat" to lat, "lng" to lng, "alt" to alt, "acc" to acc.toDouble())
        )
    }

    fun endSession(sport: String, durationMs: Long, uisRaw: Double, uisScore: Int, localCount: Int) {
        if (sessionId == 0L) return
        val uid = uid()
        val host = isHost
        val sid = sessionId
        if (host) {
            sessionRef().child("meta").child("status").setValue("ended")
        } else if (uid != null) {
            sessionRef().child("members").child(uid).removeValue()
        }
        if (uid != null) db.getReference("liveByUser").child(uid).removeValue()

        if (host && uid != null) {
            val doc = mapOf(
                "uid" to uid,
                "sport" to sport,
                "startTime" to sid,
                "durationMs" to durationMs,
                "uisRaw" to uisRaw,
                "uisScore" to uisScore,
                "localCount" to localCount,
                "remoteCount" to 0,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            firestore.collection("sessions").document(sid.toString()).set(doc)

            val userRef = firestore.collection("users").document(uid)
            firestore.runTransaction { txn ->
                val snap = txn.get(userRef)
                val current = snap.getLong("uisHighScore") ?: 0L
                if (uisScore > current) txn.set(userRef, mapOf("uisHighScore" to uisScore), com.google.firebase.firestore.SetOptions.merge())
            }
        }

        sessionId = 0L
        isHost = true
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
