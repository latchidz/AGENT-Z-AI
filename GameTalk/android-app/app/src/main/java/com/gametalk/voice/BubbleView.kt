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
 * الفقاعة العائمة «اثنان في واحد» — تظهر فوق أي تطبيق (مثل اللعبة).
 *
 * الوضع المضغوط:
 *  - ضغطة سريعة: توسّع قائمة صغيرة بجانب الفقاعة فيها زر الرسائل
 *  - ضغط مطول: قائمة فيها زر الرسائل + زر إنهاء المكالمة
 *  - سحب: تحريك الفقاعة
 *
 * القائمة الموسعة:
 *  - الفقاعة نفسها = زر كتم/تفعيل المايك (ضغطة)
 *  - زر الرسائل: يفتح نافذة الكتابة العائمة
 *  - زر الإنهاء (أحمر): يظهر فقط مع الضغط المطول
 *
 * النقطة الصغيرة أعلى يمين الفقاعة: مؤشر جودة الاتصال.
 */
class BubbleView(context: Context) : FrameLayout(context) {

    companion object {
        const val SIZE_DP = 62          // قطر الفقاعة
        const val BTN_DP = 48           // قطر أزرار القائمة
        const val GAP_DP = 8            // الفراغ بين الأزرار
        private const val CLICK_SLOP_PX = 14
        private const val LONG_PRESS_MS = 550L
    }

    /** تغيّر حجم القائمة: extraPx = العرض الإضافي (0 = مطوية)، side = -1 يسار الفقاعة / +1 يمينها */
    var onMenuChanged: ((extraPx: Int, side: Int) -> Unit)? = null
    /** ضغطة على الفقاعة وهي موسعة = كتم/تفعيل */
    var onMicAction: (() -> Unit)? = null
    /** ضغطة على زر الرسائل */
    var onMessageAction: (() -> Unit)? = null
    /** ضغطة على زر الإنهاء */
    var onEndAction: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null        // rawX, rawY من الشاشة
    var onDragEnd: (() -> Unit)? = null

    val sizePx: Int = (SIZE_DP * resources.displayMetrics.density).toInt()
    val btnPx: Int = (BTN_DP * resources.displayMetrics.density).toInt()
    val gapPx: Int = (GAP_DP * resources.displayMetrics.density).toInt()
    private val density = resources.displayMetrics.density

    private val bubbleBody: FrameLayout
    private val micIcon: ImageView
    private val qualityDot: View
    private val msgBtn: ImageView
    private val endBtn: ImageView

    private var downRawX = 0f
    private var downRawY = 0f
    private var totalDrag = 0f
    private var dragging = false
    private var menuExpanded = false

    /** العرض الحالي للنافذة: الفقاعة + الأزرار (يقرؤها الخدمة لحساب الحدود) */
    val extraWidthPx: Int
        get() = when {
            !menuExpanded -> 0
            endBtn.visibility == View.VISIBLE -> 2 * (btnPx + gapPx)
            else -> btnPx + gapPx
        }

    /** جهة ظهور الأزرار: -1 يسار الفقاعة، +1 يمينها */
    var menuSide: Int = -1
        private set

    private val longPressRunnable = Runnable {
        if (!dragging) {
            vibrate()
            if (menuExpanded) {
                // ضغط مطول والموسعة مسبقاً: لا شيء
            } else {
                showMenu(withEnd = true)
            }
        }
    }

