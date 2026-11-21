# Si Gyi Studio — Upload / Style / Render

ဒီ version သည် local video editing app ဖြစ်ပြီး Gemini script generation နှင့် TTS audio generation မပါပါ။ Workflow သည် video upload → video style → local MP4 render သုံးဆင့်သာ ဖြစ်သည်။

## Run

```bash
npm install
npm run dev
```

Browser တွင် `http://localhost:3000` ကို ဖွင့်ပါ။ Production build အတွက် `pnpm run build` ကို သုံးပါ။

## Included behavior

Video ကို browser ထဲသို့ local file အဖြစ် upload လုပ်နိုင်သည်။ Existing style controls များဖြစ်သော 9:16 fit/fill, crop focus, flip, zoom, top/bottom blur, blur strength, brightness, contrast, saturation, color filters, frame color/width နှင့် optional logo overlay ကို ဆက်လက်အသုံးပြုနိုင်သည်။

Render သည် FFmpeg WASM ကို browser ထဲတွင် run ပြီး MP4 အဖြစ် export လုပ်သည်။ Original video audio ကို ထိန်းထားသည်။ Gemini API key, script editor, transcript, Edge TTS, voice picker နှင့် server-side TTS endpoint မပါပါ။

ဖုန်းတွင် short clip ဖြင့် အရင်စမ်းပါ။ 720p ကို ပုံမှန်သုံးရန် အကြံပြုသည်။ 1080p သည် device memory နှင့် ကြာချိန်ပိုလိုနိုင်သည်။

## Long-video rendering notes

The renderer processes the source in sequential 5-minute parts, applies a short freeze-frame beat every 5 seconds, and stream-copies the parts into one MP4. MP3 and WAV dubbing uploads are both supported. The browser/WASM build still holds the source and intermediate files in memory; therefore, this is a best-effort chunked renderer, not a guaranteed 1-hour low-memory native renderer. Test with 360p first. For dependable one-hour exports on Android, move the render engine to native Android FFmpeg or a disk-backed streaming implementation.


## Android Native Renderer (new)

The Android app uses a native FFmpegKit bridge instead of FFmpeg WASM. It picks source media with Android's Storage Access Framework, copies the selected files to app-private storage (disk, not JavaScript memory), renders sequential 5-minute parts, adds a 6-frame freeze beat at each 5-second mark, stream-copies the parts into one MP4, and saves the result to `Movies/SiGyi Recap Studio`. The browser/PWA version continues to use the existing WASM renderer.

### Build APK (Termux or CI)

```bash
npm install
npm run build
gradle -p android assembleDebug --no-daemon
```

The generated APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. Java 17 and Android SDK 35 are required. The FFmpeg AAR is `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7` (LGPL build); the encoder uses OpenH264 rather than GPL x264.

### Native overlay tools in this update

- Subtitle-only blur mask: enable/disable and adjust X, Y, width, height and blur strength. It blurs only the selected rectangle; there are no full-frame top/bottom blur bands.
- Logo overlay: select a PNG/JPG/WebP from Android storage. It is placed at the top-right and its width is adjustable.
- Custom title: enter text and adjust X/Y position, size and color. Android renders a transparent title image using its text layout engine, then FFmpeg composites it into each chunk.
- The overlays are rendered into every 5-minute chunk before the parts are merged.

### Validation still required

- This environment did not complete an Android Gradle/APK build or physical-device render test. Build the APK in GitHub Actions and test a short 360p clip first.
- Confirm Burmese title shaping on the target phone and verify logo transparency for PNG/WebP.
- Part rendering is sequential and intermediates are stored on disk. The final merged file is temporarily retained for preview and copied to Movies, so ensure adequate free storage.
- 5-second freeze means a 6-frame hold at the configured output FPS (about 250 ms at 24 fps; about 333 ms at 18 fps).

### Build in GitHub Actions

Push the project to a GitHub repository and run the **Build SiGyi Android APK** workflow from the Actions tab. The debug APK is uploaded as `sigyi-recap-studio-debug-apk`. This is the recommended build path when developing from an Android phone with Termux because it avoids installing the full Android SDK locally.


## Copyright and YouTube use

The app's blur, crop, zoom, logo and title tools are editing features; they do not hide a video from Content ID and do not guarantee fair use. A translation is generally a derivative/adapted work, and YouTube says translations usually still require permission unless a valid copyright exception applies. Prefer written permission/licensing, Creative Commons terms that allow adaptation, or public-domain material. If relying on fair use/fair dealing, add substantial original criticism, analysis or commentary and use only the amount genuinely needed; no fixed number of seconds guarantees safety. Copyright rules vary by country, and only a court can finally decide fair use.
