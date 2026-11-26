package com.pro.remotecontrol

import android.content.Context
import android.content.Intent
import org.webrtc.*

/**
 * Envoltorio delgado sobre la librería WebRTC (io.getstream:stream-webrtc-android).
 * Un lado (host) agrega una pista de video capturada de la pantalla.
 * El otro lado (joiner) solo recibe video y envía toques por el DataChannel.
 */
class WebRtcClient(
    private val context: Context,
    private val signaling: SignalingClient,
    private val isHost: Boolean,
    private val onRemoteVideoTrack: (VideoTrack) -> Unit,
    private val onTouchCommand: (TouchCommand) -> Unit
) {
    private val eglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun iceServers() = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    /** Lado controlado: crea el PeerConnection, agrega la pista de pantalla y el DataChannel de entrada. */
    fun startAsHost(mediaProjectionPermission: Intent) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers())
        peerConnection = factory.createPeerConnection(rtcConfig, object : SimplePeerObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                signaling.sendIceCandidate(fromHost = true, candidate)
            }
            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                observeDataChannel(channel)
            }
        })

        videoCapturer = ScreenCapturerAndroid(mediaProjectionPermission, object : MediaProjection.Callback() {})
        videoSource = factory.createVideoSource(true)
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoCapturer?.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = factory.createVideoTrack("screen0", videoSource)
        peerConnection?.addTrack(localVideoTrack)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                signaling.createSession(desc.description) {
                    signaling.listenForAnswer { answerSdp ->
                        peerConnection?.setRemoteDescription(
                            SimpleSdpObserver(),
                            SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                        )
                    }
                    signaling.listenForIceCandidates(fromHost = false) { peerConnection?.addIceCandidate(it) }
                }
            }
        }, constraints)
    }

    /** Lado controlador: responde a la oferta, se suscribe al video remoto y abre el canal de toques. */
    fun startAsJoiner(offerSdp: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers())
        peerConnection = factory.createPeerConnection(rtcConfig, object : SimplePeerObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                signaling.sendIceCandidate(fromHost = false, candidate)
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) onRemoteVideoTrack(track)
            }
        })

        dataChannel = peerConnection?.createDataChannel("touch", DataChannel.Init())
        dataChannel?.let { observeDataChannel(it) }

        peerConnection?.setRemoteDescription(
            SimpleSdpObserver(),
            SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        )
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                signaling.sendAnswer(desc.description)
                signaling.listenForIceCandidates(fromHost = true) { peerConnection?.addIceCandidate(it) }
            }
        }, MediaConstraints())
    }

    /** Llamado desde el controlador al tocar el video remoto. Coordenadas normalizadas 0f..1f. */
    fun sendTouch(cmd: TouchCommand) {
        val payload = "${cmd.action},${cmd.x},${cmd.y}"
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(payload.toByteArray()), false
        )
        dataChannel?.send(buffer)
    }

    private fun observeDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val parts = String(bytes).split(",")
                if (parts.size == 3) {
                    onTouchCommand(
                        TouchCommand(
                            action = parts[0],
                            x = parts[1].toFloat(),
                            y = parts[2].toFloat()
                        )
                    )
                }
            }
        })
    }

    fun release() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        videoSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        signaling.close()
    }
}

data class TouchCommand(val action: String, val x: Float, val y: Float)

open class SimplePeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
