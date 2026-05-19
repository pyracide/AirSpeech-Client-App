# Chat History Restoration
- **Workspace**: WearableV1
- **Session ID**: `f7ccc60d-357b-4b0d-95ab-0caf21073f3f`
- **Date/Time**: Unknown

---

## No direct chat logs found (overview.txt / transcript.jsonl).
Restoring available workspace plans and summaries below.

## Workspace Planning & Artifacts

### implementation_plan.md
```markdown
# Air-Writing Wearable: Camera & Streaming Test Plan

## Goal

Systematically test all three camera modules (OV2640, OV3660, OV5640) on the Seeed XIAO ESP32S3 Sense, evaluate communication protocols, and determine the optimal configuration for feeding a real-time video stream to a MediaPipe hand-tracking pipeline on a Windows PC — targeting **sub-350ms end-to-end latency**.

---

## Understanding the Constraints

Before diving into the plan, here is the technical context you asked about regarding framerate, resolution, and how to maximise both.

### Why Are Framerate and Resolution Limited?

There are **four bottlenecks** in the pipeline, each of which can independently cap your performance:

#### 1. Camera Sensor → ESP32S3 (Internal Bus)
The camera connects via an 8-bit DVP (Digital Video Port) interface clocked by XCLK. The sensor captures a frame and pushes pixel data row-by-row into the ESP32S3's DMA buffers.

- **Higher resolution** = more pixels per frame = longer transfer time per frame
- **XCLK frequency** controls how fast the sensor operates (8–24 MHz). Higher = faster capture, but too high can cause instability
- **JPEG compression** (done in hardware by OV2640/OV3660/OV5640) dramatically reduces the data that needs to traverse the bus vs. raw RGB

#### 2. ESP32S3 Memory (PSRAM)
The board has **8MB PSRAM** and **512KB internal SRAM**. Frame buffers are stored in PSRAM.

- A single VGA JPEG frame is ~20–40KB. A UXGA JPEG frame is ~100–200KB
- You need **2+ frame buffers** for pipelining (capture next while transmitting current)
- At 8MB PSRAM, memory is generally not the bottleneck for JPEG, but it is for raw formats

#### 3. ESP32S3 CPU Processing
The ESP32S3 is a dual-core 240MHz Xtensa processor. It must:
- Manage DMA transfers from the camera
- Run the WiFi stack (which is CPU-intensive)
- Serialize and transmit frame data over TCP/HTTP/WebSocket

If you do any on-device processing, you eat into the CPU budget for transmission.

#### 4. WiFi Transmission (Usually the Biggest Bottleneck)
The ESP32S3's WiFi is **2.4GHz 802.11 b/g/n** with a practical throughput of **~2–8 Mbps** depending on signal quality, interference, and protocol overhead.

- A 30fps VGA MJPEG stream at quality 12 ≈ 30KB × 30 = ~900KB/s ≈ ~7.2 Mbps — **right at the limit**
- A 15fps VGA stream ≈ ~3.6 Mbps — comfortable
- QVGA at 25fps ≈ ~2 Mbps — very comfortable

### How to Maximise Both Framerate and Resolution

| Lever | What It Does | Trade-off |
|---|---|---|
| **Lower JPEG quality** (higher number, e.g. 20–30) | Smaller frames → faster transmission | Worse image quality, potential MediaPipe accuracy loss |
| **Increase XCLK** (20 MHz) | Faster sensor capture | Can cause instability on some modules |
| **Use 2 frame buffers** | Pipeline capture + transmit | Uses more PSRAM (negligible for JPEG) |
| **Reduce resolution** | Fewer pixels to capture, compress, and transmit | Less detail for MediaPipe (but it resizes to 192×192 internally anyway) |
| **Dedicated WiFi channel** | Less interference → more throughput | Requires router configuration |
| **TCP_NODELAY** | Disables Nagle's algorithm, sends packets immediately | Slightly less efficient WiFi usage |
| **Minimise on-device code** | More CPU cycles for WiFi stack | Less flexibility on the ESP32 |

### Sweet Spot for MediaPipe Hand Tracking

MediaPipe internally resizes input to **192×192** for its hand landmark model. This means:

- **VGA (640×480)** is the practical sweet spot — enough resolution for reliable palm detection at arm's length, not so much that it wastes bandwidth
- **QVGA (320×240)** is viable if the hand fills a significant portion of the frame
- **15–25 fps** is sufficient for gesture tracking; MediaPipe uses temporal filtering so you don't need 60fps
- **Sub-350ms latency** is achievable with MJPEG at VGA/QVGA — typical end-to-end latency is 100–250ms on a local network

---

## Communication Protocol Analysis

### Option A: MJPEG over HTTP (Multipart Stream)

**How it works:** The ESP32 runs an HTTP server. A special endpoint sends a continuous multipart response where each part is a JPEG frame. The browser (or OpenCV) reads frames as they arrive.

| Aspect | Assessment |
|---|---|
| Latency | **~80–200ms** (excellent) |
| Implementation | **Very simple** — ~100 lines of Arduino code |
| PC client | **Trivial** — `cv2.VideoCapture("http://...")` in Python, or any browser |
| Bidirectional control | ❌ Requires separate HTTP endpoints |
| Future mobile app | ✅ Works in Android WebView or with OkHttp |
| Reliability | **Excellent** — most battle-tested approach for ESP32 |

### Option B: WebSocket (Binary Frames)

**How it works:** A persistent bidirectional TCP connection. The ESP32 sends JPEG frames as binary WebSocket messages. The PC client decodes and displays them.

| Aspect | Assessment |
|---|---|
| Latency | **~100–250ms** (good, slightly higher framing overhead than raw HTTP) |
| Implementation | **Moderate** — needs a WebSocket library (ESPAsyncWebServer or ArduinoWebSockets) |
| PC client | **Moderate** — Python `websockets` library + OpenCV |
| Bidirectional control | ✅ **Excellent** — send commands back on the same connection |
| Future mobile app | ✅ Good — standard WebSocket clients available everywhere |
| Reliability | Good, but more complex error handling needed |

### Option C: RTSP (Real-Time Streaming Protocol)

| Aspect | Assessment |
|---|---|
| Latency | **~200–500ms** (buffer-dependent, often higher than MJPEG) |
| Implementation | **Complex** — needs Micro-RTSP library, RTP packetization |
| PC client | VLC, ffplay, or custom GStreamer pipeline |
| Bidirectional control | ❌ Separate channel needed |
| Future mobile app | Moderate — requires RTSP client library |
| Reliability | More complex to debug; not well-suited to ESP32's limited resources |

### WiFi Connectivity Options

> [!IMPORTANT]
> **WiFi Direct is not supported** on the ESP32S3 in Arduino/ESP-IDF. This is a software limitation — the protocol stack has not been implemented by Espressif. We must use alternatives.

| Mode | How It Works | Pros | Cons |
|---|---|---|---|
| **STA (Station)** | ESP32 joins your existing WiFi router | Both PC and ESP32 have internet; simplest setup | Requires a router; adds router hop latency (~5–20ms) |
| **SoftAP** | ESP32 creates its own WiFi hotspot, PC connects to it | No router needed; lowest latency (direct) | PC loses internet while connected; can be annoying |
| **AP+STA** | ESP32 hosts AP *and* connects to router simultaneously | ESP32 accessible from both networks | Complex; PC still loses internet if connected to ESP32's AP; performance split between two WiFi roles |

### Recommendation

> [!TIP]
> **Phase 1 (PC testing): Use STA mode** — connect the ESP32 to your home/office WiFi. Your PC stays on the same network with internet. Latency through a local router is negligible (~5ms added).
>
> **Phase 2 (Field/mobile): Use SoftAP mode** — for the final wearable, the ESP32 creates a dedicated hotspot. The Android device connects to it. The "no internet" prompt on Android can be dismissed or suppressed programmatically in your app.

For the streaming protocol:

> [!TIP]
> **Start with MJPEG over HTTP** — it's the simplest, lowest-latency, most reliable option and integrates trivially with OpenCV/Python on your PC.
>
> **Then test WebSocket** as a comparison — it adds bidirectional control capability which you'll eventually want (e.g., changing resolution/quality on the fly from the app).
>
> **Skip RTSP** — it adds complexity without benefit for your use case.

---

## Phased Implementation Plan

### Phase 1: Hardware Validation (Get Each Camera Working)

For each camera module (OV2640, OV3660, OV5640):

#### 1.1 Basic Camera Init & Serial JPEG Capture
- Initialize the camera with correct pin mapping for XIAO ESP32S3 Sense
- Capture a single JPEG frame and report: success/failure, frame size, capture time
- Output diagnostics via Serial Monitor: sensor detected, PSRAM available, resolution set
- **Purpose:** Confirm each module is physically working

#### 1.2 Static Image Quality Check
- Capture frames at multiple resolutions (QQVGA, QVGA, VGA, SVGA, XGA, UXGA/QXGA)
- Save to SD card or transmit via Serial for visual inspection
- **Purpose:** Verify image quality and identify any defective modules

#### 1.3 Thermal Baseline
- Run continuous capture at VGA for 5 minutes per module
- Log temperature warnings or frame drops via Serial
- **Purpose:** Establish thermal behavior, especially for OV5640

---

### Phase 2: MJPEG HTTP Streaming (Primary Protocol)

#### 2.1 Basic MJPEG Stream Server
- ESP32 connects to WiFi in STA mode
- Serve MJPEG stream on `http://<ip>:80/stream`
- Serve single snapshot on `http://<ip>:80/capture`
- Serve a simple status/config page on `http://<ip>:80/`
- **Test with:** Browser on PC + Python/OpenCV script

