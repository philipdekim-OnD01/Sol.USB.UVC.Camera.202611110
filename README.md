# CX3 Android USB Viewer — diagnostic PoC

Independent Kotlin Android project for a CX3 vendor-specific USB device. This is NOT decompiled CameraFi source. The app enumerates USB devices, lists interfaces/endpoints, requests permission, and captures up to 32 MiB from the first vendor-specific bulk IN endpoint for up to 5 seconds.

## Build
Open in Android Studio with Android SDK 35 and Gradle 8.7-compatible toolchain; sync and run on an Android phone with USB Host/OTG. The source project has not been built or hardware-tested in this environment.

## Limitations
- No sensor register configuration, CX3 firmware protocol, stream-start control transfer, frame boundary detection, or RAW10/RAW12 unpacking.
- Does not display video yet; captures diagnostic bytes only.
- Some CX3 firmware exposes different USB interface classes/endpoints, so adapt enumeration based on real descriptors.
- Bulk IN will time out if the device requires vendor-specific stream-start commands.
- Do not reuse CameraFi proprietary libraries or code; independent implementation only.

## Next evidence needed
CX3 VID/PID, USB descriptors, firmware or PC viewer source, USBPcap/Wireshark trace including device initialization and one image capture, sensor resolution/bit depth, frame markers.
