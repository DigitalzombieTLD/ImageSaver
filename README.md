# ImageSaver
Personal Android app for viewing MJPEG streams and saving captured images with metadata.

## Features
- Enter the camera **IP or hostname** (host only, optional `:port`; no scheme or path) and press **Start**. The app connects to
  `http://<host>/stream`; pasted or older saved values containing `http://…/path` are normalized to just the host. The
  root page is informational; `/stream` is the MJPEG endpoint. The stream is shown in a zoomable preview (Zoom +/-, 1x
  reset, pinch, drag; double-tap captures the displayed frame; 1x–8x).
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
- **Stream parsing:** the app reads the `multipart/x-mixed-replace` boundary from the `/stream` response Content-Type
  (quoted or unquoted) and parses the parts incrementally: delimiter line, part headers, then the JPEG, using the part's
  `Content-Length` when present (otherwise reading up to the next delimiter; JPEG SOI/EOI scanning is used only when the
  response has no boundary). Header/frame sizes are limited and only the current frame is buffered. If the stream ends
  before the first frame, is truncated, or delivers JPEGs that cannot be decoded, the status shows the reason, e.g.
  `Disconnected: Stream ended before the first frame (…) – retrying in 1s…`, and the one-second retry continues.
- **Camera controls:** the app reads `GET /api/capabilities` and keeps the metadata of every advertised control from
  `controls` and `focus_controls` (deduplicated by name): type `int` or `menu`, min/max/step/default/value. Capability
  `default` values may lie outside min..max (sentinels) and are tolerated; the current `value` is the initial position.
  **Resolution, FPS and quality are not editable and never sent** – the Pi/uStreamer serves its configured mode; the
  active resolution/FPS from `GET /api/settings` is shown read-only.
  Below the zoom/rotation buttons the main page shows sliders for brightness, contrast, saturation, gamma, gain, sharpness
  and **Focus** (`focus_absolute`), as far as the camera advertises them. Every other advertised control (hue,
  power_line_frequency, white_balance_temperature, backlight_compensation, auto_exposure, exposure_time_absolute, …) is in
  **More camera controls**. Menu controls offer the integer indexes min..max (friendly names for power_line_frequency and
  auto_exposure, raw number kept). **Apply controls** sends one `POST /api/settings` with
  `{"controls": {name: int}}` containing only controls you changed (validated against min/max/step); the Pi stops the
  stream when settings change, so the app then restarts it. Nothing is ever POSTed automatically at startup, reconnect or
  retry, and settings/network calls run off the UI thread. Success or failure is shown below the Apply button.
- **Debug: ignore saved camera settings** (persistent checkbox): the app does not call `GET /api/settings` and does not
  display or use saved settings (sliders start from the camera's advertised values); capabilities are still read to build
  the controls and streaming continues. This only affects the app: the provided Pi server loads
  `/var/lib/mobile-webcam/settings.json` and reapplies saved controls itself in `start_stream()` and has no endpoint/flag to
  suppress that, so bypassing the server's persistence would need a companion server change.
- **Rotate ⟲ / ⟳** turns the preview in 90° steps (display only; saved JPEG bytes are never modified) and works with zoom/pan
  (pan resets on rotation).
- **Nummer** and **Stand** use decimal number pads and accept optionally signed decimal numbers (`,` or `.`; no exponent);
  invalid input is rejected and nothing is saved. Values are normalized to a `.` decimal separator. Empty fields use `X`.
- **Include GPS location** is on by default and remembered across restarts. Turn it off to skip location permission and lookup
  entirely; capture then saves immediately with `0.0_0.0` in the filename.
- **Capture** saves the currently displayed frame as a JPEG in the public `DCIM/ImageSaver` folder (created if needed) via
  MediaStore, so it is visible in gallery apps. Double-tapping the preview invokes this same capture flow. No broad storage
  access is requested (Android 9 and older ask for the legacy storage permission at first save).
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

## Stream diagnostics and save feedback

- The status line shows `Connected – N frame(s)` while frames are applied to the preview. When a stream attempt ends, the
  status names the reason, e.g. `Disconnected: connected but the camera sent no bytes [HTTP 200, Content-Type: …,
  parsed/decoded/shown counters] – retrying in 1s…`. A read timeout (10 s without data) is reported as idle/slow camera.
- The stream request sends `Connection: close`, `Accept-Encoding: identity` and disables caching; the camera's response
  body is delimited by EOF, so a clean EOF is treated as a disconnect and retried after 1 s.
- After a successful save (Capture button or double-tap) the preview briefly flashes white (~300 ms, 35 % peak alpha).
  Failed saves do not flash. The overlay is not touchable and does not affect the saved JPEG.