#### 2.2 Configuration Endpoints
- HTTP endpoints to change resolution, JPEG quality, and XCLK at runtime
- Allows rapid testing without re-flashing
- Example: `GET /control?resolution=VGA&quality=12`

---

### Phase 3: WebSocket Streaming (Alternative Protocol)

#### 3.1 WebSocket Binary Stream
- ESP32 runs a WebSocket server alongside (or instead of) the HTTP server
- Sends JPEG frames as binary WebSocket messages
- PC client in Python receives and displays via OpenCV
- **Compare:** Latency and framerate vs. MJPEG

#### 3.2 Bidirectional Control
- PC sends JSON commands over the same WebSocket to change settings
- ESP32 sends back status/telemetry (FPS, frame size, WiFi RSSI, free heap)

---

### Phase 4: Structured Benchmarking

#### 4.1 Automated Benchmark Sketch
An Arduino sketch that:
- Cycles through all resolutions (QQVGA → UXGA)
- Tests JPEG quality settings (5, 10, 15, 20, 30)
- Tests XCLK frequencies (8, 10, 16, 20 MHz)
- For each combination, streams for 10 seconds and reports:
  - Average FPS
  - Average frame size (bytes)
  - Min/Max/Avg capture time (ms)
  - Min/Max/Avg transmit time (ms)
  - WiFi RSSI
  - Free heap memory

