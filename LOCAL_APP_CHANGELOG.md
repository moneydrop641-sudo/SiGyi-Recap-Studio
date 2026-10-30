# Local App Changelog

## V4 — In-app Gemini + render handoff
- Removed all external-browser fallback controls from the Gemini panel.
- Added a native full-screen Android Gemini WebView dialog and in-app open action.
- Captures normal WebView downloads with Android DownloadManager and returns Video/WAV/SRT files to the frontend import bridge.
- Added Android file chooser support: a video/WAV/SRT selected inside Gemini is also imported into the app render state.
- Added Blob anchor download capture for smaller generated files.
- WAV/SRT arrival while a source video is loaded automatically switches to Video Style.
- First-tab status shows all three render inputs: Video, WAV and SRT.
- Render still uses the SRT timestamp timeline and WAV as the replacement narration track.

## Verification
- `node --check src/main.js` passed.
- `node --check src/lib/render.js` passed.
- Full Vite build and Android APK build not verified: dependency installation did not complete within the available execution window; Android SDK/Gradle device test is not available here.


## V5 — Offline Editor tab
Added a new Offline Editor tab based on the user-supplied two-page PDF: Cut, Merge, fixed-length Auto Splitter, visual Smart Highlights (up to 10), export frame/output size/speed/rotate/mute/fill-frame controls. Uses bundled local FFmpeg WASM; no cloud upload in this tab. APK build and real-device validation remain pending; see OFFLINE_EDITOR_V5.md and TODO.md.
