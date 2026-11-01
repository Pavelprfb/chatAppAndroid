package com.chatapp.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallUiState(
    val conversationId: String,
    val participantName: String,
    val mediaType: String,
    val status: String,
    val callId: String? = null,
    val incoming: Boolean = false,
    val muted: Boolean = false,
    val cameraEnabled: Boolean = true,
    val speakerEnabled: Boolean = false,
    val error: String? = null
)

class WebRtcCallManager(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn = false
    private var audioFocusHeld = false
    private val eglBase: EglBase
    private val factory: PeerConnectionFactory
    private val mutableState = MutableStateFlow<CallUiState?>(null)
    val state = mutableState.asStateFlow()

    private var sendSignal: ((String, JSONObject, (JSONObject) -> Unit) -> Unit)? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: org.webrtc.SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var pendingRemoteOffer: SessionDescription? = null
    private var iceServers = JSONArray()
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val pendingCallSignals = mutableMapOf<String, MutableList<Pair<String, JSONObject>>>()
    private var remoteDescriptionSet = false
    private var frontCamera = true

    init {
        synchronized(PeerConnectionFactory::class.java) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun setSignalSender(sender: (String, JSONObject, (JSONObject) -> Unit) -> Unit) {
        sendSignal = { event, payload, callback ->
            sender(event, payload) { response ->
                onMain { callback(response) }
            }
        }
    }

    fun onSignal(event: String, payload: JSONObject) {
        onMain { handleSignal(event, payload) }
    }

    private fun handleSignal(event: String, payload: JSONObject) {
        val current = mutableState.value
        val callId = payload.optString("callId")
        if (current != null && !current.incoming && current.callId == null
            && current.status == "Starting call" && callId.isNotBlank()
            && event in setOf("call:accepted", "call:ended", "call:ice")) {
            pendingCallSignals.getOrPut(callId, ::mutableListOf).add(event to payload)
            return
        }
        when (event) {
            "call:incoming" -> receiveIncoming(payload)
            "call:accepted" -> receiveAnswer(payload)
            "call:ice" -> receiveIceCandidate(payload)
            "call:ended" -> {
                if (payload.optString("callId") == mutableState.value?.callId) {
                    val reason = when (payload.optString("reason")) {
                        "declined" -> "Call declined"
                        "timeout" -> "No answer"
                        "maximum-duration", "ended" -> "Call ended"
                        else -> "Call ended"
                    }
                    finishCall(reason, notifyPeer = false)
                }
            }
        }
    }

    fun startCall(conversationId: String, participantName: String, mediaType: String) {
        if (mutableState.value != null) return
        if (mediaType !in setOf("audio", "video")) {
            mutableState.value = CallUiState(conversationId, participantName, mediaType, "error", error = "Unsupported call type")
            return
        }
        mutableState.value = CallUiState(
            conversationId = conversationId,
            participantName = participantName,
            mediaType = mediaType,
            status = "Starting call"
        )
        val sender = sendSignal
        if (sender == null) {
            finishCall("Live connection is unavailable", notifyPeer = false, failed = true)
            return
        }
        sender("call:prepare", JSONObject().put("conversationId", conversationId)) { response ->
            if (!response.optBoolean("ok")) {
                finishCall(
                    response.optString("error").ifBlank { "Unable to prepare the call" },
                    notifyPeer = false,
                    failed = true
                )
                return@sender
            }
            try {
                preparePeerConnection(response.optJSONArray("iceServers") ?: JSONArray())
                createOffer(conversationId, mediaType)
            } catch (error: Exception) {
                finishCall(error.message ?: "Unable to start the call", notifyPeer = false, failed = true)
            }
        }
    }

    fun acceptCall() {
        val current = mutableState.value ?: return
        val offer = pendingRemoteOffer
        if (!current.incoming || current.status != "Incoming call" || offer == null) return
        mutableState.value = current.copy(status = "Answering")
        try {
            preparePeerConnection(iceServers)
            peerConnection?.setRemoteDescription(descriptionObserver(
                onSet = {
                    remoteDescriptionSet = true
                    flushRemoteCandidates()
                    val callId = current.callId
                    if (callId == null) {
                        finishCall("The incoming call expired", notifyPeer = false, failed = true)
                    } else {
                        createAnswer(callId)
                    }
                },
                onFailure = { error -> finishCall(error, notifyPeer = true, failed = true) }
            ), offer)
        } catch (error: Exception) {
            finishCall(error.message ?: "Unable to accept the call", notifyPeer = true, failed = true)
        }
    }

    fun declineCall() {
        finishCall("Call declined", notifyPeer = true)
    }

    fun endCall() {
        finishCall("Call ended", notifyPeer = true)
    }

    fun dismissCall() {
        mutableState.value = null
    }

    fun dispose() {
        if (mutableState.value != null) {
            finishCall("Call ended", notifyPeer = true)
        }
        releaseRenderers()
        factory.dispose()
        eglBase.release()
    }

    fun toggleMicrophone() {
        val muted = !(mutableState.value?.muted ?: false)
        audioTrack?.setEnabled(!muted)
        mutableState.value = mutableState.value?.copy(muted = muted)
    }

    fun toggleCamera() {
        val enabled = !(mutableState.value?.cameraEnabled ?: false)
        videoTrack?.setEnabled(enabled)
        mutableState.value = mutableState.value?.copy(cameraEnabled = enabled)
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                onMain {
                    frontCamera = isFrontCamera
                    localRenderer?.setMirror(frontCamera)
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                onMain { showError(errorDescription ?: "Unable to switch camera") }
            }
        })
    }

    fun toggleSpeaker() {
        val enabled = !(mutableState.value?.speakerEnabled ?: false)
        audioManager.isSpeakerphoneOn = enabled
        mutableState.value = mutableState.value?.copy(speakerEnabled = enabled)
    }

    fun permissionDenied() {
        val current = mutableState.value ?: return
        finishCall(
            if (current.mediaType == "video") "Camera and microphone permission are required"
            else "Microphone permission is required",
            notifyPeer = current.incoming,
            failed = true
        )
    }

    fun createRenderer(context: Context, local: Boolean): SurfaceViewRenderer =
        SurfaceViewRenderer(context).also { renderer ->
            renderer.init(eglBase.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            renderer.setMirror(local && frontCamera)
            if (local) {
                localRenderer?.release()
                localRenderer = renderer
                videoTrack?.addSink(renderer)
            } else {
                remoteRenderer?.release()
                remoteRenderer = renderer
                remoteVideoTrack?.addSink(renderer)
            }
        }

    fun releaseRenderer(view: View, local: Boolean) {
        val renderer = view as? SurfaceViewRenderer ?: return
        if (local) {
            videoTrack?.removeSink(renderer)
            if (localRenderer === renderer) localRenderer = null
        } else {
            remoteVideoTrack?.removeSink(renderer)
            if (remoteRenderer === renderer) remoteRenderer = null
        }
        renderer.release()
    }

    fun releaseRenderers() {
        localRenderer?.let { releaseRenderer(it, true) }
        remoteRenderer?.let { releaseRenderer(it, false) }
    }

    private var remoteVideoTrack: VideoTrack? = null

    private fun receiveIncoming(payload: JSONObject) {
        val callId = payload.optString("callId").takeIf(String::isNotBlank) ?: return
        if (mutableState.value != null) {
            sendSignal?.invoke("call:decline", JSONObject().put("callId", callId)) {}
            return
        }
        val caller = payload.optJSONObject("caller")
        val conversationId = payload.optString("conversationId")
        val mediaType = payload.optString("mediaType")
        val offerJson = payload.optJSONObject("offer")
        val sdp = offerJson?.optString("sdp").orEmpty()
        if (conversationId.isBlank() || mediaType !in setOf("audio", "video") || sdp.isBlank()) return

        pendingRemoteOffer = SessionDescription(
            SessionDescription.Type.fromCanonicalForm("offer"),
            sdp
        )
        iceServers = payload.optJSONArray("iceServers") ?: JSONArray()
        mutableState.value = CallUiState(
            conversationId = conversationId,
            participantName = caller?.optString("displayName").orEmpty().ifBlank { "ChatApp user" },
            mediaType = mediaType,
            status = "Incoming call",
            callId = callId,
            incoming = true,
            speakerEnabled = mediaType == "video"
        )
    }

    private fun receiveAnswer(payload: JSONObject) {
        val current = mutableState.value ?: return
        if (payload.optString("callId") != current.callId) return
        val answer = payload.optJSONObject("answer") ?: return
        val sdp = answer.optString("sdp")
        if (sdp.isBlank()) {
            showError("The call answer was empty")
            return
        }
        peerConnection?.setRemoteDescription(descriptionObserver(
            onSet = {
                remoteDescriptionSet = true
                flushRemoteCandidates()
                mutableState.value = mutableState.value?.copy(status = "Connecting media")
            },
            onFailure = { error -> finishCall(error, notifyPeer = true, failed = true) }
        ), SessionDescription(SessionDescription.Type.fromCanonicalForm("answer"), sdp))
    }

    private fun receiveIceCandidate(payload: JSONObject) {
        val current = mutableState.value ?: return
        if (payload.optString("callId") != current.callId) return
        val json = payload.optJSONObject("candidate") ?: return
        val candidate = IceCandidate(
            json.optString("sdpMid"),
            json.optInt("sdpMLineIndex"),
            json.optString("candidate")
        )
        if (candidate.sdp.isBlank()) return
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingRemoteCandidates.add(candidate)
        }
    }

    private fun preparePeerConnection(serverJson: JSONArray) {
        val servers = mutableListOf<PeerConnection.IceServer>()
        for (index in 0 until serverJson.length()) {
            val server = serverJson.optJSONObject(index) ?: continue
            val urls = server.opt("urls")
            val builder = when (urls) {
                is JSONArray -> {
                    val urlList = (0 until urls.length()).mapNotNull { urls.optString(it).takeIf(String::isNotBlank) }
                    if (urlList.isEmpty()) continue else PeerConnection.IceServer.builder(urlList)
                }
                is String -> if (urls.isBlank()) continue else PeerConnection.IceServer.builder(urls)
                else -> continue
            }
            if (server.has("username")) {
                builder.setUsername(server.optString("username"))
                builder.setPassword(server.optString("credential"))
            }
            servers.add(builder.createIceServer())
        }
        if (servers.isEmpty()) {
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        val configuration = PeerConnection.RTCConfiguration(servers)
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory.createPeerConnection(configuration, createPeerObserver())
            ?: error("Unable to create WebRTC peer connection")
        startCallAudio()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val useSpeaker = mutableState.value?.mediaType == "video"
        audioManager.isSpeakerphoneOn = useSpeaker
        mutableState.value = mutableState.value?.copy(speakerEnabled = useSpeaker)
        addLocalMedia()
    }

    private fun startCallAudio() {
        previousAudioMode = audioManager.mode
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        audioFocusHeld = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!audioFocusHeld) {
            error("Unable to acquire audio focus for the call")
        }
    }

    private fun addLocalMedia() {
        val current = mutableState.value ?: error("No active call")
        val constraints = MediaConstraints()
        audioSource = factory.createAudioSource(constraints)
        audioTrack = factory.createAudioTrack("audio-${System.nanoTime()}", audioSource)
        peerConnection?.addTrack(audioTrack, listOf("chatapp-call"))

        if (current.mediaType == "video") {
            val enumerator = Camera2Enumerator(appContext)
            val cameraName = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
                ?: enumerator.deviceNames.firstOrNull()
                ?: error("No camera is available")
            frontCamera = enumerator.isFrontFacing(cameraName)
            videoCapturer = enumerator.createCapturer(cameraName, null)
                ?: error("Unable to open the camera")
            videoSource = factory.createVideoSource(false)
            val capturer = videoCapturer ?: error("Unable to open the camera")
            surfaceTextureHelper = org.webrtc.SurfaceTextureHelper.create(
                "ChatAppCamera",
                eglBase.eglBaseContext
            ) ?: error("Unable to initialize the camera")
            capturer.initialize(surfaceTextureHelper, appContext, videoSource?.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            videoTrack = factory.createVideoTrack("video-${System.nanoTime()}", videoSource)
            videoTrack?.setEnabled(true)
            peerConnection?.addTrack(videoTrack, listOf("chatapp-call"))
            localRenderer?.let { videoTrack?.addSink(it) }
        }
    }

    private fun createOffer(conversationId: String, mediaType: String) {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peerConnection?.setLocalDescription(descriptionObserver(
                    onSet = {
                        val payload = JSONObject()
                            .put("conversationId", conversationId)
                            .put("mediaType", mediaType)
                            .put("offer", JSONObject().put("type", "offer").put("sdp", description.description))
                        sendSignal?.invoke("call:start", payload) { response ->
                            if (!response.optBoolean("ok")) {
                                finishCall(response.optString("error").ifBlank { "The call could not be started" }, false, true)
                                return@invoke
                            }
                            val callId = response.optString("callId")
                            val current = mutableState.value
                            if (current == null || current.status in CLOSED_CALL_STATUSES) {
                                current?.let { mutableState.value = it.copy(callId = callId) }
                                sendSignal?.invoke("call:end", JSONObject().put("callId", callId)) {}
                                return@invoke
                            }
                            mutableState.value = current?.copy(
                                callId = callId,
                                status = "Ringing"
                            )
                            for ((event, signal) in pendingCallSignals.remove(callId).orEmpty()) {
                                if (mutableState.value?.status in CLOSED_CALL_STATUSES) break
                                onSignal(event, signal)
                            }
                            if (mutableState.value?.status in CLOSED_CALL_STATUSES) return@invoke
                            pendingLocalCandidates.toList().forEach { sendIceCandidate(callId, it) }
                            pendingLocalCandidates.clear()
                        }
                    },
                    onFailure = { error -> finishCall(error, notifyPeer = true, failed = true) }
                ), description)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) = onMain { finishCall(error, false, true) }
            override fun onSetFailure(error: String) = onMain { finishCall(error, false, true) }
        }, MediaConstraints())
    }

    private fun createAnswer(callId: String) {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                peerConnection?.setLocalDescription(descriptionObserver(
                    onSet = {
                        val payload = JSONObject()
                            .put("callId", callId)
                            .put("answer", JSONObject().put("type", "answer").put("sdp", description.description))
                        sendSignal?.invoke("call:accept", payload) { response ->
                            if (!response.optBoolean("ok")) {
                                finishCall(
                                    response.optString("error").ifBlank { "Unable to accept the call" },
                                    true,
                                    true
                                )
                            } else {
                                mutableState.value = mutableState.value?.copy(
                                    status = "Connecting media",
                                    incoming = false
                                )
                                flushPendingLocalCandidates()
                            }
                        }
                    },
                    onFailure = { error -> finishCall(error, notifyPeer = true, failed = true) }
                ), description)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) = onMain { finishCall(error, true, true) }
            override fun onSetFailure(error: String) = onMain { finishCall(error, true, true) }
        }, MediaConstraints())
    }

    private fun createPeerObserver(): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onMain {
                    if (state == PeerConnection.IceConnectionState.CONNECTED
                        || state == PeerConnection.IceConnectionState.COMPLETED) {
                        mutableState.value = mutableState.value?.copy(status = "Connected")
                    } else if (state == PeerConnection.IceConnectionState.FAILED) {
                        finishCall(
                            "Could not establish a media connection. Check TURN server settings.",
                            notifyPeer = true,
                            failed = true
                        )
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                onMain {
                    val callId = mutableState.value?.callId
                    if (callId == null) pendingLocalCandidates.add(candidate) else sendIceCandidate(callId, candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) {
                onMain { stream.videoTracks.firstOrNull()?.let(::setRemoteVideoTrack) }
            }
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {
                onMain {
                    stream.videoTracks.forEach { it.removeSink(remoteRenderer) }
                    remoteVideoTrack = null
                }
            }
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
                onMain {
                    (receiver.track() as? VideoTrack)?.let(::setRemoteVideoTrack)
                    mediaStreams.firstOrNull()?.videoTracks?.firstOrNull()?.let(::setRemoteVideoTrack)
                }
            }
        }

    private fun setRemoteVideoTrack(track: VideoTrack) {
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = track
        remoteRenderer?.let(track::addSink)
    }

    private fun sendIceCandidate(callId: String, candidate: IceCandidate) {
        val candidateJson = JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
        sendSignal?.invoke(
            "call:ice",
            JSONObject().put("callId", callId).put("candidate", candidateJson)
        ) { response ->
            if (!response.optBoolean("ok")) showError(response.optString("error"))
        }
    }

    private fun flushPendingLocalCandidates() {
        val callId = mutableState.value?.callId ?: return
        pendingLocalCandidates.toList().forEach { sendIceCandidate(callId, it) }
        pendingLocalCandidates.clear()
    }

    private fun flushRemoteCandidates() {
        pendingRemoteCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    private fun descriptionObserver(
        onSet: () -> Unit,
        onFailure: (String) -> Unit
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = onMain(onSet)
        override fun onCreateFailure(error: String) = onMain { onFailure(error) }
        override fun onSetFailure(error: String) = onMain { onFailure(error) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun showError(message: String) {
        finishCall(message, notifyPeer = true, failed = true)
    }

    private fun finishCall(message: String, notifyPeer: Boolean, failed: Boolean = false) {
        val current = mutableState.value ?: return
        if (notifyPeer && current.callId != null) {
            val event = if (current.incoming) "call:decline" else "call:end"
            sendSignal?.invoke(event, JSONObject().put("callId", current.callId)) {}
        }
        releaseMedia()
        mutableState.value = current.copy(
            status = if (failed) "Call error" else message,
            incoming = false,
            error = if (failed) message else null
        )
    }

    private fun releaseMedia() {
        localRenderer?.let { videoTrack?.removeSink(it) }
        remoteRenderer?.let { remoteVideoTrack?.removeSink(it) }
        remoteVideoTrack = null
        try {
            videoCapturer?.stopCapture()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Interrupted while stopping the camera", error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to stop the camera cleanly", error)
        }
        videoCapturer?.dispose()
        videoCapturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        pendingRemoteOffer = null
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        pendingCallSignals.clear()
        remoteDescriptionSet = false
        audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
        audioManager.mode = previousAudioMode
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        audioFocusHeld = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isMicrophoneMute = false
        }
    }

    companion object {
        private const val TAG = "WebRtcCallManager"
        private val CLOSED_CALL_STATUSES = setOf(
            "Call ended",
            "Call declined",
            "Call error",
            "No answer",
            "Call timed out"
        )

        @Volatile
        private var factoryInitialized = false
    }
}
