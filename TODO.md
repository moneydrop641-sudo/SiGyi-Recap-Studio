# Remaining real-device validation

## V5 offline editor
- Install dependencies and run `npm run build`.
- Add/sync the Capacitor Android project on a machine with Android Studio, Android SDK and Gradle.
- Build and install the debug APK.
- Test Cut / Merge / Split / Smart Highlights with short MP4 files.
- Test output frame, resolution, speed, rotation, mute and fill-frame options.
- Check memory usage and playback on low-, mid- and high-memory Android devices.

## Gemini + recap render
- Test Google sign-in and Gemini share-page navigation in the in-app WebView; Google may block embedded WebView authentication.
- Verify selecting a video from Gemini's file chooser also imports it into Video Style.
- Verify standard WAV/SRT downloads and Blob downloads are intercepted and auto-imported.
- Render a real project and inspect audio duration, SRT timestamps, subtitle burn-in and MP4 playback.
- Replace base64 buffering in the native bridge with streaming native file-URI handoff for large files.
