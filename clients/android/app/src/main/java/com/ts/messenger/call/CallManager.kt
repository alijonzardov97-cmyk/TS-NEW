package com.ts.messenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.StringRes
import com.ts.messenger.R
import com.ts.messenger.net.ChatSocket
import com.ts.messenger.push.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.security.MessageDigest
import java.util.UUID

enum class CallPhase { Idle, Ringing, Calling, Connecting, Active }

data class CallUi(
    val phase: CallPhase = CallPhase.Idle,
    val channelId: String = "",
    val peerName: String = "",
    /** Verification code derived from both DTLS fingerprints; both sides must read the same. */
    val sas: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    @StringRes val notice: Int? = null,
)

/**
 * One-to-one audio call over WebRTC (DTLS-SRTP), signalled through the server's voice channel in
 * the same way as the web client. No STUN/TURN is used: candidates are host addresses only, which
 * is what a Tailscale network needs and avoids leaking addresses to third parties.
 *
 * The server relays the SDP and could in theory swap the DTLS fingerprints (a man in the middle),
 * so both phones show a code computed from the two fingerprints; reading it aloud proves that the
 * media is encrypted end to end between the two devices.
 *
 * All state changes happen on the scope's (main) dispatcher.
 */
class CallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val socket: ChatSocket,
    private val myId: String,
    /** STUN/TURN servers from the server config; empty means direct (host) candidates only. */
    private val iceServers: () -> List<PeerConnection.IceServer> = { emptyList() },
    /** Channel id -> (peer user id, display name) for a known 1:1 conversation. */
    private val peerOf: (String) -> Pair<String, String>?,
) {
    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private var peerId = ""
    private var sessionId = ""
    private var joiner = false
    private var peerSeen = false
    private var offerSent = false
    private var remoteSet = false
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var timeout: Job? = null
    private var lastVoiceOp = 0L

    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var savedMode = AudioManager.MODE_NORMAL

    private val phase get() = _ui.value.phase

    // ── User actions ──

    fun startOutgoing(channelId: String) {
        if (phase != CallPhase.Idle) return
        val (peer, name) = peerOf(channelId) ?: return
        peerId = peer
        sessionId = UUID.randomUUID().toString()
        joiner = false
        peerSeen = false
        _ui.value = CallUi(CallPhase.Calling, channelId, name)
        if (!beginMedia(name)) return
        sendVoice("join_voice", channelId)
        startTimeout(45_000, R.string.call_no_answer)
    }

    fun accept() {
        val ui = _ui.value
        if (ui.phase != CallPhase.Ringing) return
        sessionId = UUID.randomUUID().toString()
        joiner = true
        peerSeen = true // we were told the caller is there; if not, the first update ends the call
        Notifications.cancelCall(context)
        _ui.value = ui.copy(phase = CallPhase.Connecting)
        if (!beginMedia(ui.peerName)) return
        sendVoice("join_voice", ui.channelId)
        startTimeout(30_000, R.string.call_failed)
    }

    fun decline() {
        if (phase != CallPhase.Ringing) return
        Notifications.cancelCall(context)
        _ui.value = CallUi()
    }

    fun hangup() = finish(null, leave = true)

    fun toggleMute() {
        val m = !_ui.value.muted
        track?.setEnabled(!m)
        _ui.value = _ui.value.copy(muted = m)
    }

    fun toggleSpeaker() {
        val s = !_ui.value.speaker
        audio.isSpeakerphoneOn = s
        _ui.value = _ui.value.copy(speaker = s)
    }

    /** A push told us somebody is calling; show the incoming screen if we are free. */
    fun ringFromPush(channelId: String) {
        if (phase != CallPhase.Idle) return
        val (peer, name) = peerOf(channelId) ?: return
        peerId = peer
        _ui.value = CallUi(CallPhase.Ringing, channelId, name)
        startRingTimeout()
    }

    private fun startRingTimeout() {
        timeout?.cancel()
        timeout = scope.launch {
            delay(60_000)
            if (phase == CallPhase.Ringing) { Notifications.cancelCall(context); _ui.value = CallUi() }
        }
    }

    // ── Server events ──

    fun onEvent(type: String, data: JsonObject) {
        when (type) {
            "user_joined_voice" -> {
                val ch = data.str("channel_id") ?: return
                val who = data.str("user_id") ?: return
                if (who == myId) return
                val (peer, name) = peerOf(ch) ?: return
                if (who != peer) return
                when (phase) {
                    CallPhase.Idle -> {
                        peerId = peer
                        _ui.value = CallUi(CallPhase.Ringing, ch, name)
                        startRingTimeout()
                        Notifications.showIncomingCall(context, name, ch)
                    }
                    CallPhase.Calling -> if (ch == _ui.value.channelId) { peerSeen = true; setPhase(CallPhase.Connecting) }
                    else -> {}
                }
            }
            "voice_state_update" -> {
                val ch = data.str("channel_id") ?: return
                if (ch != _ui.value.channelId) return
                val present = (data["participants"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val peerHere = peerId in present
                when (phase) {
                    CallPhase.Ringing -> if (!peerHere) { Notifications.cancelCall(context); _ui.value = CallUi(notice = R.string.call_missed) }
                    CallPhase.Calling -> if (peerHere) { peerSeen = true; setPhase(CallPhase.Connecting) }
                    CallPhase.Connecting, CallPhase.Active -> {
                        if (peerHere) {
                            peerSeen = true
                            if (joiner && !offerSent) { offerSent = true; createOffer() }
                        } else if (peerSeen) {
                            finish(R.string.call_ended, leave = true)
                        }
                    }
                    CallPhase.Idle -> {}
                }
            }
            "user_left_voice" -> {
                val ch = data.str("channel_id") ?: return
                if (ch != _ui.value.channelId || data.str("user_id") != peerId) return
                when (phase) {
                    CallPhase.Ringing -> { Notifications.cancelCall(context); _ui.value = CallUi(notice = R.string.call_missed) }
                    CallPhase.Idle -> {}
                    else -> finish(R.string.call_ended, leave = true)
                }
            }
            "rtc_offer" -> if (data.str("from_user_id") == peerId && phase in ACTIVE_PHASES) handleOffer(data)
            "rtc_answer" -> if (data.str("from_user_id") == peerId && phase in ACTIVE_PHASES) handleAnswer(data)
            "rtc_ice_candidate" -> if (data.str("from_user_id") == peerId && phase in ACTIVE_PHASES) handleIce(data)
        }
    }

    // ── Media ──

    private fun beginMedia(name: String): Boolean {
        return try {
            val f = factory(context)
            val src = f.createAudioSource(MediaConstraints())
            source = src
            track = f.createAudioTrack("audio0", src).also { it.setEnabled(true) }
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = false
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                ).build().also { audio.requestAudioFocus(it) }
            CallService.start(context)
            true
        } catch (e: Exception) {
            finish(R.string.call_failed, leave = false)
            false
        }
    }

    private fun ensurePc(): PeerConnection? {
        pc?.let { return it }
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val p = factory(context).createPeerConnection(cfg, observer) ?: return null
        track?.let { p.addTrack(it, listOf("ts-stream")) }
        pc = p
        return p
    }

    private fun createOffer() {
        val p = ensurePc() ?: return finish(R.string.call_failed, leave = true)
        p.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                p.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        scope.launch {
                            if (pc !== p) return@launch
                            sendSignal("rtc_offer", sdpJson("offer", d.description))
                            maybeSas()
                        }
                    }
                    override fun onSetFailure(e: String?) { scope.launch { finish(R.string.call_failed, true) } }
                }, d)
            }
            override fun onCreateFailure(e: String?) { scope.launch { finish(R.string.call_failed, true) } }
        }, MediaConstraints())
    }

    private fun handleOffer(data: JsonObject) {
        if (pc != null && offerSent) return // we are the one who offers; ignore glare
        val sdp = parseSdp(data.str("sdp"), "offer") ?: return
        val theirSession = data.str("session_id")
        val p = ensurePc() ?: return finish(R.string.call_failed, leave = true)
        p.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                scope.launch {
                    if (pc !== p) return@launch
                    remoteSet = true
                    flushIce(p)
                    p.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(d: SessionDescription) {
                            p.setLocalDescription(object : Sdp() {
                                override fun onSetSuccess() {
                                    scope.launch {
                                        if (pc !== p) return@launch
                                        sendSignal("rtc_answer", sdpJson("answer", d.description), theirSession)
                                        maybeSas()
                                    }
                                }
                                override fun onSetFailure(e: String?) { scope.launch { finish(R.string.call_failed, true) } }
                            }, d)
                        }
                        override fun onCreateFailure(e: String?) { scope.launch { finish(R.string.call_failed, true) } }
                    }, MediaConstraints())
                }
            }
            override fun onSetFailure(e: String?) { scope.launch { finish(R.string.call_failed, true) } }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun handleAnswer(data: JsonObject) {
        val p = pc ?: return
        if (data.str("session_id") != sessionId) return
        val sdp = parseSdp(data.str("sdp"), "answer") ?: return
        p.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                scope.launch { if (pc !== p) return@launch; remoteSet = true; flushIce(p); maybeSas() }
            }
            override fun onSetFailure(e: String?) { scope.launch { finish(R.string.call_failed, true) } }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun handleIce(data: JsonObject) {
        val raw = data.str("candidate") ?: return
        if (raw.length > 2048) return
        val o = runCatching { com.ts.messenger.net.AppJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val line = o.str("candidate")?.takeIf { it.isNotEmpty() && it.length <= 1024 } ?: return
        val c = IceCandidate(o.str("sdpMid") ?: "0", o["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0, line)
        val p = pc
        if (p != null && remoteSet) p.addIceCandidate(c) else pendingIce += c
    }

    private fun flushIce(p: PeerConnection) {
        pendingIce.forEach { p.addIceCandidate(it) }
        pendingIce.clear()
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            scope.launch {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        if (phase == CallPhase.Connecting || phase == CallPhase.Calling) {
                            timeout?.cancel()
                            setPhase(CallPhase.Active)
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> finish(R.string.call_failed, leave = true)
                    else -> {}
                }
            }
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) {
            scope.launch {
                val json = buildJsonObject {
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                }.toString()
                sendSignal("rtc_ice_candidate", json, field = "candidate")
            }
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    // ── Helpers ──

    private fun setPhase(p: CallPhase) {
        _ui.value = _ui.value.copy(phase = p)
        if (p == CallPhase.Connecting) startTimeout(30_000, R.string.call_failed)
    }

    private fun startTimeout(ms: Long, @StringRes notice: Int) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(ms)
            if (phase == CallPhase.Calling || phase == CallPhase.Connecting) finish(notice, leave = true)
        }
    }

    private fun maybeSas() {
        val p = pc ?: return
        val local = fingerprint(p.localDescription?.description) ?: return
        val remote = fingerprint(p.remoteDescription?.description) ?: return
        val d = MessageDigest.getInstance("SHA-256").digest(listOf(local, remote).sorted().joinToString("|").toByteArray())
        // 72 bits -> 20 decimal digits: too much to grind for an attacker who must pick its
        // certificates before the two people compare the code.
        val v = java.math.BigInteger(1, d.copyOfRange(0, 9)).mod(java.math.BigInteger.TEN.pow(20))
        val digits = v.toString().padStart(20, '0')
        _ui.value = _ui.value.copy(sas = digits.chunked(4).joinToString(" "))
    }

    /** The one DTLS fingerprint of an SDP; null if there is none or if the lines disagree. */
    private fun fingerprint(sdp: String?): String? {
        if (sdp == null) return null
        val all = FP.findAll(sdp).map { it.groupValues[1].uppercase() }.toSet()
        return all.singleOrNull()
    }

    private fun sdpJson(type: String, sdp: String) =
        buildJsonObject { put("type", type); put("sdp", sdp) }.toString()

    private fun parseSdp(raw: String?, expected: String): String? {
        if (raw == null || raw.length > 32_768) return null
        val o = runCatching { com.ts.messenger.net.AppJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (o.str("type") != expected) return null
        val sdp = o.str("sdp")?.takeIf { it.isNotEmpty() } ?: return null
        // Media must be bound to exactly one SHA-256 DTLS fingerprint, otherwise the verification
        // code could be made to describe a different certificate than the one DTLS uses.
        if (fingerprint(sdp) == null || Regex("a=fingerprint:", RegexOption.IGNORE_CASE).findAll(sdp).count() !=
            FP.findAll(sdp).count()) return null
        return sdp
    }

    private fun sendSignal(type: String, payload: String, session: String? = null, field: String = "sdp") {
        socket.sendJson(buildJsonObject {
            put("type", type)
            put("target_user_id", peerId)
            put("session_id", session ?: sessionId)
            put(field, payload)
        })
    }

    /** The server rate-limits join/leave to one per two seconds; wait if necessary. */
    private fun sendVoice(type: String, channelId: String) {
        val wait = lastVoiceOp + 2_100 - System.currentTimeMillis()
        lastVoiceOp = System.currentTimeMillis() + maxOf(0, wait)
        scope.launch {
            if (wait > 0) delay(wait)
            socket.sendJson(buildJsonObject { put("type", type); put("channel_id", channelId) })
        }
    }

    private fun finish(notice: Int?, leave: Boolean) {
        if (phase == CallPhase.Idle) return
        timeout?.cancel()
        val ch = _ui.value.channelId
        if (leave && ch.isNotEmpty()) sendVoice("leave_voice", ch)
        runCatching { pc?.close(); pc?.dispose() }
        pc = null
        runCatching { track?.dispose(); source?.dispose() }
        track = null
        source = null
        pendingIce.clear()
        remoteSet = false
        offerSent = false
        peerSeen = false
        runCatching {
            audio.isSpeakerphoneOn = false
            audio.mode = savedMode
            focus?.let { audio.abandonAudioFocusRequest(it) }
        }
        focus = null
        CallService.stop(context)
        Notifications.cancelCall(context)
        _ui.value = CallUi(notice = notice)
    }

    fun clearNotice() { if (phase == CallPhase.Idle) _ui.value = CallUi() }

    /** Releases everything when the user signs out. */
    fun shutdown() = finish(null, leave = true)

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private companion object {
        val ACTIVE_PHASES = setOf(CallPhase.Calling, CallPhase.Connecting, CallPhase.Active)
        val FP = Regex("a=fingerprint:sha-256 ([0-9A-Fa-f:]+)", RegexOption.IGNORE_CASE)

        @Volatile private var factory: PeerConnectionFactory? = null

        fun factory(context: Context): PeerConnectionFactory {
            factory?.let { return it }
            synchronized(this) {
                factory?.let { return it }
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
                )
                return PeerConnectionFactory.builder().createPeerConnectionFactory().also { factory = it }
            }
        }
    }
}
