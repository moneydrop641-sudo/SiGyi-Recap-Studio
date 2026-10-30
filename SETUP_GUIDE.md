# Si Gyi Studio — Upload / Style / Render

ဒီ version သည် local video editing app ဖြစ်ပြီး Gemini script generation နှင့် TTS audio generation မပါပါ။ Workflow သည် video upload → video style → local MP4 render သုံးဆင့်သာ ဖြစ်သည်။

## Run

```bash
pnpm install
pnpm run dev
```

Browser တွင် `http://localhost:3000` ကို ဖွင့်ပါ။ Production build အတွက် `pnpm run build` ကို သုံးပါ။

## Included behavior

Video ကို browser ထဲသို့ local file အဖြစ် upload လုပ်နိုင်သည်။ Existing style controls များဖြစ်သော 9:16 fit/fill, crop focus, flip, zoom, top/bottom blur, blur strength, brightness, contrast, saturation, color filters, frame color/width နှင့် optional logo overlay ကို ဆက်လက်အသုံးပြုနိုင်သည်။

Render သည် FFmpeg WASM ကို browser ထဲတွင် run ပြီး MP4 အဖြစ် export လုပ်သည်။ Original video audio ကို ထိန်းထားသည်။ Gemini API key, script editor, transcript, Edge TTS, voice picker နှင့် server-side TTS endpoint မပါပါ။

ဖုန်းတွင် short clip ဖြင့် အရင်စမ်းပါ။ 720p ကို ပုံမှန်သုံးရန် အကြံပြုသည်။ 1080p သည် device memory နှင့် ကြာချိန်ပိုလိုနိုင်သည်။

## Gemini WAV/SRT workflow (initial integration)

The upload step now includes a Gemini Canvas URL field/WebView area plus WAV and SRT import controls. SRT timestamps are parsed and used to burn subtitles; an imported WAV replaces the source audio during FFmpeg render and is padded/trimmed to the subtitle timeline. If Gemini blocks iframe embedding, open the Canvas in the app's native WebView once the Android wrapper and supplied URL are tested. Browser-only iframe downloads cannot be intercepted cross-origin, so native automatic download/import requires that final Android bridge integration.

For Android wrapper setup, see `ANDROID_APK_NEXT_STEPS.md`.
