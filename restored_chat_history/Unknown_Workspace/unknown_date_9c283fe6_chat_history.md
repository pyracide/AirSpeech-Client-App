# Chat History Restoration
- **Workspace**: Unknown_Workspace
- **Session ID**: `9c283fe6-158e-4103-94ac-ad2a622ef3ec`
- **Date/Time**: Unknown

---

## No direct chat logs found (overview.txt / transcript.jsonl).
Restoring available workspace plans and summaries below.

## Workspace Planning & Artifacts

### implementation_plan.md
```markdown
# Implementation Plan - Meta Vision Streamer

## Goal Description
Create a low-latency, high-performance Android application to stream live video from Meta Ray-Ban Smart Glasses (Gen 1/Gen 2) using the Meta Wearables Device Access Toolkit (DAT). Use `AutoDeviceSelector` for seamless connection and raw `StreamSession` access for minimal latency.

## User Review Required
> [!IMPORTANT]
> **GitHub Token Required**: The SDK is hosted on a private registry. You must provide a generic GitHub PAT with `read:packages` scope to configure `local.properties`.

> [!WARNING]
> **Hinge State Handling**: The stream will automatically stop if the glasses are folded (`StreamError.HINGE_CLOSED`). The app UI must handle this state gracefully (e.g., showing a "Glasses Folded" placeholder).

## Proposed Changes

### Core Architecture
#### [NEW] `com.meta.wearable.dat.core`
- **`Wearables.initialize(Context)`**: Call this in `Application.onCreate()` or `MainActivity.onCreate()`. Must handle `DatResult` success/failure.
- **`Wearables.startRegistration(Activity)`**: Triggered via a "Connect" button. Launches the Meta AI app flow.

### Streaming Pipeline
#### [NEW] `com.meta.wearable.dat.camera`
- **`StreamSession` Management**:
    - Use `Wearables.startStreamSession` with `AutoDeviceSelector`.
    - Monitor `state` flow (`STARTING` -> `STREAMING` -> `STOPPED`).
- **`StreamConfiguration`**:
    - Set `VideoQuality.MEDIUM` (504x896) for balanced performance/quality.
    - Set `frameRate = 24` (Default).
- **Video Rendering**:
    - `streamSession.videoStream` returns `Flow<VideoFrame>`.
    - **Performance Critical**: Frames contain a `ByteBuffer` (likely YUV). We need to efficiently render this to a `SurfaceView` or `TextureView`.
    - **Optimization**: Avoid heavy processing on the collection thread. Use hardware scaling if possible.

### Permissions & Manifest
#### [MODIFY] `AndroidManifest.xml`
- Add `BLUETOOTH`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (if needed for discovery), `INTERNET`, `ACCESS_NETWORK_STATE`.
- Add `<meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="0" />`.
- Add `<intent-filter>` for the Meta AI app callback scheme.

### UI/UX
#### [NEW] `MainActivity.kt` / `composable`
- **Connection Status**: Display "Searching...", "Connected", "Streaming", "Disconnected".
- **Video Surface**: Full-screen `SurfaceView` for the video feed.
- **Controls**: "Connect" button (visible when not registered), "Start Stream" / "Stop Stream" toggles.

## Verification Plan

### Automated Tests
- We cannot mock the physical glasses easily without the Mock Device Kit (which requires extra setup), so verification will be primarily manual.

### Manual Verification
1.  **Auth Flow**:
    - Click "Connect".
    - Verify Meta AI app opens.
    - Verify app returns to "Connected" state.
2.  **Streaming**:
    - Click "Start Stream".
    - Verify video feed appears with < 200ms latency.
    - Verify `VideoQuality.MEDIUM` resolution.
### 4. Stability
    - Fold glasses -> Verify app shows "Paused/Folded".
    - Unfold -> Verify stream can resume.

## Phase 2: WebSocket Broadcaster (New Feature)

### Goal Description
Broadcast the live camera feed from the Android app to other local devices via WebSockets. The Android app will act as a WebSocket server, accepting connections from local clients (e.g., a web browser on a PC) and pushing JPEG-encoded frames in real-time.

### Proposed Architecture

#### 1. WebSocket Server (Ktor or Java-WebSocket)
- **Library Choice**: I propose using **Ktor Server (CIO engine)** or **Java-WebSocket**. Ktor is modern, Kotlin-native, and Coroutine-friendly, making it ideal for this project.
- **Port**: Host the server on a port like `8080`.
- **Endpoint**: `/stream` for WebSocket connections.
- **Lifecycle**: The server state will be managed by `StreamViewModel`. It starts when broadcasting is enabled and stops when disabled or the stream ends.

#### 2. Frame Encoding & Transmission
- **Current State**: Frames arrive as raw `I420` ByteBuffer.
- **Pipeline**:
    1.  Convert `I420` to `NV21` (already implemented in example).
    2.  Compress `NV21` -> `JPEG` byte array (already implemented for UI preview).
    3.  **Broadcast**: Send the JPEG byte array over the active WebSocket connections as binary messages.
- **Optimization**: We may need to separate the JPEG compression thread from the UI rendering thread to avoid dropping frames or slowing down the UI, depending on device performance. We can also lower the JPEG quality for the broadcast stream (e.g., 50% instead of 100%) to save bandwidth.

#### 3. UI Updates (`StreamScreen.kt` / `StreamViewModel.kt`)
- **[MODIFY] `StreamUiState.kt`**: Add `isBroadcasting: Boolean`.
- **[MODIFY] `StreamScreen.kt`**: Add a toggle button (e.g., near the photo capture button) with states "Start Broadcast" / "Stop Broadcast".
- **[NEW] Status Display**: Show the local IP address (e.g., `ws://192.168.1.X:8080/stream`) on the screen while broadcasting so the user knows where to connect.

