# Android APK build and verification

1. Install Node.js LTS, Android Studio, Android SDK Platform, and a JDK supported by the installed Android Gradle Plugin.
2. In this folder run `npm install`.
3. Run `npm run android:add` once. This creates the Capacitor Android project and copies the Gemini download plugin.
4. Run `npm run android:sync` after web/native changes.
5. Open with `npm run android:open`, wait for Gradle sync, then Build > Build Bundle(s) / APK(s) > Build APK(s).
6. Test on a real Android phone: load Gemini Canvas, sign in, download a WAV and SRT, verify each auto-imports into Video Style, then render and check audio/subtitle sync.

## Current bridge limits
- Android DownloadManager handles ordinary HTTP(S) file downloads. `blob:` / `data:` downloads generated inside a page need a separate JavaScript blob handoff and are not guaranteed to be intercepted by DownloadManager.
- Some Gemini pages may block iframe embedding. If so, the URL must be opened as a top-level page inside the app's WebView rather than embedded in an iframe.
- Large media files are currently buffered as base64 in memory for transfer to the web app. Optimize to native file-URI streaming before production use with long videos.
- Android SDK/Gradle build and device tests have not been run in this environment; do not treat this source project as a finished APK.


## V5 validation note
Before APK build, install dependencies and run `npm run build`. This workspace does not contain Gradle or the Android SDK, so APK compilation must be performed on a machine with Android Studio, JDK, Gradle and Android SDK installed. See `OFFLINE_EDITOR_V5.md` for the offline editor feature list and validation limits.
