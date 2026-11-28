package com.sigyi.recapstudio;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.activity.result.ActivityResult;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.StreamInformation;
import com.getcapacitor.*;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "NativeRender")
public class NativeRenderPlugin extends Plugin {
  private volatile boolean cancelled = false;
  private volatile FFmpegSession activeSession;
  private volatile boolean isRendering = false;
  private volatile long renderStart = 0, encodeStart = 0;
  private volatile double ratio = 0;
  private volatile boolean encoding = false;
  private volatile String stageText = "";
  private volatile String statsLine = "";
  private final ConcurrentLinkedDeque<String> logLines = new ConcurrentLinkedDeque<>();

  private void addLog(String s) {
    if (s == null) return;
    for (String line : s.split("[\\r\\n]+")) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (line.length() > 300) line = line.substring(0, 300);
      logLines.addLast(line);
      while (logLines.size() > 200) logLines.pollFirst();
    }
  }

  @PluginMethod public void pickVideo(PluginCall call) { pick(call, "video/*", "video"); }
  @PluginMethod public void pickAudio(PluginCall call) { pick(call, "audio/*", "audio"); }
  @PluginMethod public void pickTtsFile(PluginCall call) { pick(call, "audio/*", "tts"); }

  @PluginMethod public void pickLogo(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*");
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(call, i, "pickLogoResult");
  }

  @ActivityCallback
  private void pickLogoResult(PluginCall call, ActivityResult result) {
    if (call == null) return;
    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
      call.reject("Logo ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။"); return;
    }
    Uri uri = result.getData().getData();
    try {
      getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      String name = displayName(uri).replaceAll("[^A-Za-z0-9._-]", "_");
      File dir = new File(getContext().getFilesDir(), "media");
      if (!dir.exists() && !dir.mkdirs()) throw new Exception("Logo storage မဖန်တီးနိုင်ပါ");
      File target = new File(dir, "logo_" + System.currentTimeMillis() + "_" + name);
      try (InputStream in = getContext().getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
        if (in == null) throw new Exception("Logo ဖိုင်ဖတ်မရပါ");
        byte[] b = new byte[256 * 1024]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.getFD().sync();
      }
      JSObject r = new JSObject(); r.put("path", target.getAbsolutePath()); r.put("name", displayName(uri));
      r.put("size", target.length()); r.put("mimeType", getContext().getContentResolver().getType(uri)); call.resolve(r);
    } catch (Throwable t) { call.reject("Logo ဖိုင်ကို ကူးမရပါ: " + t.getMessage()); }
  }

  private void pick(PluginCall call, String mime, String kind) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(mime);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    String callback = "audio".equals(kind) ? "pickAudioResult" : "video".equals(kind) ? "pickVideoResult" : "pickTtsResult";
    startActivityForResult(call, i, callback);
  }

  @ActivityCallback private void pickVideoResult(PluginCall call, ActivityResult result) { handlePickResult(call, result, "video"); }
  @ActivityCallback private void pickAudioResult(PluginCall call, ActivityResult result) { handlePickResult(call, result, "audio"); }
  @ActivityCallback private void pickTtsResult(PluginCall call, ActivityResult result) { handlePickResult(call, result, "tts"); }

  private void handlePickResult(PluginCall call, ActivityResult result, String kind) {
    if (call == null) return;
    if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
      call.reject("ဖိုင်ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။"); return;
    }
    Uri uri = result.getData().getData();
    try {
      getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      String displayName = displayName(uri);
      if (("audio".equals(kind) || "tts".equals(kind)) && !(displayName.toLowerCase(Locale.US).endsWith(".mp3") || displayName.toLowerCase(Locale.US).endsWith(".wav"))) {
        throw new Exception("MP3 သို့မဟုတ် WAV ဖိုင်သာ ရွေးပါ။");
      }
      String name = displayName.replaceAll("[^A-Za-z0-9._-]", "_");
      File dir = new File(getContext().getFilesDir(), "media");
      if (!dir.exists() && !dir.mkdirs()) throw new Exception("App storage မဖန်တီးနိုင်ပါ");
      
      if (!"tts".equals(kind)) {
          File[] previous = dir.listFiles();
          if (previous != null) for (File old : previous) if (old.getName().startsWith(kind + "_")) old.delete();
      }
      
      File target = new File(dir, kind + "_" + System.currentTimeMillis() + "_" + name);
      try (InputStream in = getContext().getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
        if (in == null) throw new Exception("ဖိုင်ဖတ်မရပါ");
        byte[] b = new byte[1024 * 1024]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.getFD().sync();
      }
      JSObject r = new JSObject(); r.put("path", target.getAbsolutePath()); r.put("name", displayName(uri));
      r.put("size", target.length()); r.put("mimeType", getContext().getContentResolver().getType(uri)); call.resolve(r);
    } catch (Throwable t) { call.reject("ဖိုင်ကို app storage ထဲ ကူးမရပါ: " + t.getMessage()); }
  }

  private String displayName(Uri uri) {
    try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        if (n >= 0) return c.getString(n);
      }
    } catch (Throwable ignored) {}
    return "media";
  }

  // ===================== RENDER =====================

  private static class Job {
    File source, voice, logo, titleOverlay, out;
    JSObject style;
    boolean landscape;
    int w, h, fps, frames;
    double vDur, aDur, finalDur, k, pad;
    String res, hwDec;
    double[] anchA, anchV;   // anchor points (audio time -> video time)
    double videoDelay;       // manual fine-tune (sec): + = video later, - = video earlier
    String planInfo = "";
  }

  private static class VInfo { String codec; double fps; double dur; }
  private static Boolean cacheMc = null, cacheX264 = null;

  @PluginMethod public void render(PluginCall call) {
    if (isRendering) { call.reject("Render လုပ်ဆောင်နေဆဲဖြစ်ပါတယ်။"); return; }

    String vp = call.getString("videoPath"), ap = call.getString("audioPath"), lp = call.getString("logoPath");
    Boolean land = call.getBoolean("landscape", false);
    boolean isLandscape = land != null && land;

    if (vp == null || !new File(vp).isFile()) { call.reject("Video ဖိုင်မတွေ့ပါ။"); return; }
    if (ap != null && !new File(ap).isFile()) { call.reject("Audio ဖိုင်မတွေ့ပါ။"); return; }
    if (lp != null && !new File(lp).isFile()) { call.reject("Logo ဖိုင်မတွေ့ပါ။"); return; }

    cancelled = false;
    isRendering = true;
    new Thread(() -> renderWorker(call, new File(vp), ap == null ? null : new File(ap), lp == null ? null : new File(lp), isLandscape), "SiGyiNativeRender").start();
  }

  private void renderWorker(PluginCall call, File source, File voice, File logo, boolean isLandscape) {
    File work = new File(getContext().getCacheDir(), "sigyi_job_" + System.currentTimeMillis());
    if (!work.mkdirs()) { isRendering = false; call.reject("Cache folder မဖန်တီးနိုင်ပါ။"); return; }

    renderStart = System.currentTimeMillis();
    encodeStart = 0; ratio = 0; encoding = false; stageText = "ပြင်ဆင်နေပါတယ်...";
    ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    ticker.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);   // အချိန်ကို တစ်စက္ကန့်တစ်ခါ မဖြစ်မနေ ပို့မယ်

    File out = new File(getContext().getCacheDir(), "sigyi_preview_final.mp4");

    try {
      if (out.exists()) out.delete();

      VInfo vi = probeVideoInfo(source);
      double vDur = vi.dur > 0.5 ? vi.dur : probe(source);   // video stream ကိုယ်တိုင်ရဲ့ ကြာချိန် (source ထဲ အသံ track ပိုရှည်နေရင် format duration မှားတတ်လို့)
      VoiceInfo vinfo = voice != null ? analyzeVoice(voice) : null;
      double aDur = vinfo != null ? vinfo.dur : vDur;   // mp3 VBR မှာ probe က မှားတတ်လို့ decode ပြီး တိတိကျကျ တိုင်း
      if (vDur <= 0) throw new Exception("Video duration မဖတ်နိုင်ပါ။");
      if (aDur <= 0) throw new Exception("Audio duration မဖတ်နိုင်ပါ။");

      Job j = new Job();
      j.source = source; j.voice = voice; j.logo = logo; j.out = out; j.landscape = isLandscape;
      j.vDur = vDur; j.aDur = aDur; j.finalDur = aDur;       // နောက်ဆုံး ကြာချိန် = အသံကြာချိန် (အသံက အဓိက)
      j.fps = pickFps(vi.fps);
      j.res = call.getString("resolution", "720p");
      if ("360p".equals(j.res)) { j.w = isLandscape ? 640 : 360; j.h = isLandscape ? 360 : 640; }
      else if ("1080p".equals(j.res)) { j.w = isLandscape ? 1920 : 1080; j.h = isLandscape ? 1080 : 1920; }
      else { j.w = isLandscape ? 1280 : 720; j.h = isLandscape ? 720 : 1280; }
      j.frames = (int) Math.max(1, Math.ceil(j.finalDur * j.fps));

      JSObject style = call.getObject("style"); if (style == null) style = new JSObject();
      j.style = style;

      // ---- Anchor များ (အသံအချိန် → video အချိန်) ပေးထားရင် အပိုင်းလိုက် တိတိကျကျ ညှိမယ် ----
      JSArray anchorArr = call.getArray("anchors");
      if (anchorArr == null) { try { anchorArr = style.has("anchors") ? new JSArray(style.getJSONArray("anchors").toString()) : null; } catch (Throwable ignored) {} }
      if (anchorArr != null && voice != null) buildAnchors(j, anchorArr);

      // ---- Video ကို အသံနဲ့ ညှိမယ့် နည်းလမ်း (အသံကို လုံးဝ မထိဘူး) ----
      // stretch = ဗီဒီယိုအကုန်လုံးကို အသံကြာချိန်နဲ့ အတူတူ ဖြစ်အောင် မြန်/နှေး ညှိမယ် (default)
      // trim    = ဗီဒီယို 1x နဲ့ပဲ ပြမယ်၊ ရှည်ရင် ဖြတ် (freeze frame မသုံး)
      // smart   = အသံထဲက အပုဒ်(pause)တစ်ခုချင်းစီကို ရှာ → video ကို အပုဒ်လိုက် အနှေးအမြန်ညှိ + scene cut နဲ့ ကပ် (default)
      // stretch = video တစ်ခုလုံးကို အညီအမျှ ဆွဲ
      // trim    = 1x နဲ့ပဲ ပြ
      String syncMode = call.getString("syncMode", style.optString("syncMode", "smart"));
      Double vd = call.getDouble("videoDelay", style.optDouble("videoDelay", 0));
      j.videoDelay = vd == null ? 0 : clamp(vd, -3, 3);
      j.k = 1.0; j.pad = 0;
      if (voice != null) {
        if ("trim".equals(syncMode)) {
          j.pad = 0; // freeze frame မသုံး
        } else {
          j.k = aDur / vDur;       // output time = source time * k  (smart plan မရရင် ဒီ fallback ကို သုံး)
        }
      }

      String titleText = style.optString("titleText", "");
      if (!titleText.trim().isEmpty()) j.titleOverlay = createTitleOverlay(work, titleText, style, j.w, j.h);

      logLines.clear(); statsLine = "";
      Boolean hwOpt = call.getBoolean("hardware", true);
      boolean tryHw = hwOpt == null || hwOpt.booleanValue();
      j.hwDec = tryHw ? hwDecoderFor(vi.codec) : null;
      addLog("Video " + fmt(vDur) + "s | Audio " + fmt(aDur) + "s | " + j.w + "x" + j.h + " | speed k=" + String.format(Locale.US, "%.4f", j.k) + " | frames=" + j.frames + " | fps=" + j.fps + " | anchors=" + (j.anchA == null ? 0 : j.anchA.length - 1));

      // ---- encoder/decoder ရနိုင်မရနိုင် အရင်စစ် (စက္ကန့်အနည်းငယ်ပဲ) ----
      stageText = "Encoder စစ်နေပါတယ်...";
      String src = source.getAbsolutePath();
      if (tryHw && (cacheMc == null || !cacheMc)) cacheMc = quickOk("-y", "-hide_banner", "-i", src, "-t", "0.4", "-an", "-vf", "scale=320:240", "-pix_fmt", "yuv420p", "-c:v", "h264_mediacodec", "-b:v", "1M", "-f", "null", "-");
      boolean mcOk = tryHw && cacheMc != null && cacheMc;
      if (cacheX264 == null || !cacheX264) cacheX264 = quickOk("-y", "-hide_banner", "-i", src, "-t", "0.4", "-an", "-vf", "scale=320:240", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast", "-f", "null", "-");
      boolean x264Ok = cacheX264 != null && cacheX264;
      boolean hwDecOk = tryHw && j.hwDec != null && quickOk("-y", "-hide_banner", "-c:v", j.hwDec, "-i", src, "-t", "0.5", "-an", "-f", "null", "-");
      addLog("Check: mediacodec-enc=" + mcOk + " | libx264=" + x264Ok + " | hw-decode(" + j.hwDec + ")=" + hwDecOk);

      // ---- အပုဒ်စီ ညှိမယ့် plan ----
      if (voice != null && j.anchA == null && ("smart".equals(syncMode))) {
        stageText = "အပုဒ်တိုင်း အသံနဲ့ ညှိနေပါတယ်...";
        try { buildSmartPlan(j, call, vinfo, hwDecOk ? j.hwDec : null); }
        catch (Throwable t) { addLog("Smart plan မရ → stretch နဲ့ အစားထိုး: " + t.getMessage()); j.anchA = null; j.anchV = null; }
        if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
      }

      List<String> encs = new ArrayList<>();
      if (mcOk) encs.add("h264_mediacodec");
      if (x264Ok) encs.add("libx264");
      encs.add("mpeg4");
      List<String[]> attempts = new ArrayList<>();
      if (hwDecOk) attempts.add(new String[]{"hw", encs.get(0)});
      for (String e : encs) attempts.add(new String[]{"sw", e});

      boolean ok = false; String lastLogs = "";
      for (int ai = 0; ai < attempts.size() && !ok; ai++) {
        String decMode = attempts.get(ai)[0], enc = attempts.get(ai)[1];
        ratio = 0; encoding = true; encodeStart = System.currentTimeMillis();
        stageText = "Render လုပ်နေပါတယ် [decode:" + decMode + " | encode:" + enc + "]...";
        addLog("▶ Attempt " + (ai + 1) + "/" + attempts.size() + ": decode=" + decMode + " encode=" + enc);
        FFmpegSession s = encode(j, enc, "hw".equals(decMode));
        ok = s != null && ReturnCode.isSuccess(s.getReturnCode());
        if (!ok) {
          if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
          lastLogs = tail(s);
          addLog("✖ မအောင်မြင်ပါ → နောက်တစ်နည်း စမ်းမယ်");
          if (j.out.exists()) j.out.delete();
        }
      }
      if (!ok) throw new Exception("Render မအောင်မြင်ပါ။\n" + lastLogs);

      // ပထမ pass ပြီးနောက် stream duration ကို တကယ်စစ်ပါ။ Smart piecewise output
      // က audio master နဲ့ မကိုက်လျှင် global stretch fallback တစ်ကြိမ် ပြန် render လုပ်ပါ။
      double[] measured = readOutputDurations(out);
      double tolerance = Math.max(0.12, 2.0 / Math.max(1, j.fps));
      boolean durationMismatch = measured[0] <= 0 || measured[1] <= 0
          || Math.abs(measured[0] - j.finalDur) > tolerance
          || Math.abs(measured[1] - j.finalDur) > tolerance;
      if (j.anchA != null && durationMismatch) {
        addLog(String.format(Locale.US,
            "Duration validation မအောင်: video=%.3fs target=%.3fs → uniform fallback ပြန် render", measured[0], j.finalDur));
        j.anchA = null; j.anchV = null;
        j.k = j.finalDur / j.vDur;
        j.planInfo += " | piecewise duration mismatch → uniform fallback";
        boolean fallbackOk = false; String fallbackLogs = "";
        for (int ai = 0; ai < attempts.size() && !fallbackOk; ai++) {
          String decMode = attempts.get(ai)[0], enc = attempts.get(ai)[1];
          if (out.exists()) out.delete();
          ratio = 0; encoding = true; encodeStart = System.currentTimeMillis();
          stageText = "Fallback Render လုပ်နေပါတယ် [" + enc + "]...";
          FFmpegSession fs = encode(j, enc, "hw".equals(decMode));
          fallbackOk = fs != null && ReturnCode.isSuccess(fs.getReturnCode());
          if (!fallbackOk) fallbackLogs = tail(fs);
        }
        if (!fallbackOk) throw new Exception("Fallback render မအောင်မြင်ပါ။\n" + fallbackLogs);
        measured = readOutputDurations(out);
      }

      encoding = false; ratio = 1; stageText = "Movies folder ထဲ သိမ်းနေပါတယ်…";
      String syncReport = (j.planInfo.isEmpty() ? "" : j.planInfo + "\n") + verifyOutput(out);
      Uri saved = saveMovies(out);

      long elapsedMs = System.currentTimeMillis() - renderStart;
      ticker.shutdownNow();
      JSObject result = new JSObject();
      result.put("savedUri", saved.toString()); result.put("previewPath", out.getAbsolutePath());
      result.put("name", "SiGyi_Recap.mp4"); result.put("size", out.length());
      result.put("elapsed", fmtTime(elapsedMs)); result.put("elapsedMs", elapsedMs); result.put("syncReport", syncReport);
      result.put("duration", fmtTime((long) (j.finalDur * 1000)));
      JSObject d = new JSObject(); d.put("progress", 100); d.put("stage", "Render ပြီးပါပြီ");
      d.put("elapsed", fmtTime(elapsedMs)); d.put("remaining", fmtTime(0)); d.put("elapsedMs", elapsedMs); d.put("remainingMs", 0); d.put("stats", statsLine); d.put("log", recentLog());
      notifyListeners("renderProgress", d);
      isRendering = false;
      call.resolve(result);

    } catch (Throwable t) {
      ticker.shutdownNow();
      if (out.exists()) out.delete();
      isRendering = false;
      call.reject(t.getMessage() == null ? "Render မအောင်မြင်ပါ" : t.getMessage());
    } finally {
      ticker.shutdownNow();
      deleteTree(work); activeSession = null; isRendering = false;
    }
  }

  /** Render တစ်ခါတည်း (chunk မခွဲ၊ အသံ ပြန်မဖြတ်) → A/V drift မဖြစ်တော့ဘူး */
  private FFmpegSession encode(Job j, String encoder, boolean hwDecode) throws Exception {
    List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner"));
    if (hwDecode && j.hwDec != null) a.addAll(Arrays.asList("-c:v", j.hwDec));
    a.addAll(Arrays.asList("-i", j.source.getAbsolutePath()));
    int next = 1;
    if (j.voice != null) { a.addAll(Arrays.asList("-i", j.voice.getAbsolutePath())); next = 2; }
    int logoInput = -1, titleInput = -1;
    if (j.logo != null) { logoInput = next++; a.addAll(Arrays.asList("-i", j.logo.getAbsolutePath())); }
    if (j.titleOverlay != null) { titleInput = next++; a.addAll(Arrays.asList("-i", j.titleOverlay.getAbsolutePath())); }

    String graph = buildFilterGraph(j, logoInput, titleInput);
    a.addAll(Arrays.asList("-filter_complex", graph, "-map", "[vout]"));

    // Audio ကို master timeline အဖြစ်ထားမယ်။
    // PTS origin ကို 0 မှစပြီး 48 kHz တစ်သမတ်တည်းပြောင်း၊ နောက်ဆုံး duration ကို
    // audio master duration နဲ့ တိတိကျကျ ကန့်သတ်မယ်။ atempo မသုံးသောကြောင့် အသံအမြန်နှုန်း မပြောင်းပါ။
    String audioFilter = "asetpts=PTS-STARTPTS,aresample=48000,apad,atrim=duration="
        + String.format(Locale.US, "%.6f", j.finalDur);
    if (j.voice != null) a.addAll(Arrays.asList("-map", "1:a:0", "-af", audioFilter,
        "-c:a", "aac", "-b:a", "192k", "-ar", "48000"));
    else a.addAll(Arrays.asList("-map", "0:a:0?", "-af", audioFilter,
        "-c:a", "aac", "-b:a", "128k", "-ar", "48000"));

    if ("h264_mediacodec".equals(encoder)) {
      String br = "360p".equals(j.res) ? "1500k" : "1080p".equals(j.res) ? "8000k" : "4000k";
      a.addAll(Arrays.asList("-c:v", "h264_mediacodec", "-b:v", br));
    } else if ("libx264".equals(encoder)) {
      a.addAll(Arrays.asList("-c:v", "libx264", "-preset", "ultrafast", "-crf", "27"));
    } else {
      int th = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
      a.addAll(Arrays.asList("-c:v", "mpeg4", "-q:v", "5", "-me_method", "zero", "-g", "60", "-threads", String.valueOf(th)));
    }
    // Output ကို audio master duration မှာပဲ အဆုံးသတ်မယ်။
    // fps filter က CFR ထုတ်ပြီး ceil(frame count) က နောက်ဆုံး frame မလျော့စေဖို့ ကာကွယ်တယ်။
    a.addAll(Arrays.asList("-pix_fmt", "yuv420p", "-r", String.valueOf(j.fps),
        "-frames:v", String.valueOf(j.frames), "-t",
        String.format(Locale.US, "%.6f", j.finalDur), "-movflags", "+faststart",
        j.out.getAbsolutePath()));

    return runFfmpeg(a.toArray(new String[0]), j.frames);
  }

  private FFmpegSession runFfmpeg(String[] args, int totalFrames) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    final FFmpegSession[] result = {null};
    FFmpegSession s = FFmpegKit.executeWithArgumentsAsync(args, session -> { result[0] = session; done.countDown(); }, log -> addLog(log.getMessage()), stats -> {
      if (totalFrames > 0) ratio = Math.max(0.0, Math.min(1.0, stats.getVideoFrameNumber() / (double) totalFrames));
      statsLine = String.format(Locale.US, "frame %d/%d | %.1f fps | speed %.2fx", stats.getVideoFrameNumber(), totalFrames, stats.getVideoFps(), stats.getSpeed());
    });
    activeSession = s;
    while (!done.await(300, TimeUnit.MILLISECONDS)) { if (cancelled) FFmpegKit.cancel(); }
    if (result[0] == null) throw new Exception("FFmpeg session error");
    return result[0];
  }

  private String recentLog() {
    StringBuilder lg = new StringBuilder();
    Object[] arr = logLines.toArray();
    for (int i = Math.max(0, arr.length - 14); i < arr.length; i++) lg.append(arr[i]).append("\n");
    return lg.toString();
  }

  /** အသံကို decode လုပ်ပြီး တကယ့် ကြာချိန်ကို တိုင်း (header မှာ မှားနေတတ်တဲ့ mp3 VBR အတွက်) */
  private double exactDuration(File f) throws Exception {
    double p = probe(f), d = 0;
    try {
      FFmpegSession s = FFmpegKit.executeWithArguments(new String[]{"-hide_banner", "-i", f.getAbsolutePath(), "-vn", "-sn", "-dn", "-f", "null", "-"});
      String logs = s.getAllLogsAsString();
      if (logs != null) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("time=(\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)").matcher(logs);
        while (m.find()) d = Integer.parseInt(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0 + Double.parseDouble(m.group(3));
      }
    } catch (Throwable ignored) {}
    addLog("Audio duration: probe=" + fmt(p) + "s | decoded=" + fmt(d) + "s");
    return d > 0 ? d : p;
  }

  private VInfo probeVideoInfo(File f) {
    VInfo vi = new VInfo(); vi.fps = 0;
    try {
      CountDownLatch latch = new CountDownLatch(1);
      FFprobeKit.getMediaInformationAsync(f.getAbsolutePath(), s -> {
        try {
          if (s.getMediaInformation() != null) for (StreamInformation si : s.getMediaInformation().getStreams())
            if ("video".equals(si.getType())) {
              vi.codec = si.getCodec();
              try { String sd = si.getStringProperty("duration"); if (sd != null) vi.dur = Double.parseDouble(sd); } catch (Throwable ignored2) {}
              String r = si.getAverageFrameRate();
              if (r != null && r.contains("/")) { String[] x = r.split("/"); double den = Double.parseDouble(x[1]); if (den > 0) vi.fps = Double.parseDouble(x[0]) / den; }
              else if (r != null) vi.fps = Double.parseDouble(r);
              break;
            }
        } catch (Throwable ignored) {}
        latch.countDown();
      });
      latch.await(30, TimeUnit.SECONDS);
    } catch (Throwable ignored) {}
    return vi;
  }

  /** Source fps ကို လိုက်သုံး (24–30 အတွင်း) → မလိုတဲ့ frame ထပ်မ encode ရ */
  private int pickFps(double src) {
    if (src <= 0) return 30;
    int r = (int) Math.round(src);
    return Math.max(24, Math.min(30, r));
  }

  private void buildAnchors(Job j, JSArray arr) {
    List<double[]> pts = new ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      org.json.JSONObject o = arr.optJSONObject(i);
      if (o == null) continue;
      double a = o.optDouble("audio", -1), v = o.optDouble("video", -1);
      if (a < 0 || v < 0 || a > j.aDur || v > j.vDur) continue;
      pts.add(new double[]{a, v});
    }
    if (pts.isEmpty()) return;
    Collections.sort(pts, (x, y) -> Double.compare(x[0], y[0]));
    List<double[]> clean = new ArrayList<>();
    double[] last;
    if (pts.get(0)[0] <= 0.001) { last = new double[]{0, pts.get(0)[1]}; pts.remove(0); } else last = new double[]{0, 0};
    clean.add(last);
    for (double[] p : pts) {
      if (p[0] - last[0] >= 0.05 && p[1] - last[1] >= 0.05) { clean.add(p); last = p; }   // နှစ်ခုလုံး တိုးတက်နေမှ သုံး
    }
    if (j.aDur - last[0] >= 0.05 && j.vDur - last[1] >= 0.05) clean.add(new double[]{j.aDur, j.vDur});
    if (clean.size() < 2) return;
    if (clean.size() > 400) return;     // command ရှည်လွန်းမှာစိုးလို့
    j.anchA = new double[clean.size()]; j.anchV = new double[clean.size()];
    for (int i = 0; i < clean.size(); i++) { j.anchA[i] = clean.get(i)[0]; j.anchV[i] = clean.get(i)[1]; }
  }

  // ===================== SMART SENTENCE SYNC =====================

  private static class VoiceInfo { double dur; List<double[]> silences = new ArrayList<>(); }   // silence = {start, end, duration}

  private double opt(PluginCall call, JSObject style, String key, double def) {
    Double v = call.getDouble(key);
    return v != null ? v : style.optDouble(key, def);
  }
  private boolean optBool(PluginCall call, JSObject style, String key, boolean def) {
    Boolean v = call.getBoolean(key);
    return v != null ? v : style.optBoolean(key, def);
  }

  /** အသံကို တစ်ခါတည်း decode → တကယ့်ကြာချိန် + အပုဒ်ကြား pause တွေကို တစ်ပြိုင်တည်း ရှာ (အပို အချိန်မကုန်) */
  private VoiceInfo analyzeVoice(File f) throws Exception {
    VoiceInfo vi = new VoiceInfo();
    double p = probe(f), d = 0;
    try {
      FFmpegSession s = FFmpegKit.executeWithArguments(new String[]{"-hide_banner", "-i", f.getAbsolutePath(), "-vn", "-sn", "-dn",
          "-af", "silencedetect=noise=-32dB:d=0.15", "-f", "null", "-"});
      String logs = s.getAllLogsAsString();
      if (logs != null) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("time=(\\d+):(\\d\\d):(\\d\\d(?:\\.\\d+)?)").matcher(logs);
        while (m.find()) d = Integer.parseInt(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0 + Double.parseDouble(m.group(3));
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("silence_end:\\s*(\\d+(?:\\.\\d+)?)\\s*\\|\\s*silence_duration:\\s*(\\d+(?:\\.\\d+)?)").matcher(logs);
        while (sm.find()) {
          double end = Double.parseDouble(sm.group(1)), du = Double.parseDouble(sm.group(2));
          vi.silences.add(new double[]{end - du, end, du});
        }
      }
    } catch (Throwable ignored) {}
    vi.dur = d > 0 ? d : p;
    addLog("Audio duration: probe=" + fmt(p) + "s | decoded=" + fmt(d) + "s | pauses=" + vi.silences.size());
    return vi;
  }

  /** Scene cut (ရုပ်ရှင်ရဲ့ cut) အချိန်တွေ ရှာ — အပုဒ်ပြောင်းတဲ့နေရာမှာ video cut နဲ့ ကပ်ဖို့ */
  private List<Double> detectScenes(Job j, String hwDec) {
    List<Double> cuts = new ArrayList<>();
    String vf = "setpts=PTS-STARTPTS,fps=8,scale=192:-2:flags=fast_bilinear,select='gt(scene,0.30)',showinfo";
    String logs = null;
    for (int attempt = 0; attempt < 2 && logs == null && !cancelled; attempt++) {
      boolean hw = attempt == 0 && hwDec != null;
      if (attempt == 1 && hwDec == null) break;
      List<String> a = new ArrayList<>(Arrays.asList("-hide_banner", "-skip_loop_filter", "all"));
      if (hw) a.addAll(Arrays.asList("-c:v", hwDec));
      a.addAll(Arrays.asList("-i", j.source.getAbsolutePath(), "-an", "-sn", "-dn", "-vf", vf, "-f", "null", "-"));
      try {
        FFmpegSession s = FFmpegKit.executeWithArguments(a.toArray(new String[0]));
        if (s != null && ReturnCode.isSuccess(s.getReturnCode())) logs = s.getAllLogsAsString();
      } catch (Throwable ignored) {}
    }
    if (logs == null) { addLog("Scene detect မရပါ → cut မကပ်ဘဲ ညှိမယ်"); return cuts; }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("pts_time:(\\d+(?:\\.\\d+)?)").matcher(logs);
    double last = -1;
    while (m.find()) {
      double t = Double.parseDouble(m.group(1));
      if (t > 0.2 && t < j.vDur - 0.2 && t - last >= 0.25) { cuts.add(t); last = t; }
    }
    Collections.sort(cuts);
    addLog("Scene cuts: " + cuts.size());
    return cuts;
  }

  /** အပုဒ်ကြား ဖြတ်မယ့် အချိန်တွေ (audio time) ကို အနည်းဆုံး minSeg ရှိအောင် ပေါင်း၊ ၁၆၀ ထက် မကျော်စေ */
  private List<Double> mergeBounds(List<Double> raw, double aDur, double minSeg) {
    Collections.sort(raw);
    List<Double> out = new ArrayList<>();
    for (int guard = 0; guard < 12; guard++) {
      out.clear(); double last = 0;
      for (double b : raw) if (b - last >= minSeg && aDur - b >= Math.min(0.8, minSeg)) { out.add(b); last = b; }
      if (out.size() <= 160) break;
      minSeg *= 1.5;
    }
    return out;
  }

  /**
   * အပုဒ်တစ်ခုချင်းစီအတွက်: အသံအပုဒ်ကြာချိန် A → video source အပိုင်း S ကို ရွေး (speed = S/A)။
   * - အလုံးစုံ speed ကို global line (v = r × audio) ပေါ်မှာ ထိန်း → drift မစုဘူး
   * - အပုဒ်စပြောင်းတဲ့ နေရာကို အနီးဆုံး scene cut နဲ့ ကပ် (speed ကို min..max ကြားမှာပဲ)
   * - ရလာတဲ့ (audio→video) anchor တွေကို setpts piecewise နဲ့ ပေးမယ်
   */
  private void buildSmartPlan(Job j, PluginCall call, VoiceInfo vinfo, String hwDec) throws Exception {
    JSObject style = j.style;
    double aDur = j.aDur, vDur = j.vDur;

    // 1) အပုဒ်နယ်နိမိတ် (audio time)
    List<Double> raw = new ArrayList<>(); String srcKind = "pause";
    JSArray segArr = call.getArray("segments");
    if (segArr == null) { try { segArr = style.has("segments") ? new JSArray(style.getJSONArray("segments").toString()) : null; } catch (Throwable ignored) {} }
    if (segArr != null && segArr.length() > 0) {
      double acc = 0;
      for (int i = 0; i < segArr.length(); i++) {
        Object o = segArr.opt(i); double end = -1;
        if (o instanceof Number) end = ((Number) o).doubleValue();
        else if (o instanceof org.json.JSONObject) {
          org.json.JSONObject jo = (org.json.JSONObject) o;
          end = jo.optDouble("end", -1);
          if (end < 0) { double du = jo.optDouble("duration", -1); if (du > 0) end = acc + du; }
        }
        if (end > 0 && end < aDur) { raw.add(end); acc = end; }
      }
      srcKind = "segments";
    } else if (vinfo != null) {
      double minPause = clamp(opt(call, style, "minPause", 0.22), 0.1, 1.5);
      for (double[] sl : vinfo.silences) if (sl[2] >= minPause) raw.add((sl[0] + sl[1]) / 2.0);   // pause အလယ်မှာ ဖြတ်
    }
    // Scene cut များကိုလည်း audio timeline ပေါ်သို့ global ratio ဖြင့် ခန့်မှန်းချိတ်ပါ။
    // Pause မရှိတဲ့ narration တစ်ခုလုံးကို single stretch လုပ်မသွားစေဘဲ scene အလိုက် speed ချိန်နိုင်စေသည်။
    boolean explicitBase = call.getDouble("baseSpeed") != null || style.has("baseSpeed");
    double globalRatio = explicitBase
        ? clamp(opt(call, style, "baseSpeed", vDur / aDur), 0.20, 2.0)
        : clamp(vDur / aDur, 0.20, 2.0);
    List<Double> cuts = new ArrayList<>();
    if (optBool(call, style, "snapScenes", true)) {
      stageText = "Scene cut ရှာနေပါတယ်...";
      cuts = detectScenes(j, hwDec);
      stageText = "အပုဒ်တိုင်း အသံနဲ့ ညှိနေပါတယ်...";
      for (double c : cuts) {
        double audioAt = c / globalRatio;
        if (audioAt > 0.5 && audioAt < aDur - 0.5) raw.add(audioAt);
      }
    }
    List<Double> bounds = mergeBounds(raw, aDur, clamp(opt(call, style, "minSegment", 1.2), 0.5, 10));
    if (bounds.isEmpty()) { addLog("အသံ pause/scene boundary မတွေ့ပါ → whole-video retime သုံးမယ်"); return; }
    int n = bounds.size() + 1;
    double[] a = new double[n + 1];
    a[0] = 0; for (int i = 0; i < bounds.size(); i++) a[i + 1] = bounds.get(i); a[n] = aDur;

    // 2) speed ကန့်သတ်ချက်
    // Speed = source video seconds / narration seconds.
    // Burmese narration is often longer: allow each scene to slow down to 0.30x by default.
    // Do not clamp the global ratio to 0.5; very long narration may require slower retiming.
    double r = globalRatio;
    double minSp = Math.min(clamp(opt(call, style, "minSpeed", 0.30), 0.20, 1.0), r * 0.85);
    double maxSp = Math.max(clamp(opt(call, style, "maxSpeed", 1.50), 1.0, 2.0), r * 1.15);
    double window = clamp(opt(call, style, "snapWindow", 1.5), 0, 4);

    // 3) video scene cut များကို အပေါ်တွင်ရှာပြီး audio boundary နှင့် ချိတ်ထားသည်။

    // 4) video source boundary တွက်
    double[] v = new double[n + 1]; v[0] = 0;
    int snapped = 0; double spMin = 99, spMax = 0;
    for (int i = 1; i <= n; i++) {
      double A = a[i] - a[i - 1];
      double lo = v[i - 1] + minSp * A, hi = v[i - 1] + maxSp * A;
      double ideal = Math.max(lo, Math.min(hi, r * a[i]));
      double pick = ideal;
      if (i < n && !cuts.isEmpty()) {
        double best = Double.MAX_VALUE;
        for (double c : cuts) {
          if (c < lo || c > hi || Math.abs(c - ideal) > window) continue;
          double dist = Math.abs(c - ideal);
          if (dist < best) { best = dist; pick = c; }
        }
        if (best != Double.MAX_VALUE) snapped++;
      }
      double remA = aDur - a[i];
      double capTop = vDur - minSp * remA;                         // ကျန်အပုဒ်တွေအတွက် source မကုန်သွားအောင်
      if (i < n && capTop >= lo) pick = Math.min(pick, capTop);
      if (!explicitBase && i < n) { double floor = vDur - maxSp * remA; if (floor <= hi) pick = Math.max(pick, floor); }   // video အဆုံးထိ ရောက်အောင်
      pick = Math.min(pick, vDur - 0.05 * (n - i));
      pick = Math.max(pick, v[i - 1] + 0.05);
      v[i] = pick;
      double sp = (v[i] - v[i - 1]) / A;
      spMin = Math.min(spMin, sp); spMax = Math.max(spMax, sp);
    }
    if (v[n] > vDur + 0.02) { addLog("Plan မဖြစ်နိုင်ပါ (video တိုလွန်း) → stretch"); return; }

    // 5) anchor များ သတ်မှတ် (နှစ်ဘက်လုံး တိုးနေမှ)
    List<double[]> clean = new ArrayList<>(); clean.add(new double[]{0, 0});
    for (int i = 1; i <= n; i++) {
      double[] lastP = clean.get(clean.size() - 1);
      if (a[i] - lastP[0] >= 0.05 && v[i] - lastP[1] >= 0.05) clean.add(new double[]{a[i], v[i]});
    }
    if (clean.size() < 2) return;
    clean.get(clean.size() - 1)[0] = aDur;       // နောက်ဆုံး anchor = အသံအဆုံး အတိအကျ
    j.anchA = new double[clean.size()]; j.anchV = new double[clean.size()];
    for (int i = 0; i < clean.size(); i++) { j.anchA[i] = clean.get(i)[0]; j.anchV[i] = clean.get(i)[1]; }

    j.planInfo = String.format(Locale.US, "Smart sync: %d အပုဒ် (%s) | scene-snap %d/%d | speed %.2fx–%.2fx (base %.2fx) | video သုံး %.1f/%.1fs",
        n, srcKind, snapped, Math.max(0, n - 1), spMin, spMax, r, v[n], vDur);
    addLog(j.planInfo);
  }

  /** setpts expression: source အချိန် T → output အချိန် f(T) (အပိုင်းလိုက် linear) */
  private String buildPiecewisePts(Job j) {
    StringBuilder e = new StringBuilder("setpts='(");
    boolean first = true;
    double base = j.anchA[0];
    for (int i = 1; i < j.anchA.length; i++) {
      double va = j.anchV[i - 1], vb = j.anchV[i], len = vb - va;
      double k = (j.anchA[i] - j.anchA[i - 1]) / len;
      if (!first) e.append("+");
      first = false;
      e.append(String.format(Locale.US, "max(0,min(%.6f,(PTS-STARTPTS)*TB-%.6f))*%.8f", len, va, k));
    }
    e.append(String.format(Locale.US, "+%.6f)/TB'", base));
    return e.toString();
  }

  /** FFprobe ဖြင့် output video/audio stream ကြာချိန်ကို တိုင်း */
  private double[] readOutputDurations(File f) {
    double v = 0, a = 0;
    try {
      com.arthenica.ffmpegkit.MediaInformationSession ms = FFprobeKit.getMediaInformation(f.getAbsolutePath());
      if (ms != null && ms.getMediaInformation() != null) {
        for (StreamInformation si : ms.getMediaInformation().getStreams()) {
          String d = si.getStringProperty("duration");
          if (d == null) continue;
          if ("video".equals(si.getType())) v = Double.parseDouble(d);
          else if ("audio".equals(si.getType())) a = Double.parseDouble(d);
        }
      }
    } catch (Throwable t) { addLog("Duration probe သတိပေးချက်: " + t.getMessage()); }
    return new double[]{v, a};
  }

  /** Render ပြီးနောက် output ထဲက video/audio ကြာချိန် နှိုင်းယှဉ်စစ် */
  private String verifyOutput(File f) {
    double[] d = readOutputDurations(f); double v = d[0], a = d[1];
    String r = String.format(Locale.US,
        "Output check: video=%.3fs | audio=%.3fs | target=%.3fs | A/V diff=%dms",
        v, a, a, Math.round(Math.abs(v - a) * 1000));
    addLog(r); return r;
  }

  private boolean quickOk(String... args) {
    try { FFmpegSession s = FFmpegKit.executeWithArguments(args); return s != null && ReturnCode.isSuccess(s.getReturnCode()); }
    catch (Throwable t) { return false; }
  }

  private String tail(FFmpegSession s) {
    if (s == null) return "";
    String l = s.getAllLogsAsString();
    if (l == null) return "";
    return l.length() > 1800 ? l.substring(l.length() - 1800) : l;
  }

  /** တစ်စက္ကန့်တစ်ခါ ကြာချိန် + ကျန်ချိန် ပို့ပေးတယ် */
  private void tick() {
    try {
      long now = System.currentTimeMillis();
      long elapsed = now - renderStart;
      int pct; long remMs = -1;
      double r = ratio;
      if (encoding) {
        pct = 3 + (int) (r * 95);
        if (r > 0.01) remMs = (long) ((now - encodeStart) * (1.0 - r) / r);
      } else pct = 1;
      JSObject d = new JSObject();
      d.put("progress", Math.min(98, pct)); d.put("stage", stageText);
      d.put("elapsed", fmtTime(elapsed)); d.put("elapsedMs", elapsed);
      d.put("remaining", remMs >= 0 ? fmtTime(remMs) : "တွက်ချက်နေပါတယ်..."); d.put("remainingMs", remMs);
      d.put("stats", statsLine);
      d.put("log", recentLog());
      notifyListeners("renderProgress", d);
    } catch (Throwable ignored) {}
  }

  private String fmtTime(long ms) {
    long s = Math.max(0, ms) / 1000, hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
    if (hh > 0) return String.format(Locale.US, "%d နာရီ %02d မိနစ် %02d စက္ကန့်", hh, mm, ss);
    return String.format(Locale.US, "%02d မိနစ် %02d စက္ကန့်", mm, ss);
  }

  private double probe(File f) throws Exception {
    CountDownLatch latch = new CountDownLatch(1); final double[] d = {0};
    FFprobeKit.getMediaInformationAsync(f.getAbsolutePath(), s -> {
      try { if (s.getMediaInformation() != null) d[0] = Double.parseDouble(s.getMediaInformation().getDuration()); }
      catch (Throwable ignored) {}
      latch.countDown();
    });
    latch.await(30, TimeUnit.SECONDS); return d[0];
  }

  private String probeVideoCodec(File f) {
    try {
      CountDownLatch latch = new CountDownLatch(1); final String[] c = {null};
      FFprobeKit.getMediaInformationAsync(f.getAbsolutePath(), s -> {
        try {
          if (s.getMediaInformation() != null) for (StreamInformation si : s.getMediaInformation().getStreams())
            if ("video".equals(si.getType())) { c[0] = si.getCodec(); break; }
        } catch (Throwable ignored) {}
        latch.countDown();
      });
      latch.await(30, TimeUnit.SECONDS); return c[0];
    } catch (Throwable t) { return null; }
  }

  private String hwDecoderFor(String codec) {
    if (codec == null) return null;
    switch (codec) {
      case "h264": return "h264_mediacodec";
      case "hevc": return "hevc_mediacodec";
      case "vp9": return "vp9_mediacodec";
      case "mpeg4": return "mpeg4_mediacodec";
      default: return null;
    }
  }

  private String buildFilterGraph(Job j, int logoInput, int titleInput) {
    JSObject s = j.style; int w = j.w, h = j.h;
    StringBuilder g = new StringBuilder("[0:v]").append(videoFilter(j)).append("[base];");
    String current = "base";

    // ✅ Black box + Blur (gblur သုံးတယ် → boxblur လို radius error မရှိဘူး)
    if (s.optBoolean("blurEnabled", false)) {
      int bx = even((int) (w * clamp(s.optDouble("blurX", 8), 0, 90) / 100.0));
      int by = even((int) (h * clamp(s.optDouble("blurY", 72), 0, 90) / 100.0));
      int bw = even((int) (w * clamp(s.optDouble("blurW", 84), 5, 100) / 100.0));
      int bh = even((int) (h * clamp(s.optDouble("blurH", 12), 3, 40) / 100.0));
      bw = Math.min(bw, w - bx); bh = Math.min(bh, h - by);       // frame အပြင် မထွက်အောင်
      bw = even(bw); bh = even(bh);
      double sigma = clamp(s.optDouble("blurStrength", 18), 2, 60);
      int sw = even(bw / 4), sh = even(bh / 4);          // ကျဉ်းကျဉ်းလေးချုံ့ပြီး blur လုပ်ပြီး ပြန်ချဲ့ → ၁၆ ဆခန့် မြန်
      double dark = clamp(s.optDouble("blurDarkness", 40), 0, 100) / 100.0;   // 0 = မမှောင်၊ 100 = အနက်ပြည့်
      g.append("[base]split=2[bgv][blrv];")
       .append("[blrv]crop=").append(bw).append(":").append(bh).append(":").append(bx).append(":").append(by)
       .append(",scale=").append(sw).append(":").append(sh).append(":flags=fast_bilinear")
       .append(",gblur=sigma=").append(String.format(Locale.US, "%.1f", Math.max(1.0, sigma / 4.0))).append(":steps=2")
       .append(",scale=").append(bw).append(":").append(bh).append(":flags=bilinear")
       .append(",drawbox=x=0:y=0:w=iw:h=ih:color=black@").append(String.format(Locale.US, "%.2f", dark)).append(":t=fill[blurbox];")
       .append("[bgv][blurbox]overlay=x=").append(bx).append(":y=").append(by).append("[boxout];");
      current = "boxout";
    }

    if (j.logo != null && logoInput >= 0) {
      int logoW = even((int) (w * clamp(s.optDouble("logoSize", 16), 5, 35) / 100.0));
      int margin = Math.max(8, w / 60);
      g.append("[").append(logoInput).append(":v]format=rgba,scale=").append(logoW).append(":-1[brandlogo];[")
       .append(current).append("][brandlogo]overlay=x=W-w-").append(margin).append(":y=").append(margin).append("[logoout];");
      current = "logoout";
    }

    if (j.titleOverlay != null && titleInput >= 0) {
      int titleW = (int) clamp(s.optDouble("titleOverlayWidth", w * 0.88), 2, w);
      int tx = (int) ((w - titleW) * clamp(s.optDouble("titleX", 8), 0, 100) / 100.0);
      int titleH = (int) clamp(s.optDouble("titleOverlayHeight", h * 0.2), 2, h);
      int ty = (int) ((h - titleH) * clamp(s.optDouble("titleY", 12), 0, 100) / 100.0);
      g.append("[").append(titleInput).append(":v]format=rgba[titleimg];[").append(current).append("][titleimg]overlay=x=")
       .append(tx).append(":y=").append(ty).append("[titleout];");
      current = "titleout";
    }

    g.append("[").append(current).append("]format=yuv420p[vout]");
    return g.toString();
  }

  private String videoFilter(Job j) {
    JSObject s = j.style; int w = j.w, h = j.h;

    // 1) အချိန်ညှိ: video ကိုပဲ မြန်/နှေး လုပ်တယ် (အသံ မထိ)၊ ပြီးမှ fps ချိတ်
    StringBuilder b = new StringBuilder();
    if (j.anchA != null) b.append(buildPiecewisePts(j));
    else if (Math.abs(j.k - 1.0) > 0.0005) b.append("setpts=").append(String.format(Locale.US, "%.8f", j.k)).append("*(PTS-STARTPTS)");
    else b.append("setpts=PTS-STARTPTS");
    b.append(",fps=").append(j.fps);
    // Audio-master sync တွင် manual delay မထည့်ပါ။ Clone frame မဖြစ်စေရန် videoDelay ကို မသုံးပါ။

    // 2) frame ပုံစံ + zoom (scale တစ်ခါတည်း)
    double zoom = Math.max(1, Math.min(1.12, s.optDouble("zoom", 1.02)));
    if (zoom <= 1.001) zoom = 1.0;
    if ("fit".equals(s.optString("fitMode", "fit"))) {
      int zw = even((int) Math.round(w * zoom)), zh = even((int) Math.round(h * zoom));
      b.append(",scale=").append(zw).append(":").append(zh).append(":force_original_aspect_ratio=decrease:flags=bilinear,pad=")
       .append(zw).append(":").append(zh).append(":(ow-iw)/2:(oh-ih)/2:color=black");
      if (zoom > 1.0) b.append(",crop=").append(w).append(":").append(h).append(":(iw-ow)/2:(ih-oh)/2");
    } else {
      String z = String.format(Locale.US, "%.4f", zoom);
      String crop = j.landscape
        ? "crop=w='min(iw,ih*16/9)/" + z + "':h='min(ih,iw*9/16)/" + z + "':x='(iw-ow)/2':y='(ih-oh)/2'"
        : "crop=w='min(iw,ih*9/16)/" + z + "':h='min(ih,iw*16/9)/" + z + "':x='(iw-ow)/2':y='(ih-oh)/2'";
      b.append(",").append(crop).append(",scale=").append(w).append(":").append(h).append(":flags=bilinear");
    }

    if (s.optBoolean("flip", false)) b.append(",hflip");

    // 3) အရောင်
    double br = s.optDouble("brightness", 0) / 200.0, ct = 1 + s.optDouble("contrast", 0) / 100.0, sat = 1 + s.optDouble("saturation", 0) / 100.0;
    String preset = s.optString("filter", "cinematic");
    if ("mono".equals(preset)) sat = 0;
    if ("cinematic".equals(preset)) { ct *= 1.035; sat *= .96; }
    if ("vivid".equals(preset)) { ct *= 1.05; sat *= 1.16; }
    double inMin = Math.max(0.0, 0.5 - (ct / 2.0) + br);
    double inMax = Math.min(1.0, 0.5 + (ct / 2.0) + br);
    if (inMax - inMin < 0.05) inMax = Math.min(1.0, inMin + 0.05);
    double inSat = Math.max(0.0, Math.min(1.0, sat));
    if (inMin > 0.002 || inMax < 0.998) {   // ပြောင်းစရာမရှိရင် မလုပ်ဘူး၊ lutyuv က colorlevels ထက် အဆများစွာ မြန်တယ်
      b.append(String.format(Locale.US, ",lutyuv=y='clip((val/maxval-%.4f)*%.4f*maxval,minval,maxval)'", inMin, 1.0 / (inMax - inMin)));
    }
    if (inSat != 1.0) b.append(String.format(Locale.US, ",hue=s=%.3f", inSat));

    int border = Math.max(0, Math.min(18, s.optInt("frameWidth", 0)));
    if (border > 0) {
      String c = s.optString("frameColor", "#d6ef47");
      if (!c.matches("#[0-9a-fA-F]{6}")) c = "#d6ef47";
      b.append(",drawbox=x=0:y=0:w=iw:h=ih:color=").append(c.replace("#", "0x")).append(":t=").append(border);
    }

    // Freeze frame / tpad clone ကို မသုံးပါ။
    // Scene တစ်ခုချင်းစီရဲ့ setpts retime က narration duration ကို ဖြည့်ရမယ်။
    b.append(",setsar=1");
    return b.toString();
  }

  private int even(int n) { return Math.max(2, n & ~1); }
  private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
  private String fmt(double n) { return String.format(Locale.US, "%.3f", n); }

  private File createTitleOverlay(File work, String text, JSObject style, int frameW, int frameH) throws Exception {
    int fontSize = (int)(clamp(style.optDouble("titleSize", 34), 16, 100) * frameH / 1280.0);
    int padding = Math.max(8, frameW / 90);
    int maxWidth = Math.max(80, (int)(frameW * 0.88));
    TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    paint.setTextSize(Math.max(12, fontSize));
    String color = style.optString("titleColor", "#ffffff");
    paint.setColor(Color.parseColor(color.matches("#[0-9a-fA-F]{6}") ? color : "#ffffff"));
    StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(40, maxWidth - padding * 2))
      .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).setLineSpacing(0f, 1.08f).build();
    float widestLine = 1f;
    for (int line = 0; line < layout.getLineCount(); line++) widestLine = Math.max(widestLine, layout.getLineWidth(line));
    int bitmapW = Math.min(maxWidth, (int)Math.ceil(widestLine) + padding * 2);
    int bitmapH = layout.getHeight() + padding * 2;
    style.put("titleOverlayWidth", bitmapW); style.put("titleOverlayHeight", bitmapH);
    Bitmap bitmap = Bitmap.createBitmap(Math.max(2, bitmapW), Math.max(2, bitmapH), Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    Paint box = new Paint(Paint.ANTI_ALIAS_FLAG); box.setColor(Color.argb(72, 0, 0, 0));
    canvas.drawRoundRect(new RectF(0, 0, bitmapW, bitmapH), Math.max(4, padding / 2), Math.max(4, padding / 2), box);
    canvas.save(); canvas.translate(padding, padding); layout.draw(canvas); canvas.restore();
    File out = new File(work, "title_overlay.png");
    try (FileOutputStream stream = new FileOutputStream(out)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.flush(); stream.getFD().sync(); }
    bitmap.recycle(); return out;
  }

  private Uri saveMovies(File f) throws Exception {
    if (Build.VERSION.SDK_INT < 29) {
      File movies = getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
      if (movies == null) throw new Exception("Movies storage မရနိုင်ပါ");
      File output = new File(movies, "SiGyi_Recap_" + System.currentTimeMillis() + ".mp4");
      copy(f, output); return Uri.fromFile(output);
    }
    ContentValues v = new ContentValues();
    v.put(MediaStore.Video.Media.DISPLAY_NAME, "SiGyi_Recap_" + System.currentTimeMillis() + ".mp4");
    v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
    v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SiGyi Recap Studio");
    v.put(MediaStore.Video.Media.IS_PENDING, 1);
    Uri u = getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
    if (u == null) throw new Exception("Movies folder ထဲ သိမ်းမရပါ");
    try (InputStream in = new FileInputStream(f); OutputStream out = getContext().getContentResolver().openOutputStream(u)) {
      if (out == null) throw new Exception("Movies folder ထဲ ရေးမရပါ");
      byte[] b = new byte[1024 * 1024]; int n;
      while ((n = in.read(b)) > 0) out.write(b, 0, n);
    } catch (Throwable t) {
      try { getContext().getContentResolver().delete(u, null, null); } catch (Throwable ignored) {}
      throw new Exception("Movies folder ထဲ သိမ်းမရပါ: " + t.getMessage());
    }
    ContentValues ready = new ContentValues(); ready.put(MediaStore.Video.Media.IS_PENDING, 0);
    getContext().getContentResolver().update(u, ready, null, null); return u;
  }

  private void copy(File a, File b) throws Exception {
    try (InputStream in = new FileInputStream(a); OutputStream out = new FileOutputStream(b)) {
      byte[] x = new byte[1024 * 1024]; int n;
      while ((n = in.read(x)) > 0) out.write(x, 0, n);
    }
  }

  private void deleteTree(File f) {
    if (f == null || !f.exists()) return;
    if (f.isDirectory()) { File[] xs = f.listFiles(); if (xs != null) for (File x : xs) deleteTree(x); }
    f.delete();
  }

  @PluginMethod public void cancel(PluginCall call) { cancelled = true; FFmpegKit.cancel(); call.resolve(); }
}