#### 4.2 PC-Side Benchmark Client
A Python script that:
- Receives the stream and measures:
  - Received FPS
  - End-to-end latency (using embedded timestamp method)
  - Frame decode time
  - Jitter (variance in inter-frame arrival)
- Logs all results to CSV for analysis

#### 4.3 Comparison Matrix
Run Phase 4.1 + 4.2 for **each camera module** and compile a comparison table:

| Camera | Resolution | Quality | XCLK | FPS | Frame Size | Latency | Stability |
|--------|-----------|---------|------|-----|-----------|---------|-----------|
| OV2640 | VGA | 12 | 20MHz | ? | ? | ? | ? |
| OV3660 | VGA | 12 | 20MHz | ? | ? | ? | ? |
| OV5640 | VGA | 12 | 20MHz | ? | ? | ? | ? |
| ... | ... | ... | ... | ... | ... | ... | ... |

---

### Phase 5: MediaPipe Integration Test

#### 5.1 Hand Tracking Pipeline
- Python script on PC that:
  - Receives the ESP32 MJPEG stream via OpenCV
  - Feeds frames to MediaPipe Hands
  - Overlays hand landmarks on the video
  - Reports tracking FPS and detection confidence
- **Purpose:** Validate that the "best" camera/settings from Phase 4 actually work well for hand tracking end-to-end

#### 5.2 Latency Measurement
- Use the "digital clock" method: display a millisecond timer on the PC monitor, point the camera at it, and compare the timer shown in the received stream vs. the live timer
- **Target:** Sub-350ms end-to-end

---

## Project File Structure

```
WearableV1/
├── phase1_camera_test/
│   └── phase1_camera_test.ino          # Hardware validation sketch
├── phase2_mjpeg_stream/
│   └── phase2_mjpeg_stream.ino         # MJPEG HTTP server sketch
├── phase3_websocket_stream/
│   └── phase3_websocket_stream.ino     # WebSocket server sketch
├── phase4_benchmark/
│   └── phase4_benchmark.ino            # Automated benchmark sketch
├── pc_client/
│   ├── mjpeg_viewer.py                 # MJPEG stream receiver + display
│   ├── websocket_viewer.py             # WebSocket stream receiver + display
│   ├── benchmark_client.py             # Benchmark data collector
│   └── mediapipe_test.py               # Hand tracking integration test
├── common/
│   └── camera_pins.h                   # Shared pin definitions for XIAO ESP32S3 Sense
└── results/
    └── benchmark_results.csv           # Collected benchmark data
```

## Arduino IDE Setup

