# Si Gyi Recap Studio

Android-focused Capacitor app with Gemini in-app WebView/native download bridge, local WAV/SRT/video handoff, FFmpeg-WASM rendering, and an offline editor.

## Build on GitHub Actions

Push this source to `main` or `master`, or run **Actions → Android APK Build → Run workflow**. The workflow compiles a debug APK and uploads it as `SiGyi-Recap-Studio-debug-apk`.

## Local web build

```bash
npm install
npm run check
npm run build
```

## Android build

```bash
npm install
npm run build
npx cap add android
npm run android:bridge
npx cap sync android
cd android && ./gradlew assembleDebug
```

See `TERMUX_GITHUB_ACTIONS_MY.md` for the Termux → GitHub → Actions flow and `BUILD_STATUS_FINAL.md` for the exact validation boundary.

## Known device-dependent limitations

Google may block account authentication in embedded WebViews. Gemini file download capture and import behavior depends on the actual Gemini page's download implementation and must be tested on-device. Large media files can exceed Android WebView / FFmpeg-WASM memory limits. A successful APK compile does not prove these runtime workflows.
