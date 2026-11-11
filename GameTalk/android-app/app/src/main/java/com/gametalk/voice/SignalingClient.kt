package com.gametalk.voice

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * عميل الإشارة (Signaling): WebSocket إلى سيرفر الربط.
 * لا يعرف شيئاً عن WebRTC — فقط ينقل رسائل JSON.
 */
class SignalingClient(
    private val serverUrl: String,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onServerConnected()
        fun onCreated(code: String)
        fun onJoined(code: String)
        fun onPeerJoined()
        fun onPeerLeft()
        fun onOffer(sdp: String)
        fun onAnswer(sdp: String)
        fun onIce(candidate: JSONObject)
        fun onRoomNotFound()
        fun onRoomFull()
        fun onDisconnected()
    }

    companion object { private const val TAG = "SignalingClient" }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)   // keepalive على مستوى WebSocket
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile var isOpen = false
        private set

    fun connect() {
        val req = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(req, listener)
    }

    fun close() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        client.dispatcher.executorService.shutdown()
    }

    fun send(obj: JSONObject): Boolean {
        val s = obj.toString()
        Log.d(TAG, "→ $s")
        return ws?.send(s) ?: false
    }

    fun createRoom(code: String? = null) {
        val o = JSONObject().put("type", "create")
        code?.let { o.put("code", it) }
        send(o)
    }

    fun joinRoom(code: String) {
        send(JSONObject().put("type", "join").put("code", code))
    }

    fun sendOffer(sdp: String, code: String) {
        send(JSONObject().put("type", "offer").put("sdp", sdp).put("code", code))
    }

    fun sendAnswer(sdp: String, code: String) {
        send(JSONObject().put("type", "answer").put("sdp", sdp).put("code", code))
    }

    fun sendIce(candidate: JSONObject, code: String) {
        send(JSONObject().put("type", "ice").put("candidate", candidate).put("code", code))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isOpen = true
            callbacks.onServerConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            Log.d(TAG, "← $text")
            when (msg.optString("type")) {
                "created" -> callbacks.onCreated(msg.optString("code"))
                "joined" -> callbacks.onJoined(msg.optString("code"))
                "peer-joined" -> callbacks.onPeerJoined()
                "peer-left" -> callbacks.onPeerLeft()
                "offer" -> callbacks.onOffer(msg.optString("sdp"))
                "answer" -> callbacks.onAnswer(msg.optString("sdp"))
                "ice" -> callbacks.onIce(msg.optJSONObject("candidate") ?: return)
                "notfound" -> callbacks.onRoomNotFound()
                "full" -> callbacks.onRoomFull()
                "pong" -> { /* keepalive */ }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "onFailure: ${t.message}")
            isOpen = false
            callbacks.onDisconnected()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            callbacks.onDisconnected()
        }
    }
}