- **Board:** "XIAO_ESP32S3" from the `esp32` board package by Espressif
- **PSRAM:** Must be enabled (Tools → PSRAM → "OPI PSRAM")
- **Partition Scheme:** "Huge APP (3MB No OTA)" recommended for maximum sketch space
- **Upload Speed:** 921600
- **Arduino IDE 2.3.8:** ✅ Confirmed compatible

### Required Libraries
- `esp_camera.h` (built into ESP32 board package)
- `WiFi.h` (built into ESP32 board package)
- `WebServer.h` (built into ESP32 board package)
- `ArduinoWebsockets` or `ESPAsyncWebServer` (for Phase 3, install via Library Manager)

### Python Dependencies (PC side)
- `opencv-python`
- `mediapipe`
- `websockets` (for Phase 3)
- `numpy`
- `matplotlib` (for benchmark visualization)

---

## Open Questions

> [!IMPORTANT]
> **1. Do you have all three camera modules in hand right now?** This determines whether we can start Phase 1 immediately or need to plan around module availability.

> [!IMPORTANT]
> **2. Do you have a specific WiFi network (SSID) you'll be testing on?** Or would you prefer to start with SoftAP mode so there's no router dependency? (I recommend STA mode for simplicity, but either works.)

> [!WARNING]
> **3. Python environment:** Do you have Python 3 installed on this PC with pip? The PC-side scripts (benchmark client, MediaPipe test) require it. If not, we can use a browser-only approach for initial testing.

> [!NOTE]
> **4. SD card:** Does your XIAO ESP32S3 Sense have a microSD card inserted? It's useful for Phase 1 (saving test images) but not required — we can use Serial output instead.

---

## Verification Plan

### Automated Tests
- Phase 1: Serial monitor output confirms each camera initializes and captures successfully
- Phase 4: Benchmark script auto-logs all metrics; Python client validates received data

### Manual Verification
- Phase 2/3: Open browser to `http://<ESP32_IP>/stream` and visually confirm video quality
- Phase 5: Run MediaPipe test and confirm hand landmarks are detected and tracked smoothly
- Latency: Digital clock test — visually compare real-time vs. streamed clock readings

```

---


### task.md
```markdown
# Air-Writing Wearable: Camera & Streaming — Task Tracker

## Phase 1: Hardware Validation
- [x] 1.1 Create shared `camera_pins.h` with XIAO ESP32S3 Sense pin definitions
- [x] 1.2 Build Phase 1 camera test sketch *(kept as utility — user prefers to go straight to streaming)*

## Phase 2: MJPEG HTTP Streaming (Start Here)
- [x] 2.1 Build MJPEG stream server sketch (with web UI, controls, status endpoint)
- [x] 2.2 Build Python MJPEG viewer client
- [ ] 2.3 **USER: Flash sketch, set WiFi credentials, test stream**

## Phase 3: WebSocket Streaming
- [x] 3.1 Build WebSocket binary stream sketch (with bidirectional control)
- [x] 3.2 Build Python WebSocket viewer client
- [ ] 3.3 USER: Flash sketch, test WebSocket stream, compare to MJPEG

## Phase 4: Structured Benchmarking
- [x] 4.1 Build automated benchmark Arduino sketch
- [x] 4.2 Build Python benchmark client (CSV export + matplotlib plots)
- [ ] 4.3 USER: Run benchmark on each camera module, collect results

## Phase 5: MediaPipe Integration
- [x] 5.1 Build hand tracking pipeline Python script
- [ ] 5.2 USER: Run mediapipe_test.py against best camera/settings

## Supporting Files
- [x] Python requirements.txt

```

---


### walkthrough.md
```markdown
# WearableV1 Camera Streaming — Walkthrough

## What Was Built

All code for all 5 phases has been written. Here's the complete project:

```
WearableV1/
├── common/
│   └── camera_pins.h                    # Shared pin defs for XIAO ESP32S3 Sense
├── phase1_camera_test/
│   └── phase1_camera_test.ino           # Hardware validation (utility, optional)
├── phase2_mjpeg_stream/
│   └── phase2_mjpeg_stream.ino          # MJPEG HTTP streaming server ← START HERE
├── phase3_websocket_stream/
│   └── phase3_websocket_stream.ino      # WebSocket binary streaming server
├── phase4_benchmark/
│   └── phase4_benchmark.ino             # Automated benchmark suite
└── pc_client/
    ├── requirements.txt                 # pip install -r requirements.txt
    ├── mjpeg_viewer.py                  # MJPEG stream viewer + FPS meter
    ├── websocket_viewer.py              # WebSocket viewer + keyboard controls
    ├── benchmark_client.py              # Benchmark data collector + plots
    └── mediapipe_test.py                # Hand tracking integration test
```

