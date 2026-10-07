# ImageSaver
Personal Android app for viewing MJPEG streams and saving captured images with metadata.

## Features
- Enter the camera **IP or hostname** (host only, optional `:port`; no scheme or path) and press **Start**. The app connects to
  `http://<host>/stream`; pasted or older saved values containing `http://…/path` are normalized to just the host.
  The stream is shown; the stream is shown in a zoomable preview (Zoom +/-, 1x reset, pinch, drag, double-tap reset; 1x–8x).
- On connection failure or disconnect the app retries every second (never overlapping) until you press **Stop**.
- **Discover** scans only the /24 subnet of the Android **hotspot/tethering interface** (never the upstream Wi-Fi/LAN) for
  hosts with TCP port 80 open (max 32 parallel probes, short timeouts, cancellable, off the UI thread). Turn on the hotspot
  and connect the camera first. Reachable hosts are probed on common paths (`/`, `/video`, `/stream`, `/mjpg/video.mjpg`,
  `/video.mjpg`, `/?action=stream`, `/videostream.cgi`); a `multipart/x-mixed-replace` response is marked "MJPEG stream",
  other HTTP hosts are listed as "HTTP service" (edit the path afterwards). Selecting an entry fills the host field with the host only.
  **Android limitations:** there is no public API to get the tethering interface or the list of hotspot clients (and
  ARP/neighbour tables are unreadable for apps on Android 10+). The app therefore finds the hotspot interface by
  enumerating network interfaces with tethering-style names (`ap0`, `swlan0`, `wlan1`, `rndis0`, …) that are not used by any
  regular network, and assumes a /24. If the hotspot shares the interface with the connected Wi-Fi, uses an unusual name, or
  is off, discovery fails with a hint and the URL must be entered manually. Discovery is not guaranteed on every device/OS.
- **Camera settings** (resolution spinner, quality 10–40, **Apply**): before each stream connection attempt the app reads
  `GET /status` exactly once and shows the current resolution and quality. **Apply** sends one
  `POST /camera/settings` with body `resolution=<id>&quality=<n>` (form URL-encoded, ≤40 bytes) off the UI thread, shows the
  returned active settings (no extra status request), reports HTTP 400/503/500, network and malformed-response errors, then
  restarts the stream. Resolutions: 6 QVGA 320x240, 2 VGA 640x480, 1 SVGA 800x600, 3 XGA 1024x768, 4 SXGA 1280x1024,
  5 UXGA 1600x1200.
- **Rotate ⟲ / ⟳** turns the preview in 90° steps (display only; saved JPEG bytes are never modified) and works with zoom/pan
  (pan resets on rotation).
- **Nummer** and **Stand** use decimal number pads and accept optionally signed decimal numbers (`,` or `.`; no exponent);
  invalid input is rejected and nothing is saved. Values are normalized to a `.` decimal separator. Empty fields use `X`.
- **Include GPS location** is on by default and remembered across restarts. Turn it off to skip location permission and lookup
  entirely; capture then saves immediately with `0.0_0.0` in the filename.
- **Capture** saves the currently displayed frame as a JPEG in the public `DCIM/ImageSaver` folder (created if needed) via
  MediaStore, so it is visible in gallery apps. No broad storage access is requested (Android 9 and older ask for the legacy
  storage permission at first save).
- Filename: `ID_YYYY-MM-DD_HH-mm-ss_LAT_LON_Nummer_Stand.jpg` (ID is persistent and increments across restarts). Each
  number field is always present; an empty field is `X`. If enabled GPS lookup is unavailable (permission denied, no fix,
  or 10 s timeout), a status message is shown, the image is still saved, and the location component is `0.0_0.0`.
- After each save the filename and size (or an error) is displayed together with an **Open image** button that opens the
  saved picture in an installed image viewer (hidden before a save and after a failed save).

## Build / run
Requires JDK 17 and the Android SDK (platform 34). Create `local.properties` with `sdk.dir=/path/to/sdk`, then:

```
./gradlew testDebugUnitTest   # unit tests
./gradlew installDebug        # install on a connected device/emulator
```
