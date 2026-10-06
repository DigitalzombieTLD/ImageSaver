# ImageSaver
Personal Android app for viewing MJPEG streams and saving captured images with metadata.

## Features
- Enter an MJPEG URL and press **Start**; the stream is shown in a zoomable preview (Zoom +/-, 1x reset, pinch, drag, double-tap reset; 1x–8x).
- On connection failure or disconnect the app retries every second (never overlapping) until you press **Stop**.
- **Discover** scans the /24 subnet (255.255.255.0) of the device's active IPv4 address (e.g. `192.168.1.x`) for hosts
  with TCP port 80 open (max 32 parallel probes, short timeouts; nothing outside the local /24 is contacted). Reachable hosts
  are probed with HTTP GET on common paths (`/`, `/video`, `/stream`, `/mjpg/video.mjpg`, `/video.mjpg`, `/?action=stream`,
  `/videostream.cgi`); a `multipart/x-mixed-replace` response is marked "MJPEG stream" and its URL is used. Other reachable
  HTTP hosts are listed as "HTTP service" with `http://<ip>/` – edit the stream path in the URL field afterwards. Selecting
  an entry fills the URL field. The scan runs in the background, shows progress, can be cancelled (button turns into
  **Cancel**) and is cancelled when the app goes to the background.
- **Nummer** (decimal number pad) accepts an optionally signed decimal number (`,` or `.`; no exponent); invalid input is
  rejected and nothing is saved. **Stand** is free text (sanitized to `A-Za-z0-9.-`). Blank fields are omitted from the name.
- **Capture** saves the currently displayed frame as a JPEG in the public `DCIM/ImageSaver` folder (created if needed) via
  MediaStore, so it is visible in gallery apps. No broad storage access is requested (Android 9 and older ask for the legacy
  storage permission at first save).
- Filename: `ID_YYYY-MM-DD_HH-mm-ss_LAT_LON_Nummer_Stand.jpg` (ID is persistent and increments across restarts).
  If GPS is unavailable (permission denied, no fix, 10 s timeout) a status message is shown, the image is still saved and
  the location component is `X`.
- After each save the filename and size (or an error) is displayed together with an **Open image** button that opens the
  saved picture in an installed image viewer (hidden before a save and after a failed save).

## Build / run
Requires JDK 17 and the Android SDK (platform 34). Create `local.properties` with `sdk.dir=/path/to/sdk`, then:

```
./gradlew testDebugUnitTest   # unit tests
./gradlew installDebug        # install on a connected device/emulator
```
