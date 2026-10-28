package com.fitness.zwiftbike

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

data class WatchTelemetryPacket(
    val mode: String,
    val rpm: Float,
    val bpm: Int,
    val timestampMs: Long
)

/**
 * Listens for live Galaxy Watch 4 packets via both:
 * 1. Google Play Services Wearable MessageClient ("/velopulse/telemetry")
 * 2. Local UDP socket on port 45454
 */
class TelemetryReceiver(
    context: Context,
    private val onPacket: (WatchTelemetryPacket) -> Unit
) : MessageClient.OnMessageReceivedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    companion object {
        const val TELEMETRY_PATH = "/velopulse/telemetry"
        const val UDP_PORT = 45454

        fun parsePayload(raw: String): WatchTelemetryPacket? {
            val parts = raw.trim().split("|")
            if (parts.size < 5 || parts[0] != "v1") return null
            val mode = parts[1].uppercase()
            val rpm = parts[2].toFloatOrNull() ?: return null
            val bpm = parts[3].toIntOrNull() ?: return null
            val ts = parts[4].toLongOrNull() ?: System.currentTimeMillis()
            return WatchTelemetryPacket(mode = mode, rpm = rpm, bpm = bpm, timestampMs = ts)
        }
    }

    fun start() {
        runCatching {
            Wearable.getMessageClient(appContext).addListener(this)
        }

        udpJob?.cancel()
        udpJob = scope.launch {
            runCatching {
                val socket = DatagramSocket(UDP_PORT)
                udpSocket = socket
                val buffer = ByteArray(256)
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parsePayload(text)?.let(onPacket)
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == TELEMETRY_PATH) {
            val text = String(event.data, Charsets.UTF_8)
            parsePayload(text)?.let(onPacket)
        }
    }

    fun stop() {
        runCatching {
            Wearable.getMessageClient(appContext).removeListener(this)
        }
        runCatching {
            udpSocket?.close()
        }
        udpJob?.cancel()
        scope.cancel()
    }
}
