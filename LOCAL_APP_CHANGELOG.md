# Si Gyi Studio — Local App Change Report

## Final workflow

ယခု app သည် `Upload video → Video style → Render MP4` သုံးဆင့်သာ ပါဝင်သည်။ Video file ကို browser ထဲတွင် local object URL ဖြင့် preview လုပ်ပြီး server သို့ မတင်ပါ။

## ဖယ်ရှားထားသောအရာများ

Gemini API key modal, Gemini video analysis, transcript/script editor, Burmese recap generation, Edge TTS voice picker, audio synthesis, `/api/tts` endpoint နှင့် legacy TTS bot တို့ကို source package ထဲမှ ဖယ်ရှားထားသည်။ ထို့ကြောင့် app run သည့်အခါ Gemini API key၊ Python၊ Edge TTS သို့မဟုတ် TTS server မလိုတော့ပါ။

## ဆက်လက်ထားသောအရာများ

မူရင်း UI visual language နှင့် style controls များကို ဆက်လက်အသုံးပြုနိုင်သည်။ 9:16 fit/fill, left/center/right crop, horizontal flip, zoom, top/bottom blur, blur strength, natural/cinematic/vivid/mono filter, brightness, contrast, saturation, frame color/width, optional logo နှင့် logo position တို့ ပါဝင်သည်။

Render သည် browser ထဲရှိ FFmpeg WASM engine ဖြင့်လုပ်ပြီး MP4 download ပြန်ပေးသည်။ Original audio track ကို မဖယ်ဘဲ output ထဲတွင် ထိန်းထားသည်။ Caption/script data မရှိသောကြောင့် ဒီ version တွင် subtitle burn-in မလုပ်ပါ။ Subtitle-related visual blur/fit style controls များကိုတော့ မူရင်း style compatibility အတွက် ထိန်းထားသည်။

## Validation

`node --check` ဖြင့် `src/main.js`, `src/lib/render.js`, `vite.config.js` အောင်မြင်သည်။ Dependency install ပြီး `pnpm run build` လည်း အောင်မြင်သည်။ Production build တွင် Gemini/TTS module chunk မပါဘဲ app bundle နှင့် FFmpeg WASM assets သာ ထွက်လာသည်။

ဖုန်းတွင် short MP4 ဖြင့် အရင်စမ်းပါ။ 720p သည် ပုံမှန်ရွေးချယ်ရန် သင့်တော်ပြီး 1080p သည် memory နှင့် render time ပိုလိုနိုင်သည်။


## 2026-11-21 update

- Removed Gemini/SRT workflow: this source package already had no Gemini or SRT upload UI.
- Added optional local MP3/WAV dubbing audio upload; uploaded audio replaces the source audio during export.
- Render now encodes the source in 5-minute parts and concatenates the parts into one final MP4.
- Added a subtle repeating zoom-in/zoom-out motion to the exported video.
- Freeze-frame-at-5-second intervals is not yet implemented in this build and remains a follow-up render-engine task.

## Chunk render / freeze-frame update
- Added a brief 6-frame freeze beat at each 5-second point in every independently rendered part.
- Kept the existing 300-second (5-minute) part boundaries and stream-copy concat merge.
- Fixed uploaded MP3 dubbing input selection: the render command now opens the actual detected audio filename rather than always looking for `voice.wav`.
- Part progress labels now show the time range being rendered.

Memory limitation: ffmpeg.wasm uses an in-memory virtual filesystem. The source video and rendered parts still occupy browser/WASM memory, so 5-minute chunking lowers peak encoder workload but does not guarantee that a 1-hour 720p/1080p export will fit on every Android phone. A true low-memory 1-hour workflow needs a native Android FFmpeg engine or disk-backed streaming/muxing rather than the current browser-only WASM engine.


## 2026-11-21 — Native Android rendering foundation

- Added a Capacitor Android host and custom `NativeRender` plugin.
- Android media is selected through the Storage Access Framework and copied to app-private disk storage, avoiding JavaScript base64/Blob loading for long source files.
- Native FFmpeg runs sequential 5-minute chunks, applies a short 6-frame freeze at 5-second intervals, and stream-copies chunks into a final MP4.
- Output is saved to `Movies/SiGyi Recap Studio`; a cache preview is exposed to the WebView.
- Uses the maintained FFmpegKit-compatible LGPL artifact and OpenH264 encoder.
- Browser/PWA rendering remains unchanged.
- Native port in this iteration does not yet apply advanced blur bands or logo overlay; these remain browser-renderer-only. Border frame is supported natively.
- APK/device runtime build has not been executed in this environment.

## Native overlays — 2026-11-21
- Replaced global top/bottom blur bands with an optional, positionable subtitle-area blur mask (X/Y/width/height/strength).
- Added native Android image picker for a logo; logo is composited at the top-right and its size is adjustable.
- Added custom title text with position, size and color controls. Android renders the title to a transparent PNG using the system text layout engine so Myanmar shaping uses Android font fallback before FFmpeg composites it.
- Updated editor preview and native FFmpeg filter graph so overlays are applied per chunk before merge.
