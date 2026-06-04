AirSpeech Android Client

A native Android application engineered in Kotlin to process continuous, egocentric whole-word air-writing. The app acts as an edge-computing processing client, ingesting low-latency real-time video feeds from external wearable vision devices via WebSocket (MJPEG) or RTSP (H.264) streams.Architecture OverviewThe core processing pipeline decouples spatial gesture inference from handwriting analysis to ensure real-time execution on resource-constrained hardware:

Spatial Hand Tracking: Utilises Google MediaPipe Hand Landmarker to infer 21 3D landmarks frame-by-frame from incoming wearable camera matrices.Headless Digital Ink Ingestion: Bypasses UI-bound canvas loops by programmatically passing temporal coordinate arrays (x, y, t) directly into the background MyScript SDK runtime environment (OffscreenEditor).Data Parsing: Extracts recognized text via structured JSON Interactive Ink Exchange (JIIX) payloads and pushes the top candidate to the Android Text-to-Speech (TTS) engine.

Core Features: 
Triggers active drawing via a thumb-to-index finger pinch. Employs hysteresis thresholds and a 100ms temporal debounce buffer to mitigate landmark tracking jitter.
Egocentric Open-Palm Send: Computes 2D vector cosine similarities across 4 fingers to confirm extension to dispatch digital ink to the recognition engine. 
Closed-Fist Clear: Activates canvas wipe sequences if the absolute fingertip-to-wrist distance drops below $1.15 \times$ knuckle-to-wrist distance simultaneously across all fingers. Includes a 5-second idle auto-clear failsafe.
Telemetry & Boundary Haptics: Pushes real-time UDP telemetry bursts (HAND_DETECTED, HAND_LOST) and proportional boundary proximity warning packets (PROXIM:1–3) to drive localized wearable haptic feedback arrays.
Lexicon Constraint: Overrides default dictionaries with a targeted 7,000-word lexicon derived from the SUBTLEX-UK corpus and proper nouns to minimize ambiguous matching.

Technical Specifications:
Target SDK: Android 14+ (API Level 34+)Language: Kotlin 
Asynchronous Network Stack: Ktor Framework (WebSocket Client)Streaming Ingestion Pipelines: * 
AirSpeech Mini Mode: MJPEG over TCP WebSockets (average runtime performance: 26.2 FPS @ 320x240)
AirSpeech Pro Mode: RTSP video rendering pipeline with Android PixelCopy frame ingestion (average runtime performance: 28.3 FPS @ 1280x720 H.264)Core 
End-to-End Latency: <400ms local processing turnaround (average MyScript local inference: 326ms)

Project Dependencies:
com.google.mediapipe:tasks-vision
myscript-iink-android-sdk (will require a license from MyScript)
io.ktor:ktor-client-core
io.ktor:ktor-client-websockets