---

## How to Proceed

### Step 1: Set Up Python Environment

```powershell
cd c:\Users\samgr\ArduinoProjects\WearableV1\pc_client
pip install -r requirements.txt
```

### Step 2: Flash Phase 2 (MJPEG Stream)

1. Open `phase2_mjpeg_stream/phase2_mjpeg_stream.ino` in Arduino IDE
2. **Edit WiFi credentials** on lines 27-28:
   ```cpp
   const char* WIFI_SSID     = "YourNetworkName";
   const char* WIFI_PASSWORD = "YourPassword";
   ```
3. Arduino IDE settings:
   - **Board:** XIAO_ESP32S3
   - **PSRAM:** OPI PSRAM ← *critical!*
   - **Partition:** Huge APP (3MB No OTA)
4. Upload to the board
5. Open Serial Monitor (115200 baud) — note the IP address printed

### Step 3: View the Stream

**Option A — Browser:**
Open `http://<IP>/` in your browser. You'll see a live stream with resolution/quality controls.

**Option B — Python viewer:**
```powershell
python mjpeg_viewer.py --ip <IP>
```

**Option C — Quick OpenCV test (one-liner):**
```python
import cv2; cap=cv2.VideoCapture("http://<IP>/stream")
```

### Step 4: Test Each Camera Module

Swap camera modules on the XIAO Sense board and repeat Step 2-3. The sketch auto-detects the sensor type.

### Step 5: WebSocket Comparison (Phase 3)

1. Flash `phase3_websocket_stream.ino` (same WiFi credentials)
2. Open `http://<IP>/` for the WebSocket web UI, or:
   ```powershell
   python websocket_viewer.py --ip <IP> --control
   ```
3. Use keyboard controls: `R`/`r` for resolution, `Q`/`q` for quality

### Step 6: Run Benchmarks (Phase 4)

1. Flash `phase4_benchmark.ino`
2. The benchmark runs automatically on boot (takes ~10 minutes)
3. Watch results in Serial Monitor, or:
   ```powershell
   python benchmark_client.py --ip <IP> --fetch-only --plot
   ```
4. Repeat with each camera module

### Step 7: MediaPipe Integration (Phase 5)

With the best camera/settings from benchmarking:
1. Flash Phase 2 sketch with optimal settings
2. Run:
   ```powershell
   python mediapipe_test.py --ip <IP> --duration 60
   ```
3. Hold your hand in front of the camera — you should see tracked landmarks
4. The script reports whether the 350ms latency target is met

---

## Key Design Decisions

### WiFi Strategy
- **ESP32 is always STA mode** (connects to existing network as a client)
- For PC testing: connects to your home router
- For future mobile use: connects to the Android phone's hotspot (phone hosts, ESP32 joins)
- SoftAP kept only as automatic fallback if WiFi credentials fail

### Why MJPEG First
- Simplest, lowest latency (~80-200ms), most battle-tested on ESP32
- Trivially consumed by OpenCV (`cv2.VideoCapture`)
- Works in any browser without plugins
- WebSocket tested as comparison for its bidirectional control benefits

### Camera Expectations
| Camera | Expected Sweet Spot | Notes |
|--------|-------------------|-------|
| OV2640 | VGA @ 12-15 fps, Q12 | Most reliable, hw JPEG, lowest power |
| OV3660 | VGA @ 10-15 fps, Q12 | Better low-light, may run cooler than OV2640 |
| OV5640 | VGA @ 8-12 fps, Q15 | Autofocus available, runs hot, may be slower |

### MediaPipe Compatibility
- MediaPipe resizes input to 192×192 internally, so VGA (640×480) is more than enough
- 15+ fps is sufficient for hand tracking with temporal filtering
- QVGA (320×240) is viable if bandwidth is tight

---

## Files Changed

render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/common/camera_pins.h)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/phase1_camera_test/phase1_camera_test.ino)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/phase2_mjpeg_stream/phase2_mjpeg_stream.ino)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/phase3_websocket_stream/phase3_websocket_stream.ino)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/phase4_benchmark/phase4_benchmark.ino)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/pc_client/mjpeg_viewer.py)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/pc_client/websocket_viewer.py)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/pc_client/benchmark_client.py)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/pc_client/mediapipe_test.py)
render_diffs(file:///c:/Users/samgr/ArduinoProjects/WearableV1/pc_client/requirements.txt)

```

---
