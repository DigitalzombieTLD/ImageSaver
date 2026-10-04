# ImageSaver
Personal Android app for viewing MJPEG streams and saving captured images with metadata.

## Features
- Enter an MJPEG URL and press **Start**; the stream is shown in a zoomable preview (Zoom +/-, 1x reset, pinch, drag, double-tap reset; 1x–8x).
- On connection failure or disconnect the app retries every second (never overlapping) until you press **Stop**.
- **Capture** saves the currently displayed frame as a JPEG in the app's external Pictures directory
  (`Android/data/app.imagesaver/files/Pictures`, falling back to internal storage).
- Filename: `ID_YYYY-MM-DD_HH-mm-ss_LAT_LON_text.jpg` (ID is persistent and increments across restarts; text is sanitized).
  If GPS is unavailable (permission denied, no fix, 10 s timeout) a status message is shown, the image is still saved and
  the location component is `_no_gps`.
- After each save the filename and size (or an error) is displayed.

## Build / run
Requires JDK 17 and the Android SDK (platform 34). Create `local.properties` with `sdk.dir=/path/to/sdk`, then:

```
./gradlew testDebugUnitTest   # unit tests
./gradlew installDebug        # install on a connected device/emulator
```
