package com.gametalk.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var roomCodeText: TextView
    private lateinit var copyBtn: Button
    private lateinit var createBtn: Button
    private lateinit var joinBtn: Button
    private lateinit var codeInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var micPermBtn: Button
    private lateinit var overlayPermBtn: Button
    private lateinit var endBtn: Button
    private lateinit var qualityText: TextView

    private val callListener: (CallBus.Snapshot) -> Unit = { snap -> render(snap) }

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshPermissionButtons()
            if (!granted) Toast.makeText(this, "إذن الميكروفون مطلوب للمكالمة", Toast.LENGTH_LONG).show()
        }
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionButtons()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        roomCodeText = findViewById(R.id.roomCodeText)
        copyBtn = findViewById(R.id.copyBtn)
        createBtn = findViewById(R.id.createBtn)
        joinBtn = findViewById(R.id.joinBtn)
        codeInput = findViewById(R.id.codeInput)
        serverInput = findViewById(R.id.serverInput)
        micPermBtn = findViewById(R.id.micPermBtn)
        overlayPermBtn = findViewById(R.id.overlayPermBtn)
        endBtn = findViewById(R.id.endBtn)
        qualityText = findViewById(R.id.qualityText)

        // إدخال الرمز: حروف كبيرة فقط + 4 خانات
        codeInput.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(4))
        codeInput.setTextAppearance(this, R.style.CodeInputStyle)

        val prefs = getSharedPreferences("gametalk_prefs", MODE_PRIVATE)
        serverInput.setText(prefs.getString("server_url", "") ?: "")
        codeInput.setText(prefs.getString("last_room", "") ?: "")

        copyBtn.setOnClickListener {
            val code = CallBus.snapshot.roomCode
            if (code.isNullOrBlank()) {
                Toast.makeText(this, "لا يوجد رمز بعد — أنشئ غرفة أولاً", Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("GameTalk", code))
                Toast.makeText(this, "تم نسخ الرمز ✓", Toast.LENGTH_SHORT).show()
            }
        }

        createBtn.setOnClickListener {
            if (!hasMicPermission()) { requestMic(); return@setOnClickListener }
            if (!Settings.canDrawOverlays(this)) { requestOverlay(); return@setOnClickListener }
            saveServer()
            BubbleService.start(this, BubbleService.ACTION_CREATE, null, serverUrlOrNull())
            Toast.makeText(this, "جارٍ إنشاء الغرفة...", Toast.LENGTH_SHORT).show()
        }

        joinBtn.setOnClickListener {
            val code = codeInput.text.toString().trim().uppercase()
            if (code.length != 4) {
                Toast.makeText(this, "أدخل رمزاً من 4 حروف", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasMicPermission()) { requestMic(); return@setOnClickListener }
            if (!Settings.canDrawOverlays(this)) { requestOverlay(); return@setOnClickListener }
            saveServer()
            BubbleService.start(this, BubbleService.ACTION_JOIN, code, serverUrlOrNull())
        }

        micPermBtn.setOnClickListener { if (!hasMicPermission()) requestMic() else requestNotif() }
        overlayPermBtn.setOnClickListener { if (!Settings.canDrawOverlays(this)) requestOverlay() }

        endBtn.setOnClickListener { BubbleService.start(this, BubbleService.ACTION_END) }
    }

    private fun serverUrlOrNull(): String? {
        val s = serverInput.text.toString().trim()
        return s.ifBlank { null }
    }

    private fun saveServer() {
        val s = serverInput.text.toString().trim()
        getSharedPreferences("gametalk_prefs", MODE_PRIVATE)
            .edit().putString("server_url", s).apply()
    }

    // ---------- الأذونات ----------

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestOverlay() {
        Toast.makeText(this, "فعّل «العرض فوق التطبيقات» لظهور الفقاعة أثناء اللعب", Toast.LENGTH_LONG).show()
        overlayLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun refreshPermissionButtons() {
        micPermBtn.text = if (hasMicPermission()) "🎤 الميكروفون: مفعّل ✓" else "🎤 الميكروفون: غير مفعّل"
        overlayPermBtn.text =
            if (Settings.canDrawOverlays(this)) "🫧 العرض فوق التطبيقات: مفعّل ✓"
            else "🫧 العرض فوق التطبيقات: غير مفعّل"
    }

    // ---------- عرض الحالة ----------

    override fun onResume() {
        super.onResume()
        refreshPermissionButtons()
        render(CallBus.snapshot)
        CallBus.addListener(callListener)
    }

    override fun onPause() {
        super.onPause()
        CallBus.removeListener(callListener)
    }

    private fun render(s: CallBus.Snapshot) {
        val (label, color) = when (s.state) {
            CallBus.State.IDLE -> "جاهز للمكالمة" to R.color.neutral
            CallBus.State.CONNECTING -> "جارٍ الاتصال..." to R.color.waiting
            CallBus.State.WAITING_PEER -> "بانتظار انضمام صديقك..." to R.color.waiting
            CallBus.State.CONNECTED -> "متصل — الصوت يعمل" to R.color.connected
            CallBus.State.RECONNECTING -> "إعادة اتصال تلقائية..." to R.color.reconnecting
            CallBus.State.ENDED -> "انتهت المكالمة" to R.color.neutral
            CallBus.State.ERROR -> (s.message ?: "حدث خطأ") to R.color.error
        }
        statusText.text = label
        statusDot.setBackgroundResource(R.drawable.dot_shape)
        (statusDot.background.mutate() as? android.graphics.drawable.GradientDrawable)
            ?.setColor(ContextCompat.getColor(this, color))

        roomCodeText.text = s.roomCode ?: "----"

        qualityText.visibility = if (s.state == CallBus.State.CONNECTED) View.VISIBLE else View.GONE
        qualityText.text = when (s.quality) {
            CallBus.Quality.GOOD -> "📶 جودة ممتازة${s.rttMs?.let { " (${it}ms)" } ?: ""}"
            CallBus.Quality.FAIR -> "📶 جودة متوسطة${s.rttMs?.let { " (${it}ms)" } ?: ""}"
            CallBus.Quality.BAD -> "📶 جودة ضعيفة${s.rttMs?.let { " (${it}ms)" } ?: ""}"
            CallBus.Quality.UNKNOWN -> "📶 جودة الاتصال..."
        }

        endBtn.visibility =
            if (s.state == CallBus.State.IDLE || s.state == CallBus.State.ENDED) View.GONE
            else View.VISIBLE

        createBtn.isEnabled = s.state == CallBus.State.IDLE || s.state == CallBus.State.ENDED
            || s.state == CallBus.State.ERROR
        joinBtn.isEnabled = createBtn.isEnabled
        createBtn.alpha = if (createBtn.isEnabled) 1f else 0.5f
        joinBtn.alpha = if (joinBtn.isEnabled) 1f else 0.5f
    }
}
