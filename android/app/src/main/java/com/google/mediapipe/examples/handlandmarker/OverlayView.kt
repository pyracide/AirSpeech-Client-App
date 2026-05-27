/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.handlandmarker.myscript.MyScriptService
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: HandLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var drawingPaint = Paint()
    private var drawingDotPaint = Paint()
    private var centerDotPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    // Drawing Mode State
    var isDrawingMode: Boolean = true
        set(value) {
            field = value
            if (!value) {
                clearDrawing()
            }
            invalidate()
        }
    private var isWriting: Boolean = false
    private val drawnPaths = mutableListOf<Path>()
    private var currentPath: Path? = null
    
    // MyScript Integration
    private class ScreenPoint(val x: Float, val y: Float, val t: Long)
    private val currentScreenPoints = mutableListOf<ScreenPoint>()
    private val currentStrokePoints = mutableListOf<MyScriptService.PointData>()
    private var unpinchStartTime: Long = 0L
    var strokeListener: OnStrokeListener? = null
    private var lastClearTime = 0L
    private var tapCount = 0
    private var lastTapTime = 0L
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tapRunnable = Runnable {
        if (tapCount == 2) {
            strokeListener?.onDoublePinch()
        }
        tapCount = 0
    }
    var isTapGesturesEnabled: Boolean = true
    var isDoublePinchUndoEnabled: Boolean = false
    var isFistClenchClearEnabled: Boolean = true
    private var isTapTriggered = false
    private var sendPoseStartTime: Long = 0L
    private var handLostTime = 0L
    var coordinateScale: Float = 1.0f
    var sendMode: Int = 1 // 0 = Third Person, 1 = First Person
    var isMjpegMode: Boolean = false
    var isRtspMode: Boolean = false
    var isCenterCrop: Boolean = false
    var mjpegFingerStraightnessThreshold: Float = 0.78f
    var mjpegTotalStraightnessThreshold: Float = 3.40f
    var isLandmarkInDrawModeEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var isDebugOverlayEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    
    // Tap Gesture Detection State
    interface OnStrokeListener {
        fun onStroke(points: List<MyScriptService.PointData>)
        fun onClear()
        fun onSend()
        fun onDoublePinch()
        fun onTriplePinch()
        fun onDebugCoords(x: Float, y: Float)
        fun onZoneChanged(zone: Int)
        fun onHandPresence(detected: Boolean)
        fun onAbort()
        fun onDrawingStateChanged(isWriting: Boolean)
        fun onPinchDebug(scaleDist: Float, pinchDist: Float, ratio: Float) {}
        fun onFistClenchDebug(isFistClenched: Boolean) {}
    }

    init {
        initPaints()
    }

    fun clear() {
        results = null
        linePaint.reset()
        pointPaint.reset()
        clearDrawing()
        invalidate()
        initPaints()
    }
    
    fun clearDrawing() {
        drawnPaths.clear()
        currentPath = null
        isWriting = false
        currentStrokePoints.clear()
        currentScreenPoints.clear()
        unpinchStartTime = 0L
        handLostTime = 0L
        Log.d("OverlayView", "CLEAR")
    }

    fun clearTracking() {
        results = null
        isTapTriggered = false
        isWriting = false
        currentScreenPoints.clear()
        unpinchStartTime = 0L
        handLostTime = 0L
        invalidate()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL
        
        drawingPaint.color = Color.GREEN
        drawingPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        drawingPaint.style = Paint.Style.STROKE
        drawingPaint.strokeJoin = Paint.Join.ROUND
        drawingPaint.strokeCap = Paint.Cap.ROUND
        
        drawingDotPaint.color = Color.GREEN
        drawingDotPaint.strokeWidth = LANDMARK_STROKE_WIDTH * 2
        drawingDotPaint.style = Paint.Style.FILL
        
        centerDotPaint.color = Color.MAGENTA
        centerDotPaint.style = Paint.Style.FILL
        centerDotPaint.isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        
        if (isDrawingMode) {
            // Draw all saved paths
            for (path in drawnPaths) {
                canvas.drawPath(path, drawingPaint)
            }
            // Draw current path
            currentPath?.let {
                canvas.drawPath(it, drawingPaint)
            }
            
            // Draw dot if writing
            if (isWriting && results?.landmarks()?.isNotEmpty() == true) {
                 val landmark = results!!.landmarks().first()
                 val thumbTip = landmark[4]
                 val indexTip = landmark[8]
                 
                 val avgX = (indexTip.x() + thumbTip.x()) / 2f
                 val avgY = (indexTip.y() + thumbTip.y()) / 2f
                 
                 canvas.drawPoint(
                     avgX * imageWidth * scaleFactor + offsetX,
                     avgY * imageHeight * scaleFactor + offsetY,
                     drawingDotPaint
                 )
            }
        }

        // Draw normal skeleton if draw mode is OFF OR if draw mode is ON and landmark visual is enabled
        if (!isDrawingMode || isLandmarkInDrawModeEnabled) {
            // Normal Skeleton Mode
            results?.let { handLandmarkerResult ->
                for (landmark in handLandmarkerResult.landmarks()) {
                    // Check straight fingers for First Person mode
                    val indexStraight = isFingerStraight(landmark[5], landmark[6], landmark[8])
                    val middleStraight = isFingerStraight(landmark[9], landmark[10], landmark[12])
                    val ringStraight = isFingerStraight(landmark[13], landmark[14], landmark[16])
                    val pinkyStraight = isFingerStraight(landmark[17], landmark[18], landmark[20])
                    
                    for (normalizedLandmark in landmark) {
                        canvas.drawPoint(
                            normalizedLandmark.x() * imageWidth * scaleFactor + offsetX,
                            normalizedLandmark.y() * imageHeight * scaleFactor + offsetY,
                            pointPaint
                        )
                    }

                    HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                        val startIdx = connection!!.start()
                        val endIdx = connection.end()
                        
                        // Determine if this connection belongs to a straight finger
                        val isStraightFinger = when {
                            startIdx in 5..8 && endIdx in 5..8 -> indexStraight
                            startIdx in 9..12 && endIdx in 9..12 -> middleStraight
                            startIdx in 13..16 && endIdx in 13..16 -> ringStraight
                            startIdx in 17..20 && endIdx in 17..20 -> pinkyStraight
                            else -> false
                        }
                        
                        val currentPaint = if (isStraightFinger) {
                            android.graphics.Paint(linePaint).apply { color = android.graphics.Color.GREEN }
                        } else {
                            linePaint
                        }

                        canvas.drawLine(
                            landmark[startIdx].x() * imageWidth * scaleFactor + offsetX,
                            landmark[startIdx].y() * imageHeight * scaleFactor + offsetY,
                            landmark[endIdx].x() * imageWidth * scaleFactor + offsetX,
                            landmark[endIdx].y() * imageHeight * scaleFactor + offsetY,
                            currentPaint
                        )
                    }
                }
            }
        }
        
        // Draw center point
        if (isDebugOverlayEnabled && results?.landmarks()?.isNotEmpty() == true) {
            val landmark = results!!.landmarks().first()
            val j5 = landmark[5]
            val j9 = landmark[9]
            val j13 = landmark[13]
            val centerX = (j5.x() + j9.x() + j13.x()) / 3f
            val centerY = (j5.y() + j9.y() + j13.y()) / 3f
            
            canvas.drawCircle(
                centerX * imageWidth * scaleFactor + offsetX,
                centerY * imageHeight * scaleFactor + offsetY,
                15f,
                centerDotPaint
            )
        }
    }

    fun setResults(
        handLandmarkerResults: HandLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = handLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // Use max to match center-crop behavior (fixes miniature scaled down issue)
                // Use min to match fit-center behavior (standard for phone camera/mjpeg)
                if (isCenterCrop) {
                    max(width * 1f / imageWidth, height * 1f / imageHeight)
                } else {
                    min(width * 1f / imageWidth, height * 1f / imageHeight)
                }
            }
        }
        
        offsetX = (width - imageWidth * scaleFactor) / 2f
        offsetY = (height - imageHeight * scaleFactor) / 2f

        if (handLandmarkerResults.landmarks().isNotEmpty()) {
            handLostTime = 0L
            strokeListener?.onHandPresence(true)
            val firstHand = handLandmarkerResults.landmarks().first()
            
            // Calculate drawing zone
            val j5 = firstHand[5]
            val j9 = firstHand[9]
            val j13 = firstHand[13]
            val centerX = (j5.x() + j9.x() + j13.x()) / 3f
            val centerY = (j5.y() + j9.y() + j13.y()) / 3f
            
            val distToLeft = centerX
            val distToRight = 1f - centerX
            val distToTop = centerY
            val distToBottom = 1f - centerY
            
            // X Proximity Zone thresholds:
            // Left: Zone 4: < 0.12f, Zone 3: < 0.24f, Zone 2: < 0.36f
            // Right (original): Zone 4: < 0.08f, Zone 3: < 0.16f, Zone 2: < 0.24f
            val zoneLeft = when {
                distToLeft < 0.12f -> 4
                distToLeft < 0.24f -> 3
                distToLeft < 0.36f -> 2
                else -> 1
            }
            val zoneRight = when {
                distToRight < 0.08f -> 4
                distToRight < 0.16f -> 3
                distToRight < 0.24f -> 2
                else -> 1
            }
            val zoneX = maxOf(zoneLeft, zoneRight)

            // Y Proximity Zone thresholds:
            // Top (original): Zone 4: < 0.10f, Zone 3: < 0.20f, Zone 2: < 0.30f
            // Bottom: Zone 4: < 0.20f, Zone 3: < 0.30f, Zone 2: < 0.40f
            val zoneTop = when {
                distToTop < 0.10f -> 4
                distToTop < 0.20f -> 3
                distToTop < 0.30f -> 2
                else -> 1
            }
            val zoneBottom = when {
                distToBottom < 0.30f -> 4
                distToBottom < 0.40f -> 3
                distToBottom < 0.45f -> 2
                else -> 1
            }
            val zoneY = maxOf(zoneTop, zoneBottom)
            
            val zone = maxOf(zoneX, zoneY)
            val effectiveZone = if (isDrawingMode && !isWriting) 1 else zone
            strokeListener?.onZoneChanged(effectiveZone)
            
            if (isDrawingMode) {
                processGesture(firstHand)
            } else {
                // Just track midpoint for debug
                val thumbTip = firstHand[4]
                val indexTip = firstHand[8]
                val avgX = (indexTip.x() + thumbTip.x()) / 2f
                val avgY = (indexTip.y() + thumbTip.y()) / 2f
                
                val x = avgX * imageWidth * scaleFactor + offsetX
                val y = avgY * imageHeight * scaleFactor + offsetY
                strokeListener?.onDebugCoords(x, y)
            }
        } else {
            strokeListener?.onHandPresence(false)
            if (isWriting || drawnPaths.isNotEmpty() || currentPath != null) {
                if (handLostTime == 0L) {
                    handLostTime = System.currentTimeMillis()
                }
                if (System.currentTimeMillis() - handLostTime >= 5000) {
                    if (isWriting) {
                        isWriting = false
                        strokeListener?.onDrawingStateChanged(false)
                    } else {
                        isWriting = false
                    }
                    currentPath = null
                    drawnPaths.clear()
                    currentStrokePoints.clear()
                    invalidate()
                    strokeListener?.onAbort()
                    handLostTime = 0L
                }
            } else {
                handLostTime = 0L
            }
        }

        invalidate()
    }
    
    private fun processGesture(landmarks: List<NormalizedLandmark>) {
        val wrist = landmarks[0]
        val thumbTip = landmarks[4]
        val indexMCP = landmarks[5]
        val indexTip = landmarks[8]
        val middleMCP = landmarks[9]
        val middleTip = landmarks[12]
        val ringMCP = landmarks[13]
        val ringTip = landmarks[16]
        val pinkyMCP = landmarks[17]
        val pinkyTip = landmarks[20]
        
        val scaleDist = distance(wrist, indexMCP)
        val pinchDist = distance(thumbTip, indexTip)
        val ratio = if (scaleDist > 0) pinchDist / scaleDist else 100f // prevent div by zero
        
        strokeListener?.onPinchDebug(scaleDist, pinchDist, ratio)
        
        // Calculate fist clench
        val indexMCPDist = distance(indexMCP, wrist)
        val indexTipDist = distance(indexTip, wrist)
        val middleMCPDist = distance(middleMCP, wrist)
        val middleTipDist = distance(middleTip, wrist)
        val ringMCPDist = distance(ringMCP, wrist)
        val ringTipDist = distance(ringTip, wrist)
        val pinkyMCPDist = distance(pinkyMCP, wrist)
        val pinkyTipDist = distance(pinkyTip, wrist)
        
        val indexClenched = indexTipDist < indexMCPDist * 1.15f
        val middleClenched = middleTipDist < middleMCPDist * 1.15f
        val ringClenched = ringTipDist < ringMCPDist * 1.15f
        val pinkyClenched = pinkyTipDist < pinkyMCPDist * 1.15f
        
        val isFistClenched = indexClenched && middleClenched && ringClenched && pinkyClenched
        strokeListener?.onFistClenchDebug(isFistClenched)

        // Cooldown: After a clear gesture, keep canvas cleared for 0.5 seconds and block drawing
        val now = System.currentTimeMillis()
        if (now - lastClearTime < 500) {
            if (isWriting) {
                isWriting = false
                strokeListener?.onDrawingStateChanged(false)
            }
            currentPath = null
            currentStrokePoints.clear()
            currentScreenPoints.clear()
            unpinchStartTime = 0L
            drawnPaths.clear()
            val indexX = indexTip.x() * imageWidth * scaleFactor + offsetX
            val indexY = indexTip.y() * imageHeight * scaleFactor + offsetY
            strokeListener?.onDebugCoords(indexX, indexY)
            return
        }
        
        // If fist clench is active, drawing/writing is impossible.
        if (isFistClenched) {
            if (isWriting) {
                isWriting = false
                strokeListener?.onDrawingStateChanged(false)
            }
            currentPath = null
            currentStrokePoints.clear()
            currentScreenPoints.clear()
            unpinchStartTime = 0L
            
            // Process fist clench clear gesture if enabled
            if (isFistClenchClearEnabled) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClearTime > 1000) {
                    clearDrawing()
                    strokeListener?.onClear()
                    lastClearTime = currentTime
                }
            }
            
            val indexX = indexTip.x() * imageWidth * scaleFactor + offsetX
            val indexY = indexTip.y() * imageHeight * scaleFactor + offsetY
            strokeListener?.onDebugCoords(indexX, indexY)
            return
        }
        
        // --- Pinch Detection (The "Pen") ---
        val START_THRESHOLD = when {
            isRtspMode -> START_THRESHOLD_RTSP
            isMjpegMode -> START_THRESHOLD_MJPEG
            else -> START_THRESHOLD_DEFAULT
        }
        val STOP_THRESHOLD = when {
            isRtspMode -> STOP_THRESHOLD_RTSP
            isMjpegMode -> STOP_THRESHOLD_MJPEG
            else -> STOP_THRESHOLD_DEFAULT
        }
        val STOP_THRESHOLD_TAP = when {
            isRtspMode -> STOP_THRESHOLD_TAP_RTSP
            isMjpegMode -> STOP_THRESHOLD_TAP_MJPEG
            else -> STOP_THRESHOLD_TAP_DEFAULT
        }
        
        // --- Tap Gesture Detection (Double/Triple Pinch) ---
        // Use a separate release threshold so tap sensitivity can be tuned independently of pen-up.
        if (isTapGesturesEnabled) {
            if (ratio < START_THRESHOLD && !isTapTriggered) {
                isTapTriggered = true
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime > 400) {
                    tapCount = 1
                } else {
                    tapCount++
                }
                lastTapTime = currentTime
                
                tapHandler.removeCallbacks(tapRunnable)
                if (tapCount == 3) {
                    if (!isFistClenchClearEnabled) {
                        Log.d("OverlayView", "TRIPLE PINCH")
                        strokeListener?.onTriplePinch()
                    }
                    tapCount = 0
                } else {
                    tapHandler.postDelayed(tapRunnable, 400)
                }
            } else if (ratio > STOP_THRESHOLD_TAP) {
                isTapTriggered = false
            }
        }

        // --- Send Pose Check for Debounce ---
        val isSendPose = if (ratio <= STOP_THRESHOLD) {
            false
        } else if (sendMode == 0) {
            // Third Person (Current) Mode
            val middleExt = distance(middleTip, wrist) > distance(middleMCP, wrist) * 0.85f
            val ringExt = distance(ringTip, wrist) > distance(ringMCP, wrist) * 0.85f
            val pinkyExt = distance(pinkyTip, wrist) > distance(pinkyMCP, wrist) * 0.85f
            val fingersOpen = middleExt && ringExt && pinkyExt
            val thumbIndexApart = ratio > 0.95
            fingersOpen && thumbIndexApart
        } else {
            // First Person Mode: 4 fingers straight + total straightness check
            val indexCos = getFingerCosine(indexMCP, landmarks[6], indexTip)
            val middleCos = getFingerCosine(middleMCP, landmarks[10], middleTip)
            val ringCos = getFingerCosine(ringMCP, landmarks[14], ringTip)
            val pinkyCos = getFingerCosine(pinkyMCP, landmarks[18], pinkyTip)

            val fingerStraightnessThreshold = if (isMjpegMode) mjpegFingerStraightnessThreshold else FINGER_STRAIGHTNESS_THRESHOLD
            val totalStraightnessThreshold = if (isMjpegMode) mjpegTotalStraightnessThreshold else TOTAL_STRAIGHTNESS_THRESHOLD

            val indexStraight = indexCos > fingerStraightnessThreshold
            val middleStraight = middleCos > fingerStraightnessThreshold
            val ringStraight = ringCos > fingerStraightnessThreshold
            val pinkyStraight = pinkyCos > fingerStraightnessThreshold

            val totalStraightness = indexCos + middleCos + ringCos + pinkyCos

            indexStraight && middleStraight && ringStraight && pinkyStraight && totalStraightness > totalStraightnessThreshold
        }

        if (isSendPose) {
            if (sendPoseStartTime == 0L) {
                sendPoseStartTime = System.currentTimeMillis()
            }
        } else {
            sendPoseStartTime = 0L
        }

        val sendPoseAchieved = isSendPose && sendPoseStartTime != 0L && (System.currentTimeMillis() - sendPoseStartTime >= 100)

        // --- Pinch Detection (The "Pen" with Hysteresis) ---
        var justStarted = false
        val nowTime = System.currentTimeMillis()
        if (!isWriting && ratio < START_THRESHOLD) {
            isWriting = true
            strokeListener?.onDrawingStateChanged(true)
            justStarted = true
            Log.d("OverlayView", "DOWN")
            // Start new path
            currentPath = Path()
            val avgX = (indexTip.x() + thumbTip.x()) / 2f
            val avgY = (indexTip.y() + thumbTip.y()) / 2f
            
            val x = avgX * imageWidth * scaleFactor + offsetX
            val y = avgY * imageHeight * scaleFactor + offsetY
            currentPath?.moveTo(x, y)
            currentStrokePoints.clear()
            currentScreenPoints.clear()
            unpinchStartTime = 0L
            
            val scaledX = (avgX * imageWidth * scaleFactor) * coordinateScale + offsetX
            val scaledY = (avgY * imageHeight * scaleFactor) * coordinateScale + offsetY
            currentStrokePoints.add(MyScriptService.PointData(scaledX, scaledY, nowTime))
            currentScreenPoints.add(ScreenPoint(x, y, nowTime))
         } else if (isWriting && ratio > STOP_THRESHOLD && (!isSendPose || sendPoseAchieved)) {
             var shouldStop = true
             if (isMjpegMode) {
                 if (unpinchStartTime == 0L) {
                     unpinchStartTime = nowTime
                 }
                 if (nowTime - unpinchStartTime < MJPEG_UNPINCH_DEBOUNCE_MS) {
                     shouldStop = false
                 } else {
                     // Real unpinch confirmed. Truncate points added during debounce
                     currentStrokePoints.removeAll { it.timestamp >= unpinchStartTime }
                     currentScreenPoints.removeAll { it.t >= unpinchStartTime }
                     
                     // Rebuild currentPath
                     currentPath = Path()
                     if (currentScreenPoints.isNotEmpty()) {
                         currentPath?.moveTo(currentScreenPoints[0].x, currentScreenPoints[0].y)
                         for (i in 1 until currentScreenPoints.size) {
                             currentPath?.lineTo(currentScreenPoints[i].x, currentScreenPoints[i].y)
                         }
                     } else {
                         currentPath = null
                     }
                 }
             }

             if (shouldStop) {
                 isWriting = false
                 strokeListener?.onDrawingStateChanged(false)
                 Log.d("OverlayView", "UP")
                 // Commit path
                 currentPath?.let { drawnPaths.add(it) }
                 currentPath = null
                 
                 // Notify listener
                 if (currentStrokePoints.isNotEmpty()) {
                     strokeListener?.onStroke(ArrayList(currentStrokePoints))
                     currentStrokePoints.clear()
                     currentScreenPoints.clear()
                 }
                 unpinchStartTime = 0L
             }
         }
        
        if (isWriting && !justStarted && !sendPoseAchieved) {
            if (ratio <= STOP_THRESHOLD) {
                unpinchStartTime = 0L
            }
            val avgX = (indexTip.x() + thumbTip.x()) / 2f
            val avgY = (indexTip.y() + thumbTip.y()) / 2f
            
            val x = avgX * imageWidth * scaleFactor + offsetX
            val y = avgY * imageHeight * scaleFactor + offsetY
            currentPath?.lineTo(x, y)
            
            val scaledX = (avgX * imageWidth * scaleFactor) * coordinateScale + offsetX
            val scaledY = (avgY * imageHeight * scaleFactor) * coordinateScale + offsetY
            currentStrokePoints.add(MyScriptService.PointData(scaledX, scaledY, nowTime))
            currentScreenPoints.add(ScreenPoint(x, y, nowTime))
        }
        
        // --- The "Send" Gesture ---
        if (sendPoseAchieved) {
             val currentTime = System.currentTimeMillis()
             // Debounce send to avoid multiple triggers
             if (currentTime - lastClearTime > 1000) {
                  if (isWriting) {
                      isWriting = false
                      strokeListener?.onDrawingStateChanged(false)
                      Log.d("OverlayView", "UP (Send)")
                      currentPath?.let { drawnPaths.add(it) }
                      currentPath = null
                      if (currentStrokePoints.isNotEmpty()) {
                          strokeListener?.onStroke(ArrayList(currentStrokePoints))
                      }
                      currentStrokePoints.clear()
                      currentScreenPoints.clear()
                  }
                  clearDrawing()
                  strokeListener?.onSend()
                  lastClearTime = currentTime
                  sendPoseStartTime = 0L
             }
        }
        
        // Also emit debug coords when drawing mode is ON
        val indexX = indexTip.x() * imageWidth * scaleFactor + offsetX
        val indexY = indexTip.y() * imageHeight * scaleFactor + offsetY
        strokeListener?.onDebugCoords(indexX, indexY)
    }
    
    private fun distance(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
        // We use normalized coordinates directly? 
        // No, the prompt specifies "Euclidean distance". 
        // If we use normalized, X and Y have different scales unless aspect ratio is 1:1.
        // It's safer to use pixel coordinates to represent actual physical distance on screen.
        
        val x1 = p1.x() * imageWidth * scaleFactor
        val y1 = p1.y() * imageHeight * scaleFactor
        val x2 = p2.x() * imageWidth * scaleFactor
        val y2 = p2.y() * imageHeight * scaleFactor
        
        return sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))
    }
    
    // Checks if the 2D vector from knuckle to PIP is within a similarity threshold (cosine) to PIP to Tip
    private fun isFingerStraight(mcp: NormalizedLandmark, pip: NormalizedLandmark, tip: NormalizedLandmark, threshold: Float = 0.77f): Boolean {
        return getFingerCosine(mcp, pip, tip) > threshold
    }

    private fun getFingerCosine(mcp: NormalizedLandmark, pip: NormalizedLandmark, tip: NormalizedLandmark): Float {
        val mcpX = mcp.x() * imageWidth * scaleFactor
        val mcpY = mcp.y() * imageHeight * scaleFactor
        val pipX = pip.x() * imageWidth * scaleFactor
        val pipY = pip.y() * imageHeight * scaleFactor
        val tipX = tip.x() * imageWidth * scaleFactor
        val tipY = tip.y() * imageHeight * scaleFactor
        
        val v1x = pipX - mcpX
        val v1y = pipY - mcpY
        val v2x = tipX - pipX
        val v2y = tipY - pipY
        
        val dotProduct = v1x * v2x + v1y * v2y
        val mag1 = sqrt((v1x * v1x + v1y * v1y).toDouble()).toFloat()
        val mag2 = sqrt((v2x * v2x + v2y * v2y).toDouble()).toFloat()
        
        if (mag1 == 0f || mag2 == 0f) return 0f
        return dotProduct / (mag1 * mag2)
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F

        // Default Thresholds (Local camera)
        const val START_THRESHOLD_DEFAULT = 0.20
        const val STOP_THRESHOLD_DEFAULT = 0.30
        const val STOP_THRESHOLD_TAP_DEFAULT = 0.26

        // MJPEG Thresholds (Smart Glasses Classic mode)
        const val START_THRESHOLD_MJPEG = 0.20
        const val STOP_THRESHOLD_MJPEG = 0.35
        const val STOP_THRESHOLD_TAP_MJPEG = 0.26

        // RTSP Thresholds (Lowered for responsiveness on RTSP stream)
        const val START_THRESHOLD_RTSP = 0.15
        const val STOP_THRESHOLD_RTSP = 0.25
        const val STOP_THRESHOLD_TAP_RTSP = 0.20

        // Send Gesture Finger Straightness Thresholds
        const val FINGER_STRAIGHTNESS_THRESHOLD = 0.80f
        const val TOTAL_STRAIGHTNESS_THRESHOLD = 3.60f

        // MJPEG Debounce Settings (in milliseconds)
        const val MJPEG_UNPINCH_DEBOUNCE_MS = 100L
    }
}
