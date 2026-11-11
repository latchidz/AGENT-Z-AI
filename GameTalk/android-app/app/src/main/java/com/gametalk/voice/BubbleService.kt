package com.gametalk.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

/**
 * خدمة المكالمة: تعمل في المقدمة (Foreground) وتدير:
 * 1) الإشارة عبر سحابة PeerJS العامة (بدون خادم) + إعادة الاتصال التلقائية
 * 2) محرك WebRTC الصوتي + قناة البيانات للرسائل السريعة
 * 3) الفقاعة العائمة «اثنان في واحد»: كتم المايك + زر رسائل فوق أي لعبة
 * 4) نافذة كتابة عائمة + بطاقة رسالة قابلة للنسخ تختفي تلقائياً
 * 5) الإشعار الدائم (كتم / إنهاء)
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
    private var bubbleAnchorX = 0
    private var bubbleAnchorY = 0
    private var menuTimer: Runnable? = null

    // نافذة كتابة الرسالة
    private var composer: LinearLayout? = null
    private var composerInput: EditText? = null

    // بطاقة الرسالة المستلمة
    private var msgCard: LinearLayout? = null
    private var msgCardLp: WindowManager.LayoutParams? = null
    private val cardHideRunnable = Runnable { hideMessageCard() }

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
            ACTION_TOGGLE_MUTE -> toggleMute()
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

    // ===================== الرسائل السريعة =====================

    /** وصلت رسالة من صديقك عبر قناة البيانات */
    override fun onChatMessage(text: String) {
        main.post {
            if (text.isNotBlank() && !ending) showMessageCard(text)
        }
    }

    /** إرسال رسالة سريعة (من نافذة الكتابة) */
    private fun sendQuickMessage() {
        val text = composerInput?.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            Toast.makeText(this, "اكتب رسالة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = engine?.sendChat(text) == true
        hideComposer()
        if (ok) {
            Toast.makeText(this, "أُرسلت الرسالة ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "لا توجد مكالمة متصلة — تعذّر الإرسال", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- نافذة الكتابة العائمة ----------

    private fun showComposer() {
        if (!canDraw() || composer != null) return
        if (engine == null || !engine!!.isChatReady()) {
            Toast.makeText(this,
                "افتح المكالمة أولاً — الرسائل تصل أثناء الاتصال", Toast.LENGTH_LONG).show()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF50D1B2A.toInt())
                setStroke(dp(1), 0xFF12E2FF.toInt())
            }
            setPadding(dp(14), dp(10), dp(8), dp(10))
        }

        val input = EditText(this).apply {
            hint = "اكتب رسالتك... (كود الغرفة مثلاً)"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8FA3B8.toInt())
            textSize = 15f
            background = null
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            filters = arrayOf(InputFilter.LengthFilter(120))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    sendQuickMessage(); true
                } else false
            }
        }
        composerInput = input

        val sendBtn = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0A9BF0.toInt())
            }
            setImageResource(R.drawable.ic_send)
            setColorFilter(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { sendQuickMessage() }
        }

        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_end_call)
            setColorFilter(0xFF8FA3B8.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { hideComposer() }
        }

        box.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        box.addView(closeBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val screenW = resources.displayMetrics.widthPixels
        val lp = WindowManager.LayoutParams(
            screenW - dp(28),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(56)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        // لمسة خارج النافذة → تُغلق الكاتب وتُمرر اللمسة للعبة
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { hideComposer(); true } else false
        }

        try {
            windowManager.addView(box, lp)
            composer = box
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.postDelayed({ try { imm.showSoftInput(input, 0) } catch (_: Exception) {} }, 150)
        } catch (e: Exception) {
            Log.w(TAG, "composer add: ${e.message}")
        }
    }

    private fun hideComposer() {
        composer?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        composer = null
        composerInput = null
    }

    // ---------- بطاقة الرسالة المستلمة ----------

    private fun showMessageCard(text: String) {
        hideMessageCard()
        if (!canDraw()) { copyToClipboard(text); return }  // بدون إذن العرض: انسخ مباشرة

        val screenW = resources.displayMetrics.widthPixels
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF511202E.toInt())
                setStroke(dp(1), 0xFF12E2FF.toInt())
            }
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }

        val tv = TextView(this).apply {
            setText(text)
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(10) }
        }
        val copyBtn = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC0A9BF0.toInt())
            }
            setImageResource(R.drawable.ic_copy)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        card.addView(tv)
        card.addView(copyBtn, LinearLayout.LayoutParams(dp(42), dp(42)))

        // لمس البطاقة كله = نسخ
        card.setOnClickListener { copyToClipboard(text) }

        val lp = WindowManager.LayoutParams(
            screenW - dp(40),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(110)
        }

        msgCard = card
        msgCardLp = lp
        try {
            windowManager.addView(card, lp)
        } catch (e: Exception) {
            Log.w(TAG, "msgCard add: ${e.message}")
            msgCard = null
        }
        // إشعار: اهتزاز + نغمة قصيرة
        vibrateShort()
        beep()
        // تختفي تلقائياً بعد 4.5 ثانية
        main.removeCallbacks(cardHideRunnable)
        main.postDelayed(cardHideRunnable, 4500)
    }

    private fun hideMessageCard() {
        main.removeCallbacks(cardHideRunnable)
        msgCard?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        msgCard = null
        msgCardLp = null
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GameTalk", text))
            Toast.makeText(this, "تم النسخ ✓ — الصقه في اللعبة", Toast.LENGTH_SHORT).show()
            hideMessageCard()
        } catch (_: Exception) {}
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ try { tg.release() } catch (_: Exception) {} }, 400)
        } catch (_: Exception) {}
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
        val screenW = resources.displayMetrics.widthPixels

        bubbleAnchorX = prefs.getInt("bubble_x", -1).let { saved ->
            if (saved >= 0) saved
            else (screenW - b.sizePx - (12 * density).toInt())
        }
        bubbleAnchorY = prefs.getInt("bubble_y",
            (resources.displayMetrics.heightPixels * 0.38).toInt())

        val lp = WindowManager.LayoutParams(
            b.sizePx, b.sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        bubbleLp = lp

        b.onMicAction = { toggleMute() }
        b.onMessageAction = {
            b.collapseMenu()
            applyWindow()
            showComposer()
        }
        b.onEndAction = {
            b.collapseMenu()
            applyWindow()
            endCall()
        }
        b.onMenuChanged = { extra, _ ->
            applyWindow()
            menuTimer?.let { main.removeCallbacks(it) }
            if (extra > 0) {
                val r = Runnable { bubble?.let { if (it.isExpanded()) it.collapseMenu() } }
                menuTimer = r
                main.postDelayed(r, 5000)
            } else {
                menuTimer = null
            }
        }
        b.onDrag = { rawX, rawY ->
            val half = b.sizePx / 2
            val max = screenW - b.sizePx
            bubbleAnchorX = (rawX - half).toInt().coerceIn(0, max)
            bubbleAnchorY = clampY((rawY - half).toInt())
            applyWindow()
        }
        b.onDragEnd = {
            prefs.edit().putInt("bubble_x", bubbleAnchorX).putInt("bubble_y", bubbleAnchorY).apply()
        }

        try {
            windowManager.addView(b, lp)
            bubble = b
            applyWindow()
        } catch (e: Exception) {
            Log.w(TAG, "bubble add: ${e.message}")
        }
    }

    /** يحسب موضع/عرض نافذة الفقاعة من المرساة وحالة القائمة */
    private fun applyWindow() {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val screenW = resources.displayMetrics.widthPixels
        val extra = b.extraWidthPx
        val side = b.menuSide
        lp.width = b.sizePx + extra
        lp.height = b.sizePx
        lp.x = if (side < 0 && extra > 0) bubbleAnchorX - extra else bubbleAnchorX
        lp.x = lp.x.coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
        // تصحيح المرساة بعد الحصر حتى لا تنزاح الفقاعة عند كل تحديث
        bubbleAnchorX = if (side < 0 && extra > 0) lp.x + extra else lp.x
        lp.y = clampY(bubbleAnchorY)
        safeUpdate(b, lp)
    }

    private fun clampY(y: Int): Int {
        val b = bubble ?: return y
        val max = resources.displayMetrics.heightPixels - b.sizePx
        return y.coerceIn(0, max)
    }

    private fun safeUpdate(v: android.view.View, lp: WindowManager.LayoutParams) {
        try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    // ===================== الكتم =====================

    private fun toggleMute() {
        val muted = engine?.toggleMute() ?: return
        CallBus.update { it.copy(muted = muted) }
        bubble?.setMuted(muted)
        refreshNotification()
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
        hideComposer()
        hideMessageCard()
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
            hideComposer()
            hideMessageCard()
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
