# Si Gyi Recap Studio — In-App Gemini Handoff V4

## Changes
- Removed the external-browser launch link from the Gemini toolbox UI.
- Gemini shortcut buttons now open a native, full-screen Android WebView dialog through `GeminiDownloadBridge.openGeminiWebView()`.
- The native WebView enables JavaScript, DOM storage and persistent Google cookies, and shows a close control and load progress.
- Gemini upload file chooser supports selecting Video / WAV / SRT from Android storage; selected media is also handed back to the app render state while the URI is returned to Gemini for upload.
- Standard Gemini file downloads are captured with Android DownloadManager, including session cookies and user-agent; completed files are returned to the app as `downloadedFile` events.
- Added an in-page Blob-download hook for `<a download>` blob links (up to 100 MB) and forwards the resulting bytes through the same import event.
- Video, WAV and SRT imports route to the existing Video Style & Render state. Importing WAV or SRT after a video is present opens the Style tab automatically.
- The first-tab download status now lists the current Video, WAV and SRT assets.
- SRT segments continue to drive the subtitle overlay timestamps. WAV replaces the source audio; without WAV, source video audio is preserved.

## Important limitations
- Google may restrict sign-in in embedded WebViews on some accounts/devices. The app does not bypass Google's security checks.
- The Blob transfer currently buffers up to 100 MB in memory. Large video downloads should use the standard DownloadManager file path; a streaming file-URI bridge is a future optimization.
- The Android native source has not yet been compiled into an APK in this environment.
- Build and device testing still require Android SDK/Gradle and a physical Android test run.