### Questions / Clarifications Needed

> [!IMPORTANT]
> Please review and clarify the following points before I begin implementation:
1.  **Library Preference**: Are you comfortable with me adding **Ktor Server** dependencies to the project for the WebSocket host, or do you prefer a lighter, pure-Java alternative like `Java-WebSocket`? (I strongly recommend Ktor for Kotlin flow integration).
2.  **Client Application**: How do you plan to consume this stream? E.g., a simple HTML page with an `<img>` tag that updates on message receipt? Or a more complex application? Knowing this helps me format the binary message correctly.
3.  **Performance Trade-offs**: Emitting 24 JPEGs per second over WiFi can be intensive for the phone. Should we downscale the broadcast resolution explicitly, or stick with the native `VideoQuality.MEDIUM` (504x896)?

## Phase 3: Background Streaming (Foreground Service)

### Goal Description
Keep the video stream and WebSocket broadcast alive even when the `CameraAccess` app is minimized, sent to the background, or while the user is interacting with another app (like an ML processing app). 

### Feasibility Analysis
The user asked: *"if the app hasn't been designed to run concurrently or in the background is option 1 even possible?"*
**Yes, absolutely.** The current app wasn't designed for it because all the streaming logic lives inside a `ViewModel` attached to the UI lifecycle `Activity`. When the app goes to the background, Android aggressively pauses/kills UI components. By introducing a **Foreground Service**, we are fundamentally re-architecting the app to explicitly tell the OS: *"This process is now doing crucial background work, keep it alive."* 

### Proposed Architecture

#### 1. Manifest Updates
- Add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` (or `CAMERA`/`DATA_SYNC`) permissions, which are strictly required in modern Android (14+) for background execution.
- Declare a new `<service>` component in `AndroidManifest.xml`.

#### 2. Create `BroadcastService.kt`
- Create a class extending `android.app.Service`.
- **Lifecycle**: Started via `startForegroundService()` from the UI when the user clicks "Start Broadcast".
- **Notification**: The service MUST display a persistent Android Notification (e.g., "Meta Vision Streamer is broadcasting...") so the user knows it's running. This is non-negotiable for Foreground Services.
- **Responsibilities**: 
    - Move `Wearables.startStreamSession()` into this service.
    - Move `LocalWebsocketServer.start()` and frame emission logic into this service.
    - Hold its own `CoroutineScope` independent of the UI.

#### 3. Refactor `StreamViewModel` & UI Coordination
- The `StreamViewModel` will no longer directly hold the `StreamSession`.
- Instead, the UI toggles will send an `Intent` to start or stop the `BroadcastService`.
- **State Sync**: To update the UI when the user re-opens the app (e.g., showing the preview image), the Service can expose a `StateFlow` via a Singleton object or we can use a Bound Service connection to pass the latest `Bitmap` back to the ViewModel when the UI is visible.

```

---


