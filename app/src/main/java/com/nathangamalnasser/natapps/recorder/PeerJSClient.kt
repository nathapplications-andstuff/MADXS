package com.nathangamalnasser.natapps.recorder

import android.content.Context
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PeerJSClient(private val context: Context) {

    companion object {
        private const val TAG  = "Relay"
        private const val PORT = 9001
    }

    enum class State { IDLE, CONNECTING, CONNECTED, ERROR }

    var onStateChange: ((State, String) -> Unit)? = null
    var onOpen:  (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var deviceSide = "left"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0,     TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var connected = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect(serverIp: String, side: String) {
        deviceSide = side
        setState(State.CONNECTING, "Connecting to $serverIp…")
        val url = "ws://$serverIp:$PORT"
        ws = http.newWebSocket(Request.Builder().url(url).build(), wsListener)
    }

    fun send(json: String) {
        if (ws == null) return
        try {
            val obj = JSONObject(json)
            obj.put("type",   "sensor")
            obj.put("device", deviceSide)
            ws?.send(obj.toString())
        } catch (_: Exception) {}
    }

    fun sendGpsOrigin(lat: Double, lng: Double) {
        try {
            ws?.send(JSONObject().apply {
                put("type",   "gps_origin")
                put("device", deviceSide)
                put("lat",    lat)
                put("lng",    lng)
            }.toString())
        } catch (_: Exception) {}
    }

    fun sendGps(t: Long, lat: Double, lng: Double, alt: Double, acc: Float) {
        try {
            ws?.send(JSONObject().apply {
                put("type",   "gps")
                put("device", deviceSide)
                put("t",      t)
                put("lat",    lat)
                put("lng",    lng)
                put("alt",    alt)
                put("acc",    acc.toDouble())
            }.toString())
        } catch (_: Exception) {}
    }

    fun isConnected() = connected

    fun disconnect() {
        ws?.close(1000, null)
        ws = null
        connected = false
        setState(State.IDLE, "Disconnected")
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            connected = true
            ws.send(JSONObject().apply {
                put("type",   "register")
                put("role",   "sensor")
                put("device", deviceSide)
            }.toString())
            setState(State.CONNECTED, "Live — streaming to viewer")
            onOpen?.invoke()
        }
        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
            connected = false
            this@PeerJSClient.ws = null
            setState(State.ERROR, "Cannot reach relay: ${t.message}")
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            connected = false
            this@PeerJSClient.ws = null
            if (code == 1000) setState(State.IDLE, "Disconnected")
            else setState(State.ERROR, "Relay disconnected (code $code)")
            onClose?.invoke()
        }
    }

    private fun setState(s: State, msg: String) {
        onStateChange?.invoke(s, msg)
    }
}
