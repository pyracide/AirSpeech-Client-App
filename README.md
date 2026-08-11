# AirSpeech Android Client
*By Sam Gray*

An Android application written in Kotlin to process continuous, egocentric (first-person) whole-word air-writing. The app acts as an edge-computing processing client, ingesting low-latency real-time video feeds from external wearable devices via WebSocket (MJPEG) or RTSP (H.264) streams.

🔗 **Project Details:** [samuelgray.myportfolio.com/airspeech](https://samuelgray.myportfolio.com/airspeech)

---

## Overview

* **Spatial Hand Tracking:** Google MediaPipe Hand Landmarker infers 21 3D landmarks frame-by-frame from incoming wearable camera matrices.
* **Headless Digital Ink Ingestion:** Bypasses UI-bound canvas loops by passing temporal coordinate arrays $(x, y, t)$ directly into a background MyScript SDK runtime environment (using MyScript `OffscreenEditor`).
* **Data Parsing:** Extracts recognized text via structured JSON Interactive Ink Exchange (JIIX) payloads and pushes the top candidate to the Android Text-to-Speech (TTS) engine.

---

## Core Features

* **Pinch-to-Draw:** Triggers active drawing via a thumb-to-index finger pinch, made reliable with hysteresis thresholds and a 100ms debounce buffer to mitigate landmark tracking jitter.
* **Open-Palm Send:** Drawing is committed with an Open-Palm Send, by computing 2D vector cosine similarities across 4 fingers.
* **Closed-Fist Clear:** Triggers a Clear by activating a canvas wipe sequence if the absolute fingertip-to-wrist distance drops below $1.15 \times$ knuckle-to-wrist distance simultaneously across all fingers. Includes a 5-second idle auto-clear failsafe.
* **Telemetry & Boundary Haptics:** Pushes real-time UDP telemetry bursts (`HAND_DETECTED`, `HAND_LOST`) and proportional boundary proximity warning packets (`PROXIM:1–3`) to drive haptic feedback on the custom wearable; this keeps the user informed.
* **Lexicon Constraint:** Overrides MyScript default dictionaries with a targeted 7,000-word lexicon (98% of spoken English) derived from the SUBTLEX-UK corpus.

---

## Technical Specifications

* **Language:** Kotlin
* **Target SDK:** Android 14+ (API Level 34+)
* **Asynchronous Network Stack:** Ktor Framework (WebSocket Client)
* **Streaming Ingestion Pipelines:**
  * **AirSpeech Mini Mode:** MJPEG over TCP WebSockets (average runtime performance: 26.2 FPS @ 320x240)
  * **AirSpeech Pro Mode:** RTSP video rendering pipeline with Android PixelCopy frame ingestion (average runtime performance: 28.3 FPS @ 1280x720 H.264)
* **Core End-to-End Latency:** <400ms local processing turnaround (average MyScript local inference: 326ms)

---

## Project Dependencies

* `com.google.mediapipe:tasks-vision`
* `myscript-iink-android-sdk` *(requires a license from MyScript)*
* `io.ktor:ktor-client-core`
* `io.ktor:ktor-client-websockets`

