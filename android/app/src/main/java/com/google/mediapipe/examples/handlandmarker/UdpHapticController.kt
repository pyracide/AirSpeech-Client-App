package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class UdpHapticController(private val onStatus: ((String) -> Unit)? = null) {
    private val port = 8282
    private var socket: DatagramSocket? = null
    
    private var targetIp: String = ""
    
    private var isHandDetected = false
    private var currentZone = 0
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    var isDrawingMode = true
    private var isWriting = false
    var isEnabled = true

    // Haptic Texture Engine Settings (Configurable & Persistent)
    var minPeriod = 60
    var maxPeriod = 500
    var speedLow = 20
    var speedHigh = 470
    var smoothingWindow = 100
    var decay = 0.60f
    var hysteresis = 15
    var watchdogTimeout = 80
    var drawPreset = 1

    // Physics Simulation State
    data class SpeedSample(val time: Long, val speed: Float)
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private val speedHistory = mutableListOf<SpeedSample>()
    private var filteredSpeed = 0f
    private var hapticsActive = false
    private var lastMouseMoveTime = 0L
    private var strokeHasPulsed = false
    private var lastSentPeriod = -1
    private var lastSendTime = 0L
    private val MIN_SEND_INTERVAL = 100L // rate limit to 10 msgs/sec

    private var watchdogJob: Job? = null
    
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

    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("haptics_settings", Context.MODE_PRIVATE)
        minPeriod = prefs.getInt("min_period", 60)
        maxPeriod = prefs.getInt("max_period", 500)
        speedLow = prefs.getInt("speed_low", 20)
        speedHigh = prefs.getInt("speed_high", 470)
        smoothingWindow = prefs.getInt("smoothing_window", 100)
        decay = prefs.getFloat("decay", 0.60f)
        hysteresis = prefs.getInt("hysteresis", 15)
        watchdogTimeout = prefs.getInt("watchdog_timeout", 80)
        drawPreset = prefs.getInt("draw_preset", 1)
        Log.d("UdpHaptic", "Settings loaded: minPeriod=$minPeriod, maxPeriod=$maxPeriod, speedLow=$speedLow, speedHigh=$speedHigh, smoothing=$smoothingWindow, decay=$decay, hysteresis=$hysteresis, watchdog=$watchdogTimeout, preset=$drawPreset")
    }

    fun saveSettings(context: Context) {
        val prefs = context.getSharedPreferences("haptics_settings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("min_period", minPeriod)
            putInt("max_period", maxPeriod)
            putInt("speed_low", speedLow)
            putInt("speed_high", speedHigh)
            putInt("smoothing_window", smoothingWindow)
            putFloat("decay", decay)
            putInt("hysteresis", hysteresis)
            putInt("watchdog_timeout", watchdogTimeout)
            putInt("draw_preset", drawPreset)
            apply()
        }
        Log.d("UdpHaptic", "Settings saved")
    }

    fun resetToDefaults(context: Context) {
        minPeriod = 60
        maxPeriod = 500
        speedLow = 20
        speedHigh = 470
        smoothingWindow = 100
        decay = 0.60f
        hysteresis = 15
        watchdogTimeout = 80
        drawPreset = 1
        saveSettings(context)
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
                // Send HAND_FOUND command to the ESP32
                sendPacket("HAND_FOUND:55")
            } else {
                // Halt haptics and stop timer when hand is lost
                stopWatchdog()
                sendPacket("DRAW_STOP")
                hapticsActive = false
                lastSentPeriod = -1
                currentZone = 0
            }
        }
    }
    
    fun onDrawingStateChanged(writing: Boolean) {
        isWriting = writing
        lastTime = System.currentTimeMillis()
        lastMouseMoveTime = System.currentTimeMillis()
        if (writing) {
            strokeHasPulsed = false
            speedHistory.clear()
            filteredSpeed = 0f
            lastSentPeriod = -1
            startWatchdog()
        } else {
            stopWatchdog()
            if (!strokeHasPulsed) {
                // Tap/dot detected: trigger single responsive ROM click
                sendPacket("TEST_EFFECT:$drawPreset")
            }
            if (hapticsActive) {
                sendPacket("DRAW_STOP")
                hapticsActive = false
            }
            lastSentPeriod = -1
        }
    }

    fun onHandMoved(avgX: Float, avgY: Float) {
        if (!isWriting) {
            // Stationary/moving silent state: only update coords baseline
            lastX = avgX * 900f
            lastY = avgY * 520f
            lastTime = System.currentTimeMillis()
            lastMouseMoveTime = System.currentTimeMillis()
            return
        }

        val now = System.currentTimeMillis()
        lastMouseMoveTime = now

        // Map normalized coordinates [0, 1] to virtual 900x520 viewport
        val x = avgX * 900f
        val y = avgY * 520f

        val dx = x - lastX
        val dy = y - lastY
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val dt = now - lastTime // elapsed milliseconds

        val speed = if (dt > 0) (dist / dt) * 1000f else 0f // units per second

        // Record speed sample
        speedHistory.add(SpeedSample(now, speed))

        // Update previous values
        lastX = x
        lastY = y
        lastTime = now

        // Remove old samples outside temporal smoothing window
        val cutoff = now - smoothingWindow
        speedHistory.removeAll { it.time < cutoff }

        // Calculate smoothed speed using sliding window weighted average
        val smoothedSpeed = if (speedHistory.isEmpty()) {
            0f
        } else {
            var totalWeight = 0f
            var weightedSum = 0f
            for (sample in speedHistory) {
                val age = now - sample.time
                val weight = 1f - (age.toFloat() / smoothingWindow)
                if (weight > 0f) {
                    weightedSum += sample.speed * weight
                    totalWeight += weight
                }
            }
            if (totalWeight > 0f) weightedSum / totalWeight else 0f
        }

        // Fast-attack, slow-decay momentum filter (Peak Envelope Tracker)
        if (smoothedSpeed >= filteredSpeed) {
            filteredSpeed = smoothedSpeed
        } else {
            filteredSpeed = filteredSpeed * decay + smoothedSpeed * (1f - decay)
        }

        // Speed to pulse period mapping
        val period = speedToPeriod(filteredSpeed)
        if (period > 0) {
            val shouldSend = (abs(period - lastSentPeriod) > hysteresis || lastSentPeriod == -1) &&
                             (now - lastSendTime > MIN_SEND_INTERVAL)

            if (shouldSend) {
                sendPacket("DRAW_PULSE:$period:$drawPreset")
                lastSentPeriod = period
                lastSendTime = now
                hapticsActive = true
                strokeHasPulsed = true
            }
        } else {
            // Speed dropped below threshold while drawing (stationary drawing state)
            if (hapticsActive) {
                sendPacket("DRAW_STOP")
                hapticsActive = false
                lastSentPeriod = -1
            }
        }
    }

    private fun speedToPeriod(speed: Float): Int {
        if (speed < speedLow) return -1
        val t = min(1f, (speed - speedLow) / (speedHigh - speedLow).toFloat())
        // Fast speed -> short period (minPeriod), slow speed -> long period (maxPeriod)
        return (maxPeriod - t * (maxPeriod - minPeriod)).roundToInt()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(20)
                if (isWriting) {
                    val elapsed = System.currentTimeMillis() - lastMouseMoveTime
                    if (elapsed > watchdogTimeout) {
                        // Hand stopped completely: cut off haptics immediately
                        filteredSpeed = 0f
                        speedHistory.clear()
                        if (hapticsActive) {
                            sendPacket("DRAW_STOP")
                            hapticsActive = false
                            lastSentPeriod = -1
                        }
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
    
    fun onZoneChanged(zone: Int) {
        currentZone = (zone - 1).coerceIn(0, 3)
    }

    fun sendBitrate(bitrateBps: Int) {
        scope.launch(Dispatchers.IO) {
            sendPacket("BITRATE:$bitrateBps")
        }
    }
    
    private fun sendPacket(message: String) {
        if (!isEnabled) {
            onStatus?.invoke("Muted (Haptics Disabled)")
            return
        }
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
        stopWatchdog()
        scope.cancel()
        socket?.close()
    }
}
