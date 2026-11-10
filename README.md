# SOL CX3 USB Camera — Android live RAW preview (experimental)

Independent Kotlin/Android USB Host proof of concept for a Cypress/Infineon CX3 bridge. This is **not** CameraFi source code and does **not** claim compatibility with SOL's proprietary firmware yet.

## Implemented
- USB device enumeration, VID/PID, interface and endpoint inspection, Android permission prompt.
- Claim first Bulk IN endpoint and continuously receive payloads.
- Reassemble fixed-size RAW8, MIPI RAW10 and MIPI RAW12 frames by width/height.
- Display grayscale preview on Android ImageView, throttled to about 5 preview updates/second (capture continues independently).
- Manual vendor control transfers: editable hexadecimal bmRequestType, bRequest, wValue, wIndex and OUT payload; IN read length.
- Start/stop capture UI and error reporting.

## Not implemented / cannot assume
- **Exact CX3/SOL firmware control opcodes** (start/stop/reset, sensor register programming). No valid command can be asserted without firmware source, documented request map, or verified USB trace.
- Automatic frame synchronization or resynchronization. The code assumes tightly packed pixel bytes with no frame headers, line padding, packet metadata or inter-frame gaps.
- UVC VideoStreaming negotiation (VS_PROBE_CONTROL / VS_COMMIT_CONTROL), UVC isochronous payload parsing, MJPEG/YUY2, or Android CameraFi protocol emulation.
- Color demosaicing, 50MP memory/performance optimization, USB disconnect recovery, safe reconnect while streaming.
- APK build, device testing, real-time throughput/FPS benchmarking.

## Usage
1. Open in Android Studio; install Android SDK 35 and sync Gradle.
2. Connect Android USB-OTG host to CX3; select a device and grant USB permission.
3. Inspect the listed interface class and endpoints. This app needs a **Bulk IN** endpoint.
4. Enter actual RAW frame width, height, and pixel format; start preview only after the CX3 firmware is already streaming.
5. If the firmware requires start commands, obtain the **verified** request map or USBPcap/Wireshark capture from the original PC Viewer, then enter the correct bmRequestType/bRequest/wValue/wIndex/payload.
6. If bytes are received but the picture is scrambled, check frame boundaries, line stride, CSI packing, sensor mode and metadata; do not interpret the preview as valid measurement data.

## Technical cautions
- Android USB Host bulkTransfer() is not a substitute for a full UVC stack.
- Some CX3 firmware uses UVC, others use vendor Bulk; identify actual descriptors first.
- Do not send guessed vendor commands: they may reconfigure hardware or firmware unexpectedly.
- Preview converts to an ARGB bitmap and is suitable for small diagnostic frames; full-resolution 8MP/50MP needs a bounded buffer pool, efficient unpacking and GPU rendering.
- The repository is public. Do not commit proprietary SOL firmware, captured patient/sample data, or secrets.

## Evidence needed for hardware-specific integration
CX3 USB VID/PID and descriptors; original PC Viewer or its USB control trace; known working sensor mode (RAW8/10/12, width, height, line stride); start/stop command sequence; packet framing details.

## Status
Source-only PoC, not yet compiled or tested on physical hardware.
