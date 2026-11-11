package com.gametalk.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.RTCStatsReport

/**
 * محرك المكالمة الصوتية (WebRTC).
 * صوت فقط + Opus منخفض البت (~24kbps + DTX) لاستهلاك إنترنت خفيف جداً.
 */
class CallEngine(private val context: Context) {

    companion object {
        private const val TAG = "CallEngine"
        private const val MAX_AUDIO_BITRATE_BPS = 24000   // ~10.8 ميجابايت/ساعة
        private const val AUDIO_TRACK_ID = "gt_audio"
        private const val STREAM_ID = "gt_stream"

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
    }

    interface Listener {
        fun onLocalIce(candidate: IceCandidate)
        fun onPeerConnectionState(state: PeerConnection.PeerConnectionState)
    }

    private val main = Handler(Looper.getMainLooper())
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var statsRunnable: Runnable? = null
    private var listener: Listener? = null

    @Volatile var isMuted = false
        private set

    fun ensureInitialized() {
        if (factory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()
        }
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    }

    fun createPeerConnection(listener: Listener): Boolean {
        ensureInitialized()
        this.listener = listener
        closePeerConnection()

        val config = PeerConnection.RTCConfiguration(ArrayList(ICE_SERVERS)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIce(candidate)
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "PC state: $newState")
                main.post { listener.onPeerConnectionState(newState) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                Log.i(TAG, "Remote audio track added")
            }
        }

        val connection = factory?.createPeerConnection(config, observer) ?: return false

        // الميكروفون
        val src = factory!!.createAudioSource(MediaConstraints())
        val track = factory!!.createAudioTrack(AUDIO_TRACK_ID, src)
        track.setEnabled(true)
        connection.addTrack(track, listOf(STREAM_ID))

        pc = connection
        audioSource = src
        audioTrack = track
        isMuted = false
        routeAudioToSpeaker()
        startStatsLoop()
        return true
    }

    /** توجيه الصوت إلى مكبر السماعة (وضع الاتصال) */
    private fun routeAudioToSpeaker() {
        val am = audioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val devices = am.availableCommunicationDevices
                val speaker = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                speaker?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "speaker routing: ${e.message}")
        }
    }

    // ---------- SDP ----------

    fun createOffer(onSdp: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val munged = SessionDescription(
                    SessionDescription.Type.OFFER,
                    optimizeOpusSdp(desc.description)
                )
                pc?.setLocalDescription(SdpObserverAdapter(), munged)
                onSdp(munged.description)
            }
        }, constraints)
    }

    fun acceptOfferAndAnswer(remoteSdp: String, onAnswer: (String) -> Unit) {
        val conn = pc ?: return
        conn.setRemoteDescription(
            SdpObserverAdapter(),
            SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        )
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        conn.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val munged = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    optimizeOpusSdp(desc.description)
                )
                conn.setLocalDescription(SdpObserverAdapter(), munged)
                onAnswer(munged.description)
            }
        }, constraints)
    }

    fun setRemoteAnswer(remoteSdp: String) {
        pc?.setRemoteDescription(
            SdpObserverAdapter(),
            SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        )
    }

    fun addRemoteIce(candidate: IceCandidate) {
        try { pc?.addIceCandidate(candidate) } catch (e: Exception) {
            Log.w(TAG, "addIce: ${e.message}")
        }
    }

    /** خفض بِت-ريت Opus + DTX → استهلاك أقل كثيراً أثناء الصمت */
    private fun optimizeOpusSdp(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val params = ";maxaveragebitrate=$MAX_AUDIO_BITRATE_BPS;usedtx=1;stereo=0"
        var idx = lines.indexOfFirst { it.startsWith("a=fmtp:111 ") }
        if (idx >= 0) {
            lines[idx] = lines[idx] + params
        } else {
            val rtpmap = lines.indexOfFirst { it.startsWith("a=rtpmap:111 opus") }
            if (rtpmap >= 0) lines.add(rtpmap + 1, "a=fmtp:111 usedtx=1" + params)
        }
        return lines.joinToString("\r\n")
    }

    // ---------- كتم الصوت ----------

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        audioTrack?.setEnabled(!isMuted)
        return isMuted
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        audioTrack?.setEnabled(!muted)
    }

    // ---------- قياس الجودة (RTT كل 3 ثوانٍ) ----------

    private fun startStatsLoop() {
        stopStatsLoop()
        val r = object : Runnable {
            override fun run() {
                pc?.getStats { report -> evaluateQuality(report) }
                main.postDelayed(this, 3000)
            }
        }
        statsRunnable = r
        main.post(r)
    }

    private fun stopStatsLoop() {
        statsRunnable?.let { main.removeCallbacks(it) }
        statsRunnable = null
    }

    private fun evaluateQuality(report: RTCStatsReport) {
        var rttSec: Double? = null
        for ((_, stats) in report.statsMap) {
            if (stats.type == "candidate-pair") {
                val selected = stats.members["selected"] as? Boolean == true
                val state = stats.members["state"] as? String
                val rtt = stats.members["currentRoundTripTime"] as? Double
                if (rtt != null && (selected || state == "succeeded")) {
                    rttSec = rtt
                }
            }
        }
        val rttMs = rttSec?.let { (it * 1000).toInt() }
        val quality = when {
            rttMs == null -> CallBus.Quality.UNKNOWN
            rttMs < 130 -> CallBus.Quality.GOOD
            rttMs < 300 -> CallBus.Quality.FAIR
            else -> CallBus.Quality.BAD
        }
        CallBus.update { it.copy(quality = quality, rttMs = rttMs) }
    }

    // ---------- الإغلاق ----------

    fun closePeerConnection() {
        stopStatsLoop()
        try { pc?.close() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        pc = null
        audioSource = null
        audioTrack = null
    }

    fun release() {
        closePeerConnection()
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        restoreAudio()
    }

    private fun restoreAudio() {
        val am = audioManager ?: return
        try {
            am.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT < 31) {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {}
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "createFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "setFailure: $error")
        }
    }
}
