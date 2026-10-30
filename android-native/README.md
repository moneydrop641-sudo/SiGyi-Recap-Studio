# Android native bridge integration

After running `npm run android:add`, copy `GeminiDownloadBridgePlugin.java` and `MainActivity.java` into:
`android/app/src/main/java/com/sigyi/recapstudio/`.

The plugin attaches a WebView `DownloadListener`, downloads files through Android DownloadManager, and emits `downloadedFile` with a base64 payload. The web app imports `.wav` and `.srt` automatically. Large downloads are currently buffered in memory; for very large media, replace this with a streaming/native file URI handoff before production.

Then run `npm run android:sync` and build the APK from Android Studio or Gradle.
