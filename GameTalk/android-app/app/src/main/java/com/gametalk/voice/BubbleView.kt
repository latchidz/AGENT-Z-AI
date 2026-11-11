package com.gametalk.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max

/**
 * الفقاعة العائمة: تظهر فوق أي تطبيق (مثل اللعبة).
 * - لمسة واحدة: كتم / إلغاء الكتم
 * - ضغط مطول: يطلب من الخدمة إظهار زر إنهاء المكالمة بجانبها
 * - سحب: تحريك الفقاعة
 * - النقطة الصغيرة أعلى اليمين: مؤشر جودة الاتصال
 */
class BubbleView(context: Context) : FrameLayout(context) {

    companion object {
        const val SIZE_DP = 62
        private const val CLICK_SLOP_PX = 14
        private const val LONG_PRESS_MS = 550L
    }

    var onToggleMute: (() -> Unit)? = null
    var onExpandRequested: ((Boolean) -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null        // dx, dy من نقطة البداية
    var onDragEnd: (() -> Unit)? = null

    val sizePx: Int = (SIZE_DP * resources.displayMetrics.density).toInt()
    private val density = resources.displayMetrics.density

    private val micIcon: ImageView
    private val qualityDot: View

    private var downRawX = 0f
    private var downRawY = 0f
    private var totalDrag = 0f
    private var dragging = false
    private var expanded = false

    private val longPressRunnable = Runnable {
        if (!dragging) { expanded = true; vibrate(); onExpandRequested?.invoke(true) }
    }

    init {
        val grad = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF12E2FF.toInt(), 0xFF0A66FF.toInt())
        ).apply { shape = GradientDrawable.OVAL }

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((2 * density).toInt(), Color.argb(128, 255, 255, 255))
        }
        background = LayerDrawable(arrayOf(ring, grad)).apply {
            val inset = (3 * density).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }

        micIcon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (30 * density).toInt(), (30 * density).toInt(), Gravity.CENTER
            )
            setImageResource(R.drawable.ic_mic_on)
            setColorFilter(Color.WHITE)
        }
        addView(micIcon)

        qualityDot = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (11 * density).toInt(), (11 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                marginEnd = (5 * density).toInt()
                topMargin = (5 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF9AA6B2.toInt())
                setStroke((1 * density).toInt(), Color.BLACK)
            }
        }
        addView(qualityDot)
    }

    fun setMuted(muted: Boolean) {
        micIcon.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
    }

    fun setWaiting(waiting: Boolean) {
        micIcon.alpha = if (waiting) 0.45f else 1f
    }

    fun setQuality(quality: CallBus.Quality) {
        val color = when (quality) {
            CallBus.Quality.GOOD -> 0xFF2BE880.toInt()
            CallBus.Quality.FAIR -> 0xFFFFC93C.toInt()
            CallBus.Quality.BAD -> 0xFFFF4757.toInt()
            CallBus.Quality.UNKNOWN -> 0xFF9AA6B2.toInt()
        }
        (qualityDot.background as? GradientDrawable)?.setColor(color)
    }

    /** تُستدعى من الخدمة بعد إخفاء زر الإنهاء */
    fun collapse() { expanded = false }

    fun isExpanded(): Boolean = expanded

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(40, 120))
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachTouchHandling() {
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    totalDrag = 0f
                    dragging = false
                    postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    totalDrag = max(abs(dx), abs(dy))
                    if (totalDrag > CLICK_SLOP_PX) {
                        dragging = true
                        removeCallbacks(longPressRunnable)
                        if (expanded) {
                            expanded = false
                            onExpandRequested?.invoke(false)
                        }
                    }
                    if (dragging) onDrag?.invoke(dx, dy)
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    when {
                        dragging -> {
                            onDragEnd?.invoke()
                            if (expanded) onExpandRequested?.invoke(false)
                        }
                        expanded -> { /* لمسة أثناء التوسع: لا شيء */ }
                        else -> {
                            vibrate()
                            onToggleMute?.invoke()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    if (dragging) onDragEnd?.invoke()
                }
            }
            true
        }
    }
}
