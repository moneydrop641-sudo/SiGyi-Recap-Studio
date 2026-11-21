# Local App Acceptance Checklist

## Existing browser/PWA workflow
- [x] Local video upload and preview
- [x] MP3/WAV dubbing upload
- [x] FFmpeg WASM browser rendering
- [x] 5-minute chunk render and MP4 concat merge
- [x] Freeze-frame beat every 5 seconds
- [x] Gemini, SRT upload, script and TTS workflow removed

## Android native renderer
- [x] Capacitor Android host and custom NativeRender plugin scaffold
- [x] Android Storage Access Framework video picker
- [x] Android MP3/WAV picker with extension validation
- [x] Copy selected media to app-private disk storage (not JS memory)
- [x] Sequential 5-minute native FFmpeg parts
- [x] Freeze frame at 5-second intervals, including part boundaries
- [x] Native crop/fill/fit, zoom motion, flip, color adjustment and frame border
- [x] Stream-copy merge and MediaStore export to Movies/SiGyi Recap Studio
- [x] Native progress and cancel bridge
- [ ] Port native blur-band filters
- [ ] Port native logo overlay
- [ ] Build APK in GitHub Actions and resolve any Gradle/API issues
- [ ] Runtime test on a physical Android phone with short clips, then 5-minute and 1-hour videos
- [ ] Measure peak storage use and rendering speed on target devices

## Important limitations

The Android native renderer is disk-backed for the source and each 5-minute part, so it avoids loading the full source and all output parts into JavaScript/WASM memory. It still needs substantial free internal storage for source, parts, merged output and the final Movies copy. Keep the app open during rendering. A one-hour render is not yet device-validated or guaranteed on every phone; begin with 360p and a short clip.

The native Android renderer currently applies fit/fill, crop focus, zoom motion, flip, brightness, contrast, saturation, color presets, frame border and freeze beats. Blur bands and logo overlay remain available only in the browser renderer until ported.

## Validation status

- `node --check` passed for all JavaScript source files.
- npm dependency installation timed out in this environment, so Vite production build was not run for this native update.
- Android Gradle/APK build and physical-device FFmpeg tests have not been run in this environment.

## Device validation still required — native overlays
- [ ] Build debug APK with Android SDK/Gradle.
- [ ] Test Burmese and English title shaping on a physical Android device.
- [ ] Test PNG/JPEG/WebP logo alpha and sizing.
- [ ] Verify subtitle blur mask alignment against actual 9:16 output after fit/fill/crop/zoom.
- [ ] Verify chunk boundary overlay continuity and final concat on 360p, 720p and 1080p.
- [ ] Confirm low-storage failure handling and cleanup after cancellation.
- [ ] Run a 1-hour source video on the target phone and record peak storage/thermal behavior.
