# Si Gyi Recap Studio V5 — Offline Editor update

## Added from the supplied PDF
A new **Offline Editor** tab was added alongside the Gemini / Style / Render workflow.

- Cut Video: start/end time in seconds.
- Merge Video: select multiple videos.
- Auto Video Splitter: fixed clip presets (15s, 30s, 60s, 3m, 5m, 10m, 15m).
- Smart Highlights: locally detects visual scene changes and exports up to 10 short clips. This is visual-change detection only; it does not understand speech, plot, or semantic story highlights.
- Export settings: original / vertical 9:16 frame, original size / 720p / 1080p, speed, rotate, mute original audio, fill-frame crop or fit with black bars.
- MP4 downloads are generated locally using the bundled FFmpeg WASM core. No video upload or cloud processing is used by this feature.

## Important implementation notes
- The offline editor uses the same local FFmpeg WASM package as the existing render flow.
- The FFmpeg WASM engine still requires the app's bundled JS/WASM assets to have been installed/built into the app package once. After that, the editor does not need an internet connection.
- Large videos can exceed Android WebView/WASM memory limits. Long or high-resolution clips may fail on low-memory phones; start with short clips and Original/720p output.
- Merge expects input clips with compatible audio streams. If a selected clip has no audio, merge may fail; mute mode avoids the audio concat path.
- Smart Highlights are approximate scene-change candidates, not editorial judgement. Up to 10 candidate clips are produced.

## Validation performed in this workspace
- `node --check src/main.js` — passed.
- `node --check src/lib/render.js` — passed.
- Android APK compile was **not** run: this workspace has no Gradle executable, Android SDK / adb / sdkmanager, or Gradle cache.
- Dependency installation was attempted twice but timed out; `node_modules` was not created. Therefore Vite production build, native APK compile, Google/Gemini sign-in, native download capture, and real-device audio/SRT sync remain unverified.
- The previous Gemini native WebView / Download Bridge source is retained. Standard downloads and Gemini authentication must be validated on a real Android build. Google may restrict sign-in inside embedded WebViews.
