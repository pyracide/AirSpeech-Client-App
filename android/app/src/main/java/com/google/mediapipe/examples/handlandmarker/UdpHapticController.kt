package com.google.mediapipe.examples.handlandmarker

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpHapticController {
    private val port = 8282
    private var socket: DatagramSocket? = null
    
    private var targetIp: String = ""
    
    private var isHandDetected = false
    private var currentZone = 0
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var proximJob: Job? = null
    
    init {
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            Log.e("UdpHaptic", "Failed to create socket", e)
        }
    }
    
    fun updateTargetIp(url: String) {
        val ip = url.substringAfter("//").substringBefore(":")
        if (ip.isNotBlank() && ip != targetIp) {
            targetIp = ip
            Log.d("UdpHaptic", "Target IP updated to $targetIp")
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
                sendPacket("PROXIM:$currentZone")
                delay(100) // 10Hz
            }
        }
    }
    
    private fun stopProximLoop() {
        proximJob?.cancel()
        proximJob = null
    }
    
    private fun sendPacket(message: String) {
        if (targetIp.isEmpty() || socket == null) return
        try {
            val address = InetAddress.getByName(targetIp)
            val bytes = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, address, port)
            socket?.send(packet)
        } catch (e: Exception) {
            Log.e("UdpHaptic", "Failed to send packet: $message", e)
        }
    }
    
    fun close() {
        scope.cancel()
        socket?.close()
    }
}
