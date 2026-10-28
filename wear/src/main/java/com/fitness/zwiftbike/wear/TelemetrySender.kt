package com.fitness.zwiftbike.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

/**
 * Transmits live Galaxy Watch 4 telemetry over both:
 * 1. Google Play Services Wearable MessageClient ("/velopulse/telemetry")
 * 2. Local Wi-Fi UDP broadcast fallback on port 45454
 */
class TelemetrySender(context: Context) {
    private val appContext = context.applicationContext
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)

    private val _connectedNodeCount = MutableStateFlow(0)
    val connectedNodeCount: StateFlow<Int> = _connectedNodeCount.asStateFlow()

    companion object {
        const val TELEMETRY_PATH = "/velopulse/telemetry"
        const val UDP_PORT = 45454
    }

    suspend fun sendTelemetry(mode: String, rpm: Float, bpm: Int, timestampMs: Long) {
        val formattedRpm = String.format(Locale.US, "%.1f", rpm)
        val payloadString = "v1|$mode|$formattedRpm|$bpm|$timestampMs"
        val payloadBytes = payloadString.toByteArray(Charsets.UTF_8)

        withContext(Dispatchers.IO) {
            // 1. Wearable Data Layer MessageClient
            runCatching {
                val nodes = nodeClient.connectedNodes.await()
                _connectedNodeCount.value = nodes.size
                for (node in nodes) {
                    messageClient.sendMessage(node.id, TELEMETRY_PATH, payloadBytes).await()
                }
            }.onFailure {
                _connectedNodeCount.value = 0
            }

            // 2. UDP Local Broadcast Fallback (for Wi-Fi hotspot or emulator testing)
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val broadcastAddress = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(
                        payloadBytes,
                        payloadBytes.size,
                        broadcastAddress,
                        UDP_PORT
                    )
                    socket.send(packet)
                }
            }
        }
    }
}
