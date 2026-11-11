package com.gametalk.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

/**
 * خدمة المكالمة: تعمل في المقدمة (Foreground) وتدير:
 * 1) الإشارة عبر سحابة PeerJS العامة (بدون خادم) + إعادة الاتصال التلقائية
 * 2) محرك WebRTC الصوتي
 * 3) الفقاعة العائمة فوق التطبيقات + زر الإنهاء
 * 4) الإشعار الدائم (كتم / إنهاء)
 *
 * تدفق الإشارة v1.1: المنضم هو من يرسل OFFER (لأنه يعرف رمز الغرفة)،
 * والمنشئ يجيب بـ ANSWER عند استلامه.
 */
class BubbleService : Service(), Signaling.Callbacks, CallEngine.Listener {

    companion object {
        private const val TAG = "BubbleService"
        private const val CHANNEL_ID = "gametalk_call"
        private const val NOTIF_ID = 42
        private const val PREFS = "gametalk_prefs"

        const val ACTION_CREATE = "com.gametalk.voice.CREATE"
        const val ACTION_JOIN = "com.gametalk.voice.JOIN"
        const val ACTION_END = "com.gametalk.voice.END"
        const val ACTION_TOGGLE_MUTE = "com.gametalk.voice.TOGGLE_MUTE"
        const val EXTRA_CODE = "code"

        private val BACKOFF_STEPS = longArrayOf(2000, 4000, 8000, 16000, 30000)

        fun start(context: Context, action: String, code: String? = null) {
            val i = Intent(context, BubbleService::class.java)
                .setAction(action)
                .putExtra(EXTRA_CODE, code)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager

    private var signaling: Signaling? = null
    private var engine: CallEngine? = null

    private var role: CallBus.Role = CallBus.Role.CREATOR
    private var roomCode: String? = null
    private var pendingJoinCode: String? = null

    private var wsRetryAttempt = 0
    private var retryRunnable: Runnable? = null
    private var offerAttempt = 0
    private var offerRunnable: Runnable? = null
    private var ending = false

    // overlay
    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var endBtn: ImageView? = null
    private var endLp: WindowManager.LayoutParams? = null

    // ===================== دورة الحياة =====================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CREATE -> {
                role = CallBus.Role.CREATOR
                roomCode = null
                pendingJoinCode = intent.getStringExtra(EXTRA_CODE)
                beginCall("جارٍ إنشاء الغرفة...")
            }
            ACTION_JOIN -> {
                role = CallBus.Role.JOINER
                pendingJoinCode = intent.getStringExtra(EXTRA_CODE)
                beginCall("جارٍ الانضمام...")
            }
            ACTION_TOGGLE_MUTE -> {
                val muted = engine?.toggleMute() ?: false
                CallBus.update { it.copy(muted = muted) }
                bubble?.setMuted(muted)
                refreshNotification()
            }
            ACTION_END -> endCall()
        }
        return START_STICKY
    }

    private fun beginCall(statusMsg: String) {
        ending = false
        wsRetryAttempt = 0
        offerAttempt = 0
        CallBus.update {
            CallBus.Snapshot(
                state = CallBus.State.CONNECTING,
                message = statusMsg,
                role = role
            )
        }
        startForegroundCompat(buildNotification("جارٍ الاتصال..."))
        engine = CallEngine(this)
        connectSignaling()
        showBubble()
    }

    private fun connectSignaling() {
        signaling?.close()
        signaling = PeerSignaling(role, pendingJoinCode, this).also { it.connect() }
    }

    // ===================== Signaling Callbacks =====================

    override fun onServerConnected() {
        // التسجيل في سحابة PeerJS تلقائي عبر OPEN — لا شيء مطلوب هنا
    }

    override fun onCreated(code: String) {
        main.post {
            roomCode = code
            pendingJoinCode = code
            prefs.edit().putString("last_room", code).apply()
            CallBus.update {
                if (it.state == CallBus.State.CONNECTED) it.copy(roomCode = code)
                else it.copy(
                    state = CallBus.State.WAITING_PEER,
                    roomCode = code,
                    message = "الغرفة جاهزة — شارك الرمز مع صديقك",
                    role = role
                )
            }
            refreshNotification()
        }
    }

    override fun onJoined(code: String) {
        main.post {
            roomCode = code
            // المنضم يبادر بإرسال OFFER فور تسجيله
            CallBus.update {
                it.copy(state = CallBus.State.CONNECTING, roomCode = code,
                    message = "تم الدخول — جارٍ الاتصال بصديقك...", role = role)
            }
            refreshNotification()
            startOffering()
            scheduleOfferRetry(first = false)
        }
    }

    /** المنضم: إنشاء PeerConnection وإرسال عرض */
    private fun startOffering() {
        if (ending) return
        if (engine?.createPeerConnection(this) == true) {
            engine?.createOffer { sdp ->
                roomCode?.let { signaling?.sendOffer(sdp, it) }
            }
        } else {
            failCall("تعذر تشغيل الميكروفون/المحرك الصوتي")
        }
    }

    /** إعادة إرسال العرض دورياً حتى يستقبل جواباً (السحابة تُسقط الرسائل الغير متسلمين بعد 5 ثوانٍ) */
    private fun scheduleOfferRetry(first: Boolean) {
        offerRunnable?.let { main.removeCallbacks(it) }
        if (first) {
            val r = Runnable {
                if (ending || CallBus.snapshot.state == CallBus.State.CONNECTED) return@Runnable
                startOffering()
            }
            offerRunnable = r
            main.postDelayed(r, 4000)
            return
        }
        offerAttempt++
        if (offerAttempt > 45) {
            failCall("لم يرد صديقك — تأكد أنه أنشأ الغرفة ومن صحة الرمز ثم أعد المحاولة")
            return
        }
        val r = Runnable {
            if (ending || CallBus.snapshot.state == CallBus.State.CONNECTED) return@Runnable
            Log.i(TAG, "offer retry #$offerAttempt")
            startOffering()
            scheduleOfferRetry(first = false)
        }
        offerRunnable = r
        main.postDelayed(r, 4000)
    }

    private fun stopOfferRetry() {
        offerRunnable?.let { main.removeCallbacks(it) }
        offerRunnable = null
    }

    override fun onOffer(sdp: String) {
        main.post {
            // المنشئ هو من يستقبل العرض ويجيب
            if (role == CallBus.Role.CREATOR && !ending) {
                CallBus.update { it.copy(state = CallBus.State.CONNECTING,
                    message = "صديقك يريد الاتصال — جارٍ التأسيس...") }
                if (engine?.createPeerConnection(this) == true) {
                    engine?.acceptOfferAndAnswer(sdp) { answer ->
                        roomCode?.let { signaling?.sendAnswer(answer, it) }
                    }
                } else {
                    failCall("تعذر تشغيل الميكروفون/المحرك الصوتي")
                }
            }
        }
    }

    override fun onAnswer(sdp: String) {
        main.post {
            stopOfferRetry()
            engine?.setRemoteAnswer(sdp)
        }
    }

    override fun onIce(candidate: JSONObject) {
        // الشكل المتوقع: {candidate:"<sdp>", sdpMid:"0", sdpMLineIndex:0}
        // مع fallback للشكل المغلّف {candidate:{...}} للتوافق
        val c = candidate.optJSONObject("candidate") ?: candidate
        val line = c.optString("candidate")
        if (line.isBlank()) return
        val ice = IceCandidate(
            c.optString("sdpMid", "0"),
            c.optInt("sdpMLineIndex", 0),
            line
        )
        main.post { engine?.addRemoteIce(ice) }
    }

    override fun onLocalIce(candidate: IceCandidate) {
        val code = roomCode ?: return
        val j = JSONObject()
            .put("candidate", JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp))
        signaling?.sendIce(j, code)
    }

    override fun onPeerConnectionState(state: PeerConnection.PeerConnectionState) {
        main.post {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> onCallConnected()
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    CallBus.update { if (it.state == CallBus.State.CONNECTED)
                        it.copy(state = CallBus.State.RECONNECTING,
                            message = "ضعف الاتصال — جارٍ الاسترداد...") else it }
                PeerConnection.PeerConnectionState.FAILED -> onCallFailed()
                PeerConnection.PeerConnectionState.CLOSED -> {}
                else -> {}
            }
        }
    }

    private fun onCallConnected() {
        stopOfferRetry()
        vibrateShort()
        CallBus.update { it.copy(state = CallBus.State.CONNECTED,
            message = "متصل — الصوت يعمل الآن", quality = CallBus.Quality.UNKNOWN) }
        bubble?.setWaiting(false)
        refreshNotification()
    }

    private fun onCallFailed() {
        // نُنهي اتصال WebRTC فقط ونعيد المصافحة عبر الإشارة
        engine?.closePeerConnection()
        if (role == CallBus.Role.JOINER) {
            // المنضم يبادر من جديد (الإشارة غالباً ما زالت مسجلة)
            offerAttempt = 0
            CallBus.update { it.copy(state = CallBus.State.RECONNECTING,
                message = "إعادة الاتصال التلقائية...") }
            if (signaling?.isOpen == true) {
                scheduleOfferRetry(first = true)
            } else {
                scheduleWsReconnect(immediate = true)
            }
        } else {
            CallBus.update { it.copy(state = CallBus.State.WAITING_PEER,
                message = "بانتظار إعادة انضمام صديقك...") }
            bubble?.setWaiting(true)
        }
    }

    override fun onPeerLeft() {
        main.post {
            engine?.closePeerConnection()
            stopOfferRetry()
            CallBus.update { it.copy(state = CallBus.State.WAITING_PEER,
                message = "انقطع صديقك — بانتظار عودته...", quality = CallBus.Quality.UNKNOWN) }
            bubble?.setWaiting(true)
            refreshNotification()
        }
    }

    override fun onRoomFull() {
        main.post { failCall("الغرفة مشغولة من جهاز آخر — انتظر قليلاً ثم أعد المحاولة") }
    }

    override fun onDisconnected() {
        if (ending) return
        main.post {
            engine?.closePeerConnection()
            CallBus.update { it.copy(state = CallBus.State.RECONNECTING,
                message = "انقطع الاتصال — إعادة تلقائية...") }
            scheduleWsReconnect(immediate = false)
        }
    }

    // ===================== إعادة الاتصال التلقائية =====================

    private fun scheduleWsReconnect(immediate: Boolean) {
        retryRunnable?.let { main.removeCallbacks(it) }
        val delay = if (immediate) 500L
        else BACKOFF_STEPS[minOf(wsRetryAttempt, BACKOFF_STEPS.size - 1)]
        wsRetryAttempt++
        val r = Runnable {
            if (ending) return@Runnable
            Log.i(TAG, "WS reconnect attempt #$wsRetryAttempt")
            connectSignaling()
        }
        retryRunnable = r
        main.postDelayed(r, delay)
    }

    // ===================== الفقاعة العائمة =====================

    private fun canDraw(): Boolean = Settings.canDrawOverlays(this)

    private fun showBubble() {
        if (!canDraw() || bubble != null) return
        val b = BubbleView(this)
        b.attachTouchHandling()
        b.setWaiting(true)

        val density = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            b.sizePx, b.sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", -1).let { saved ->
                if (saved >= 0) saved
                else (resources.displayMetrics.widthPixels - b.sizePx - (12 * density).toInt())
            }
            y = prefs.getInt("bubble_y",
                (resources.displayMetrics.heightPixels * 0.38).toInt())
        }

        b.onToggleMute = {
            val muted = engine?.toggleMute() ?: false
            CallBus.update { it.copy(muted = muted) }
            b.setMuted(muted)
            refreshNotification()
        }
        b.onExpandRequested = { show -> if (show) showEndBtn() else hideEndBtn() }
        b.onDrag = { rawX, rawY ->
            val half = b.sizePx / 2
            lp.x = clampX((rawX - half).toInt())
            lp.y = clampY((rawY - half).toInt())
            safeUpdate(b, lp)
            if (endBtn != null) positionEndBtn()
        }
        b.onDragEnd = {
            prefs.edit().putInt("bubble_x", lp.x).putInt("bubble_y", lp.y).apply()
        }

        try {
            windowManager.addView(b, lp)
            bubble = b
            bubbleLp = lp
        } catch (e: Exception) {
            Log.w(TAG, "bubble add: ${e.message}")
        }
    }

    private fun clampX(x: Int): Int {
        val b = bubble ?: return x
        val max = resources.displayMetrics.widthPixels - b.sizePx
        return x.coerceIn(0, max)
    }

    private fun clampY(y: Int): Int {
        val b = bubble ?: return y
        val max = resources.displayMetrics.heightPixels - b.sizePx
        return y.coerceIn(0, max)
    }

    private fun safeUpdate(v: android.view.View, lp: WindowManager.LayoutParams) {
        try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun endBtnSizePx(): Int = (46 * resources.displayMetrics.density).toInt()

    private fun showEndBtn() {
        val b = bubble ?: return
        if (!canDraw() || endBtn != null) return
        val iv = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6FF3B5C.toInt())
            }
            setImageResource(R.drawable.ic_end_call)
            setColorFilter(Color.WHITE)
            setPadding(
                (11 * resources.displayMetrics.density).toInt(),
                (11 * resources.displayMetrics.density).toInt(),
                (11 * resources.displayMetrics.density).toInt(),
                (11 * resources.displayMetrics.density).toInt()
            )
            setOnClickListener {
                b.collapse()
                hideEndBtn()
                endCall()
            }
        }
        val lp = WindowManager.LayoutParams(
            endBtnSizePx(), endBtnSizePx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        endBtn = iv
        endLp = lp
        try {
            windowManager.addView(iv, lp)
            positionEndBtn()
        } catch (e: Exception) {
            endBtn = null
            Log.w(TAG, "endBtn add: ${e.message}")
        }
        // إخفاء تلقائي بعد 5 ثوانٍ
        main.postDelayed({ if (bubble?.isExpanded() == true) {
            bubble?.collapse(); hideEndBtn()
        } }, 5000)
    }

    private fun positionEndBtn() {
        val b = bubble ?: return
        val lpB = bubbleLp ?: return
        val lpE = endLp ?: return
        val gap = (6 * resources.displayMetrics.density).toInt()
        val screenW = resources.displayMetrics.widthPixels
        lpE.y = lpB.y + (b.sizePx - endBtnSizePx()) / 2
        lpE.x = if (lpB.x > screenW / 2) lpB.x - endBtnSizePx() - gap
        else lpB.x + b.sizePx + gap
        endBtn?.let { safeUpdate(it, lpE) }
    }

    private fun hideEndBtn() {
        endBtn?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        endBtn = null
        endLp = null
    }

    // ===================== إنهاء المكالمة =====================

    private fun failCall(msg: String) {
        CallBus.update { it.copy(state = CallBus.State.ERROR, message = msg) }
        refreshNotification()
    }

    private fun endCall() {
        ending = true
        retryRunnable?.let { main.removeCallbacks(it) }
        stopOfferRetry()
        hideEndBtn()
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubble = null
        bubbleLp = null
        signaling?.close()
        signaling = null
        engine?.release()
        engine = null
        CallBus.update { it.copy(state = CallBus.State.ENDED, message = "انتهت المكالمة") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!ending) {
            hideEndBtn()
            bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
            signaling?.close()
            engine?.release()
            CallBus.update { it.copy(state = CallBus.State.ENDED) }
        }
        super.onDestroy()
    }

    // ===================== الإشعار =====================

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "مكالمة GameTalk", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mutePi = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endPi = PendingIntent.getService(
            this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muted = CallBus.snapshot.muted
        val code = CallBus.snapshot.roomCode
        val text = buildString {
            append(status)
            if (!code.isNullOrBlank()) append("  •  الرمز: $code")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_mic)
            .setContentTitle("GameTalk — مكالمة صوتية")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, if (muted) "▶ تشغيل الصوت" else "⏸ كتم الصوت", mutePi)
            .addAction(0, "✕ إنهاء المكالمة", endPi)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    private fun refreshNotification() {
        val s = CallBus.snapshot
        val status = when (s.state) {
            CallBus.State.CONNECTED -> if (s.muted) "متصل (مكتوم)" else "متصل"
            CallBus.State.WAITING_PEER -> "بانتظار صديقك"
            CallBus.State.RECONNECTING -> "إعادة اتصال تلقائية..."
            CallBus.State.ERROR -> s.message ?: "خطأ"
            else -> s.message ?: "..."
        }
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.notify(NOTIF_ID, buildNotification(status)) } catch (_: Exception) {}
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun vibrateShort() {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createOneShot(60, 180))
        } catch (_: Exception) {}
    }
}
