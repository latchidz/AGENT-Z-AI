package com.gametalk.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * طبقة الإشارة عبر سحابة PeerJS العامة — بدون أي خادم خاص (v1.1).
 *
 * الخادم: wss://0.peerjs.com/peerjs?key=peerjs&id=<ID>&token=<RAND>
 *  - الخادم يرد بـ {"type":"OPEN"} عند نجاح حجز المعرّف
 *  - HEARTBEAT كل 4 ثوانٍ يبقي الاتصال مفتوحاً (بدونه يُغلق خلال ثوانٍ)
 *  - الرسائل: OFFER / ANSWER / CANDIDATE / LEAVE مع dst معرّف الطرف الآخر
 *    والخادم يضيف src تلقائياً عند التمرير
 *
 * المعرّفات مشتقة من رمز الغرفة (4 أحرف):
 *  - المنشئ: gtalk-<CODE>-A    المنضم: gtalk-<CODE>-B
 * المنضم يبادر بإرسال OFFER لأنه وحده يعرف متى يريد الاتصال.
 */
class PeerSignaling(
    private val role: CallBus.Role,
    pendingCode: String?,
    private val callbacks: Signaling.Callbacks
) : Signaling {

    companion object {
        private const val TAG = "PeerSignaling"
        private const val CLOUD_HOST = "0.peerjs.com"
        private const val CLOUD_KEY = "peerjs"
        private const val CONN_ID = "gtalk-media"
        private const val HEARTBEAT_MS = 4000L
        private const val REGISTER_RETRY_MS = 3000L
        private const val MAX_REGISTER_RETRIES = 4
        // حروف بلا I/L/O/0/1 لتفادي الالتباس عند القراءة
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var roomCode: String = ""
    private var heartbeatRunnable: Runnable? = null
    private var registerRetries = 0
    private var closedByUs = false

    @Volatile override var isOpen = false
        private set

    init {
        roomCode = if (role == CallBus.Role.CREATOR) {
            pendingCode?.takeIf { it.length == 4 } ?: generateCode()
        } else {
            pendingCode ?: generateCode()
        }
    }

    val code: String get() = roomCode

    private fun myPeerId(): String =
        "gtalk-$roomCode-${if (role == CallBus.Role.CREATOR) "A" else "B"}"

    private fun otherPeerId(): String =
        "gtalk-$roomCode-${if (role == CallBus.Role.CREATOR) "B" else "A"}"

    private fun generateCode(): String = buildString {
        repeat(4) { append(CODE_ALPHABET.random()) }
    }

    // ===================== الاتصال =====================

    override fun connect() {
        closedByUs = false
        val url = "wss://$CLOUD_HOST/peerjs?key=$CLOUD_KEY" +
                "&token=tk-${randomToken()}&id=${myPeerId()}"
        Log.i(TAG, "connecting: $url")
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    override fun close() {
        closedByUs = true
        stopHeartbeat()
        // إبلاغ الطرف الآخر قبل الإغلاق
        try {
            if (isOpen && roomCode.isNotBlank()) {
                ws?.send(JSONObject().put("type", "LEAVE").put("dst", otherPeerId()).toString())
            }
        } catch (_: Exception) {}
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        isOpen = false
        client.dispatcher.executorService.shutdown()
    }

    private fun randomToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    private fun startHeartbeat() {
        stopHeartbeat()
        val r = object : Runnable {
            override fun run() {
                try { ws?.send(JSONObject().put("type", "HEARTBEAT").toString()) } catch (_: Exception) {}
                main.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeatRunnable = r
        main.post(r)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { main.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    // ===================== إرسال الوسائط =====================

    private fun mediaPayload(sdpType: String, sdp: String): JSONObject =
        JSONObject()
            .put("type", "media")
            .put("connectionId", CONN_ID)
            .put("sdp", JSONObject().put("type", sdpType).put("sdp", sdp))

    override fun sendOffer(sdp: String, code: String) {
        val m = JSONObject()
            .put("type", "OFFER")
            .put("dst", otherPeerId())
            .put("payload", mediaPayload("offer", sdp))
        val ok = ws?.send(m.toString()) ?: false
        Log.d(TAG, "→ OFFER (${if (ok) "sent" else "NOT sent"})")
    }

    override fun sendAnswer(sdp: String, code: String) {
        val m = JSONObject()
            .put("type", "ANSWER")
            .put("dst", otherPeerId())
            .put("payload", mediaPayload("answer", sdp))
        ws?.send(m.toString())
    }

    override fun sendIce(candidate: JSONObject, code: String) {
        val inner = candidate.optJSONObject("candidate") ?: return
        val m = JSONObject()
            .put("type", "CANDIDATE")
            .put("dst", otherPeerId())
            .put("payload", JSONObject()
                .put("type", "media")
                .put("connectionId", CONN_ID)
                .put("candidate", inner))
        ws?.send(m.toString())
    }

    // ===================== الاستقبال =====================

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            Log.d(TAG, "← $text")
            when (msg.optString("type")) {
                "OPEN" -> {
                    isOpen = true
                    startHeartbeat()
                    when (role) {
                        CallBus.Role.CREATOR -> callbacks.onCreated(roomCode)
                        CallBus.Role.JOINER -> callbacks.onJoined(roomCode)
                    }
                }
                "ID-TAKEN" -> handleIdTaken()
                "ERROR" -> {
                    val m = msg.optJSONObject("payload")?.optString("msg")
                    Log.w(TAG, "server error: $m")
                    callbacks.onDisconnected()
                }
                "OFFER" -> {
                    val sdp = msg.optJSONObject("payload")
                        ?.optJSONObject("sdp")?.optString("sdp") ?: return
                    callbacks.onOffer(sdp)
                }
                "ANSWER" -> {
                    val sdp = msg.optJSONObject("payload")
                        ?.optJSONObject("sdp")?.optString("sdp") ?: return
                    callbacks.onAnswer(sdp)
                }
                "CANDIDATE" -> {
                    val c = msg.optJSONObject("payload")?.optJSONObject("candidate") ?: return
                    callbacks.onIce(c)
                }
                "LEAVE" -> callbacks.onPeerLeft()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "onFailure: ${t.message}")
            isOpen = false
            stopHeartbeat()
            if (!closedByUs) callbacks.onDisconnected()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed ($code)")
            isOpen = false
            stopHeartbeat()
            if (!closedByUs) callbacks.onDisconnected()
        }
    }

    /** المعرّف محجوز لجلسة أخرى */
    private fun handleIdTaken() {
        isOpen = false
        stopHeartbeat()
        registerRetries++
        if (registerRetries > MAX_REGISTER_RETRIES) {
            callbacks.onRoomFull()
            return
        }
        if (role == CallBus.Role.CREATOR && registerRetries == 1) {
            // تصادم نادر: جرب رمزاً جديداً كلياً
            roomCode = generateCode()
        }
        Log.w(TAG, "ID-TAKEN — retry #$registerRetries with code=$roomCode")
        main.postDelayed({
            if (!closedByUs) {
                try { ws?.cancel() } catch (_: Exception) {}
                connect()
            }
        }, REGISTER_RETRY_MS)
    }
}