    init {
        // جسم الفقاعة (دائرة متدرجة + أيقونة المايك + نقطة الجودة)
        bubbleBody = FrameLayout(context).apply {
            layoutParams = LayoutParams(sizePx, sizePx, Gravity.START or Gravity.CENTER_VERTICAL)
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
        }
        addView(bubbleBody)

        micIcon = ImageView(context).apply {
            layoutParams = LayoutParams(
                (30 * density).toInt(), (30 * density).toInt(), Gravity.CENTER
            )
            setImageResource(R.drawable.ic_mic_on)
            setColorFilter(Color.WHITE)
        }
        bubbleBody.addView(micIcon)

        qualityDot = View(context).apply {
            layoutParams = LayoutParams(
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
        bubbleBody.addView(qualityDot)

        // زر الرسائل
        msgBtn = ImageView(context).apply {
            layoutParams = LayoutParams(btnPx, btnPx, Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = 0 }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF2159E8E.toInt())
            }
            setImageResource(R.drawable.ic_chat)
            setColorFilter(Color.WHITE)
            setPadding(
                (12 * density).toInt(), (12 * density).toInt(),
                (12 * density).toInt(), (12 * density).toInt()
            )
            visibility = View.GONE
            setOnClickListener {
                vibrate()
                onMessageAction?.invoke()
            }
        }
        addView(msgBtn)

        // زر الإنهاء
        endBtn = ImageView(context).apply {
            layoutParams = LayoutParams(btnPx, btnPx, Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { leftMargin = 0 }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6FF3B5C.toInt())
            }
            setImageResource(R.drawable.ic_end_call)
            setColorFilter(Color.WHITE)
            setPadding(
                (11 * density).toInt(), (11 * density).toInt(),
                (11 * density).toInt(), (11 * density).toInt()
            )
            visibility = View.GONE
            setOnClickListener {
                onEndAction?.invoke()
            }
        }
        addView(endBtn)
    }

    // ===================== القائمة =====================

    fun showMenu(withEnd: Boolean) {
        val screenW = resources.displayMetrics.widthPixels
        // جهة الأزرار: بعيداً عن أقرب حافة
        menuSide = if (downRawX > screenW / 2f) -1 else 1
        menuExpanded = true
        endBtn.visibility = if (withEnd) View.VISIBLE else View.GONE
        msgBtn.visibility = View.VISIBLE
        layoutMenu()
        onMenuChanged?.invoke(extraWidthPx, menuSide)
    }

    fun collapseMenu() {
        if (!menuExpanded) return
        menuExpanded = false
        msgBtn.visibility = View.GONE
        endBtn.visibility = View.GONE
        layoutMenu()
        onMenuChanged?.invoke(0, 0)
    }

    fun isExpanded(): Boolean = menuExpanded

    /** ترتيب الأطفال أفقياً حسب الجهة:
     *  side<0: [الإنهاء][الرسائل][الفقاعة]   side>0: [الفقاعة][الرسائل][الإنهاء] */
    private fun layoutMenu() {
        val before = mutableListOf<View>()   // أزرار يسار الفقاعة
        val after = mutableListOf<View>()    // أزرار يمين الفقاعة
        if (menuExpanded) {
            if (menuSide < 0) {
                // [الإنهاء][الرسائل][الفقاعة] — الإنهاء الأبعد
                if (endBtn.visibility == View.VISIBLE) before.add(endBtn)
                before.add(msgBtn)
            } else {
                // [الفقاعة][الرسائل][الإنهاء] — الإنهاء الأبعد
                after.add(msgBtn)
                if (endBtn.visibility == View.VISIBLE) after.add(endBtn)
            }
        }
        var left = 0
        fun place(v: View, w: Int, gap: Int) {
            val lp = v.layoutParams as LayoutParams
            lp.leftMargin = left + if (left == 0) 0 else gap
            v.layoutParams = lp
            left = lp.leftMargin + w
        }
        for (v in before) place(v, btnPx, gapPx)
        place(bubbleBody, sizePx, 0)
        for (v in after) place(v, btnPx, gapPx)
    }

    // ===================== الحالة =====================

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

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(40, 120))
        } catch (_: Exception) {}
    }

    // ===================== اللمس =====================

    private fun hitRegion(x: Float, y: Float): Int {
        // 0 = فقاعة / فراغ، 1 = زر رسائل، 2 = زر إنهاء
        if (y < 0 || y > height) return 0
        if (msgBtn.visibility == View.VISIBLE) {
            val l = msgBtn.left
            if (x >= l && x <= l + msgBtn.width) return 1
        }
        if (endBtn.visibility == View.VISIBLE) {
            val l = endBtn.left
            if (x >= l && x <= l + endBtn.width) return 2
        }
        return 0
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
                    val region = hitRegion(event.x, event.y)
                    if (region == 1 || region == 2) {
                        // الأزرار تتعامل بنفسها (OnClickListener)
                        removeCallbacks(longPressRunnable)
                        return@setOnTouchListener false
                    }
                    postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    totalDrag = max(abs(dx), abs(dy))
                    if (totalDrag > CLICK_SLOP_PX) {
                        dragging = true
                        removeCallbacks(longPressRunnable)
                        if (menuExpanded) collapseMenu()
                    }
                    if (dragging) onDrag?.invoke(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    when {
                        dragging -> onDragEnd?.invoke()
                        menuExpanded -> {
                            // ضغطة على الفقاعة/الفراغ والموسعة = كتم/تفعيل
                            vibrate()
                            onMicAction?.invoke()
                        }
                        else -> {
                            vibrate()
                            showMenu(withEnd = false)
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
