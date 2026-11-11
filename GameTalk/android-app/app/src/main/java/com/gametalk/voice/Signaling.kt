package com.gametalk.voice

import org.json.JSONObject

/**
 * واجهة طبقة الإشارة — تفصل الخدمة عن تفاصيل الخادم.
 * الإصدار 1.1 يستخدم PeerSignaling (سحابة PeerJS العامة) ولا يحتاج خادماً خاصاً.
 */
interface Signaling {

    /** هل اتصال الإشارة مفتوح الآن؟ */
    val isOpen: Boolean

    fun connect()
    fun close()
    fun sendOffer(sdp: String, code: String)
    fun sendAnswer(sdp: String, code: String)
    fun sendIce(candidate: JSONObject, code: String)

    interface Callbacks {
        /** افتُح WebSocket (قبل التسجيل) */
        fun onServerConnected()
        /** المنشئ: تم تسجيل غرفته — الرمز جاهز للمشاركة */
        fun onCreated(code: String)
        /** المنضم: تم تسجيله في الغرفة — يبدأ تأسيس الاتصال */
        fun onJoined(code: String)
        /** الطرف الآخر قطع اتصاله */
        fun onPeerLeft()
        fun onOffer(sdp: String)
        fun onAnswer(sdp: String)
        fun onIce(candidate: JSONObject)
        /** المقعد مشغول بجهاز آخر */
        fun onRoomFull()
        /** انقطع اتصال الإشارة */
        fun onDisconnected()
    }
}
