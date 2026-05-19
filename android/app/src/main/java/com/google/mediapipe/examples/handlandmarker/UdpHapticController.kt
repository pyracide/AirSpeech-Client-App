package com.google.mediapipe.examples.handlandmarker

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpHapticController(private val onStatus: ((String) -> Unit)? = null) {
    private val port = 8282
    private var socket: DatagramSocket? = null
    
    private var targetIp: String = ""
    
    private var isHandDetected = false
    private var currentZone = 0
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var proximJob: Job? = null
    
    var isDrawingMode = false
    private var isWriting = false
    private var lastDrawingTime = 0L
    
    init {
        scope.launch {
            try {
                socket = DatagramSocket()
                Log.d("UdpHaptic", "UDP DatagramSocket successfully initialized on background thread.")
                onStatus?.invoke("Socket Initialized")
            } catch (e: Exception) {
                Log.e("UdpHaptic", "Failed to create socket", e)
                onStatus?.invoke("Init Error: ${e.localizedMessage}")
            }
        }
    }
    
    fun updateTargetIp(url: String) {
        val ip = url.substringAfter("//").substringBefore(":")
        if (ip.isNotBlank() && ip != targetIp) {
            targetIp = ip
            Log.d("UdpHaptic", "Target IP updated to $targetIp")
            onStatus?.invoke("Target: $ip")
        }
    }
    
    fun onHandPresenceChanged(detected: Boolean) {
        if (isHandDetected != detected) {
            isHandDetected = detected
            
            if (detected) {
                sendBurst("HAND_DETECTED")
                startProximLoop()
            } else {
                stopProximLoop()
                sendBurst("HAND_LOST")
                currentZone = 0
            }
        }
    }
    
    fun onDrawingStateChanged(writing: Boolean) {
        isWriting = writing
        lastDrawingTime = System.currentTimeMillis()
    }
    
    fun onZoneChanged(zone: Int) {
        // Only map if zone is valid (1-4).
        // PROXIM:0 = Safe zone (zone 1)
        // PROXIM:1 = zone 2
        // PROXIM:2 = zone 3
        // PROXIM:3 = zone 4
        currentZone = (zone - 1).coerceIn(0, 3)
    }
    
    private fun sendBurst(message: String) {
        scope.launch {
            repeat(3) {
                sendPacket(message)
                delay(20)
            }
        }
    }
    
    private fun startProximLoop() {
        proximJob?.cancel()
        proximJob = scope.launch {
            while (isActive && isHandDetected) {
                val zoneToSend = if (isDrawingMode) {
                    val currentTime = System.currentTimeMillis()
                    val isWithinGracePeriod = (currentTime - lastDrawingTime) <= 2000L
                    if (isWriting || isWithinGracePeriod) {
                        currentZone
                    } else {
                        0
                    }
                } else {
                    currentZone
                }
                
                sendPacket("PROXIM:$zoneToSend")
                delay(100) // 10Hz
            }
        }
    }
    
    private fun stopProximLoop() {
        proximJob?.cancel()
        proximJob = null
    }
    
    private fun sendPacket(message: String) {
        if (targetIp.isEmpty() || socket == null) {
            val reason = "targetIp: '$targetIp', socketInitialized: ${socket != null}"
            Log.w("UdpHaptic", "Skipping packet send. $reason")
            onStatus?.invoke("Skipped send: $reason")
            return
        }
        try {
            val address = InetAddress.getByName(targetIp)
            val bytes = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, address, port)
            socket?.send(packet)
            Log.d("UdpHaptic", "Sent UDP message '$message' to $targetIp:$port")
            onStatus?.invoke("Sent $message -> $targetIp")
        } catch (e: Exception) {
            Log.e("UdpHaptic", "Failed to send packet: $message to $targetIp", e)
            onStatus?.invoke("Send Error: ${e.localizedMessage}")
        }
    }
    
    fun close() {
        scope.cancel()
        socket?.close()
    }
}
