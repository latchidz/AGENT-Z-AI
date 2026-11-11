package com.gametalk.voice

import android.os.Handler
import android.os.Looper

/**
 * حالة المكالمة المشتركة بين الخدمة والواجهة.
 */
object CallBus {

    enum class State {
        IDLE,           // لا مكالمة
        CONNECTING,     // جارٍ الاتصال بالسيرفر
        WAITING_PEER,   // الغرفة جاهزة، بانتظار انضمام صديقك
        CONNECTED,      // الصوت يعمل
        RECONNECTING,   // انقطاع → إعادة اتصال تلقائية
        ENDED,          // انتهت المكالمة
        ERROR           // خطأ
    }

    enum class Quality { UNKNOWN, GOOD, FAIR, BAD }

    data class Snapshot(
        val state: State = State.IDLE,
        val roomCode: String? = null,
        val message: String? = null,
        val quality: Quality = Quality.UNKNOWN,
        val rttMs: Int? = null,
        val muted: Boolean = false,
        val role: Role? = null
    )

    enum class Role { CREATOR, JOINER }

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayListList<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(block: (Snapshot) -> Snapshot) {
        snapshot = block(snapshot)
        val s = snapshot
        main.post { listeners.forEach { it(s) } }
    }

    fun addListener(l: (Snapshot) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Snapshot) -> Unit) { listeners.remove(l) }

    fun reset() {
        snapshot = Snapshot()
        val s = snapshot
        main.post { listeners.forEach { it(s) } }
    }
}

// CopyOnWriteArrayList ليس متاحاً مباشرة باسم مختصر هنا — غلاف بسيط
private class CopyOnWriteArrayListList<T> {
    private val list = java.util.concurrent.CopyOnWriteArrayList<T>()
    fun add(e: T) { list.add(e) }
    fun remove(e: T) { list.remove(e) }
    fun forEach(action: (T) -> Unit) { list.forEach(action) }
}
