package com.pro.remotecontrol

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.webrtc.IceCandidate
import kotlin.random.Random

/**
 * Señalización simple usando Firestore como "buzón":
 * /sessions/{code} { offer, answer, hostReady, joinReady }
 * /sessions/{code}/hostCandidates/{auto}
 * /sessions/{code}/joinCandidates/{auto}
 *
 * No requiere servidor propio: solo un proyecto de Firebase con Firestore
 * en modo de prueba (o con reglas restringidas por código, ver README).
 */
class SignalingClient(private val code: String) {

    private val db = FirebaseFirestore.getInstance()
    private val sessionRef = db.collection("sessions").document(code)
    private val registrations = mutableListOf<ListenerRegistration>()

    companion object {
        fun generateCode(): String = (100000..999999).random().toString()
    }

    fun createSession(offerSdp: String, onDone: () -> Unit) {
        val data = hashMapOf(
            "offer" to offerSdp,
            "createdAt" to System.currentTimeMillis()
        )
        sessionRef.set(data).addOnSuccessListener { onDone() }
    }

    fun fetchOffer(onResult: (String?) -> Unit) {
        sessionRef.get().addOnSuccessListener { doc ->
            onResult(doc.getString("offer"))
        }
    }

    fun sendAnswer(answerSdp: String) {
        sessionRef.update("answer", answerSdp)
    }

    fun listenForAnswer(onAnswer: (String) -> Unit) {
        val reg = sessionRef.addSnapshotListener { snap, _ ->
            val answer = snap?.getString("answer")
            if (answer != null) onAnswer(answer)
        }
        registrations.add(reg)
    }

    fun sendIceCandidate(fromHost: Boolean, candidate: IceCandidate) {
        val sub = if (fromHost) "hostCandidates" else "joinCandidates"
        val data = hashMapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.sdp
        )
        sessionRef.collection(sub).add(data)
    }

    fun listenForIceCandidates(fromHost: Boolean, onCandidate: (IceCandidate) -> Unit) {
        val sub = if (fromHost) "hostCandidates" else "joinCandidates"
        val reg = sessionRef.collection(sub).addSnapshotListener { snaps, _ ->
            snaps?.documentChanges?.forEach { change ->
                val d = change.document
                val candidate = IceCandidate(
                    d.getString("sdpMid"),
                    (d.getLong("sdpMLineIndex") ?: 0).toInt(),
                    d.getString("candidate")
                )
                onCandidate(candidate)
            }
        }
        registrations.add(reg)
    }

    fun close() {
        registrations.forEach { it.remove() }
        registrations.clear()
    }

    fun deleteSession() {
        sessionRef.delete()
    }
}
