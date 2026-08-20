package com.nathangamalnasser.natapps.recorder

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class RelayServer(private val context: Context) {

    private val http = HttpServer(context)
    private val ws   = WsServer()

    fun start() {
        try { http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) } catch (_: IOException) {}
        try { ws.start() } catch (_: Exception) {}
    }

    fun stop() {
        http.stop()
        try { ws.stop(0) } catch (_: Exception) {}
    }

    // ── HTTP server: serves tracer-real.html + session files ─────────────────

    private class HttpServer(private val ctx: Context) : NanoHTTPD(8080) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.trimStart('/')
            if (uri.startsWith("session/") && uri.endsWith(".json")) {
                val filename = uri.removePrefix("session/")
                if (filename.matches(Regex("session_\\d+\\.json"))) {
                    try {
                        val file = java.io.File(ctx.filesDir, filename)
                        if (file.exists()) {
                            val resp = newChunkedResponse(
                                Response.Status.OK,
                                "application/json; charset=utf-8",
                                file.inputStream()
                            )
                            resp.addHeader("Access-Control-Allow-Origin", "*")
                            return resp
                        }
                    } catch (_: Exception) {}
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "null")
            }
            return try {
                val stream = ctx.assets.open("tracer-real.html")
                newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", stream)
            } catch (_: Exception) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
    }

    // ── WebSocket relay on port 9001 ─────────────────────────────────────────

    class WsServer : WebSocketServer(InetSocketAddress(9001)) {
        private val viewers = CopyOnWriteArraySet<WebSocket>()
        private val sensors = ConcurrentHashMap<String, WebSocket>()
        @Volatile var cachedOrigin: String? = null

        init { isReuseAddr = true }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            viewers.remove(conn)
            val dev: String = conn.getAttachment() ?: return
            if (sensors[dev] === conn) {
                sensors.remove(dev)
                broadcastToViewers("""{"type":"status","device":"$dev","connected":false}""")
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val msg = try { JSONObject(message) } catch (_: Exception) { return }
            when (msg.optString("type")) {
                "register" -> {
                    val role   = msg.optString("role")
                    val device = msg.optString("device")
                    if (role == "viewer") {
                        viewers.add(conn)
                        cachedOrigin?.let { try { conn.send(it) } catch (_: Exception) {} }
                    } else {
                        conn.setAttachment(device)
                        sensors[device] = conn
                        broadcastToViewers("""{"type":"status","device":"$device","connected":true}""")
                    }
                }
                "gps_origin" -> { cachedOrigin = message; broadcastToViewers(message) }
                "sensor", "gps" -> broadcastToViewers(message)
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {}
        override fun onStart() {}

        private fun broadcastToViewers(msg: String) {
            viewers.forEach { v -> if (v.isOpen) try { v.send(msg) } catch (_: Exception) {} }
        }
    }
}