### task.md
```markdown
# Task: Meta Vision Streamer (Android)

## 1. Skill Up & Planning
- [x] Review project brief and requirements
- [x] Understand SDK architecture (Authentication, Streaming, Permissions) <!-- id: 0 -->
    - [x] **Authentication**: `Wearables.startRegistration`, Meta AI app handshake (Confirmed)
    - [x] **Streaming**: `StreamSession`, `StreamConfiguration`, `VideoFrame` (ByteBuffer based)
    - [x] **Permissions**: Bluetooth, Wi-Fi, Location (Confirmed: BLUETOOTH, BLUETOOTH_CONNECT, INTERNET)
    - [x] **GitHub Auth**: Private registry configuration (Requires `read:packages` token)
- [x] Create Implementation Plan <!-- id: 14 -->
- [x] Obtain GitHub Personal Access Token from User <!-- id: 1 -->

## 2. Environment Setup
- [x] Configure `local.properties` with GITHUB_TOKEN placeholder <!-- id: 2 -->
- [x] Configure `settings.gradle` for private specific repo (`maven.pkg.github.com/facebook/meta-wearables-dat-android`) <!-- id: 3 -->
    - [x] **Fix**: Explicitly load `local.properties` in `settings.gradle` to resolve auth issues.
- [x] Configure `build.gradle` dependencies (`mwdat-core:0.4.0`, `mwdat-camera:0.4.0`) <!-- id: 4 -->
- [x] Location switch & Gradle Sync Fixes <!-- id: 5 -->
    - [x] Locate new project path
    - [x] Fix `gradle.properties` (AndroidX warning)
...

## 3. "Hello World" Connection
- [x] Create minimal UI for connection status <!-- id: 6 -->
- [x] Implement `Wearables.initialize` and `startRegistration` <!-- id: 7 -->

## 4. Video Stream Implementation
- [x] Configure `StreamConfiguration` (`VideoQuality.MEDIUM`, `frameRate=24`) <!-- id: 10 -->
- [x] Initialize `StreamSession` and handle lifecycle (start/stop/close) <!-- id: 11 -->

## 5. WebSocket Broadcaster (Phase 2)
- [x] Add Ktor Server dependencies to `libs.versions.toml` and `build.gradle.kts`.
- [x] Add `ACCESS_NETWORK_STATE` to `AndroidManifest.xml`.
- [x] Create `LocalWebsocketServer.kt` to handle Ktor CIO engine lifecycle and routing.
- [x] Add `isBroadcasting` toggle to `StreamUiState.kt` and `StreamViewModel.kt`.
- [x] Inject JPEG byte array emission into the video frame rendering pipeline.
- [x] Update `StreamScreen.kt` UI with a Toggle Button and Local IP awareness.
- [x] Tested and confirmed broadcasting works on active screen.

## 6. Background Service Broadcaster (Phase 3)
- [x] Add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` permissions to Manifest.
- [x] Create `BroadcastService.kt` to run the Ktor Server and StreamSession.
- [x] Implement persistent Notification for the Foreground Service.
- [x] Refactor `StreamViewModel.kt` to communicate with the Service via Intents instead of managing the stream directly.
- [x] User testing: Verify streaming continues while the app is backgrounded.

```

---


### handoff_notes.md
```markdown
# Handoff: Meta Vision Streamer Relocation

## 1. Project Goal
Build a low-latency (<200ms) Android app to stream live video from Meta Ray-Ban Smart Glasses (Gen 1/2) using the Meta Wearables Device Access Toolkit (DAT v0.4).

## 2. Technical Stack
- **Language**: Kotlin
- **Min SDK**: 30 (Android 11) | **Target SDK**: 34 (Android 14)
- **Dependencies**: `com.meta.wearable:mwdat-core:0.4.0`, `com.meta.wearable:mwdat-camera:0.4.0`
- **Repo**: `https://maven.pkg.github.com/facebook/meta-wearables-dat-android` (Requires `read:packages` PAT)

## 3. Current State (Files Created)
- **`build.gradle` (Project Level)**: Configured with `maven.pkg.github.com`.
- **`app/build.gradle`**: Dependencies added.
- **`local.properties`**: Needs `GITHUB_TOKEN=PLACEHOLDER` replaced with real token.
- **`AndroidManifest.xml`**: Added `BLUETOOTH`, `BLUETOOTH_CONNECT`, `INTERNET`, `Application ID 0`, and Intent Filter for Meta AI app callback.
- **`MainActivity.kt`**: Contains UI logic for "Connect" button. SDK calls (`Wearables.initialize`, `startRegistration`) are **commented out** to prevent build errors before Gradle sync.
- **`activity_main.xml`**: `SurfaceView` + Connection Status UI.
- **`themes.xml`, `colors.xml`, `strings.xml`**: Basic resources added.

## 4. Key Implementation Details (From API Research)
- **Initialization**: `Wearables.initialize(Context)`.
- **Auth Flow**: `Wearables.startRegistration(Activity)` -> Meta AI App -> Callback. Monitor `registrationState`.
- **Connection**: `AutoDeviceSelector` automatically picks the best glasses.
- **Streaming**:
    - `Wearables.startStreamSession(context, AutoDeviceSelector(), StreamConfiguration(MEDIUM, 24))`
    - `streamSession.videoStream` returns `Flow<VideoFrame>`.
    - `VideoFrame` contains raw `ByteBuffer`. Must render efficiently to Surface.
- **Risk**: `StreamSession` stops with `HINGE_CLOSED` if glasses are folded.

## 5. Immediate Next Steps (After Move)
1.  **Move Folder**: Move `MetaDisplayStreamer` out of OneDrive (e.g., to `C:\Projects\`).
2.  **Add Token**: Open `local.properties` and replace `PLACEHOLDER_TOKEN_HERE` with your GitHub PAT.
3.  **Sync Gradle**: Open the project in Android Studio (new location) and sync.
4.  **Uncomment Code**: In `MainActivity.kt`, uncomment the `initializeSdk` and `startRegistration` blocks.
5.  **Run**: Launch app on physical Android device connected to Wi-Fi.

```

---
