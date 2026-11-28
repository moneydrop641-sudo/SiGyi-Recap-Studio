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
  private volatile long progressFrameBase = 0;
  private volatile long progressFrameTotal = 1;
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

  // ===================== AUTO ALIGN (speech-envelope DTW) =====================

  /** Speech-envelope DTW aligner. Pure Java, no Android deps. */
  private static final class Aligner {
    static final class Result {
      boolean ok; String report = "";
      double[] a, v;                 // anchor points (audio sec -> video sec), excluding end point
      double corrDtw, corrLin, costDtw, costLin;
    }

    /** log-energy frames -> normalized 0..1, lightly smoothed */
    static float[] normalize(float[] logE) {
      int n = logE.length;
      if (n == 0) return logE;
      float[] s = logE.clone(); Arrays.sort(s);
      float lo = s[(int) (n * 0.05)], hi = s[Math.min(n - 1, (int) (n * 0.95))];
      if (hi - lo < 1e-3f) hi = lo + 1e-3f;
      float[] x = new float[n];
      for (int i = 0; i < n; i++) x[i] = Math.max(0f, Math.min(1f, (logE[i] - lo) / (hi - lo)));
      float[] y = new float[n];
      for (int i = 0; i < n; i++) {
        float a = x[Math.max(0, i - 1)], b = x[i], c = x[Math.min(n - 1, i + 1)];
        y[i] = (a + 2 * b + c) * 0.25f;
      }
      return y;
    }

    static double pearson(float[] x, float[] y) {
      int n = x.length; double mx = 0, my = 0;
      for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
      mx /= n; my /= n;
      double sxy = 0, sxx = 0, syy = 0;
      for (int i = 0; i < n; i++) { double dx = x[i] - mx, dy = y[i] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy; }
      if (sxx < 1e-9 || syy < 1e-9) return 0;
      return sxy / Math.sqrt(sxx * syy);
    }

    /** ea = narration envelope (output timeline), ev = original video-audio envelope. Same hop (sec). */
    static Result align(float[] ea, float[] ev, double hop) {
      Result r = new Result();
      int Na = ea.length, Nv = ev.length;
      if (Na < 60 || Nv < 60) { r.report = "too short"; return r; }
      double ratio = (double) (Nv - 1) / (Na - 1);
      if (ratio < 0.56 || ratio > 1.8) { r.report = "duration ratio out of range"; return r; }
      int W = Math.max(30, Math.min(600, (int) (0.15 * Nv) + 20));
      int width = 2 * W + 1;
      int[] center = new int[Na];
      for (int i = 0; i < Na; i++) center[i] = (int) Math.round(i * ratio);
      final float INF = 1e30f, pen = 0.08f;
      float[][] D = new float[3][width];
      byte[][] bp = new byte[Na][width];
      for (int i = 0; i < Na; i++) {
        float[] cur = D[i % 3]; Arrays.fill(cur, INF);
        float[] p1 = i >= 1 ? D[(i - 1) % 3] : null, p2 = i >= 2 ? D[(i - 2) % 3] : null;
        for (int k = 0; k < width; k++) {
          int j = center[i] - W + k;
          if (j < 0 || j >= Nv) continue;
          float c = Math.abs(ea[i] - ev[j]);
          if (i == 0) { if (j == 0) cur[k] = c; continue; }
          float best = INF; byte arg = -1;
          int k1 = (j - 1) - center[i - 1] + W;
          if (k1 >= 0 && k1 < width && p1[k1] < INF) { float t = p1[k1] + c; if (t < best) { best = t; arg = 0; } }
          int k2 = (j - 2) - center[i - 1] + W;
          if (k2 >= 0 && k2 < width && p1[k2] < INF) { float t = p1[k2] + c + pen; if (t < best) { best = t; arg = 1; } }
          if (i >= 2) {
            int k3 = (j - 1) - center[i - 2] + W;
            if (k3 >= 0 && k3 < width && p2[k3] < INF) { float t = p2[k3] + 2 * c + pen; if (t < best) { best = t; arg = 2; } }
          }
          cur[k] = best; bp[i][k] = arg;
        }
      }
      if (D[(Na - 1) % 3][W] >= INF) { r.report = "no path in band"; return r; }

      float[] jRow = new float[Na]; Arrays.fill(jRow, Float.NaN);
      int i = Na - 1, j = Nv - 1;
      while (true) {
        if (i < 0 || j < 0) { r.report = "backtrack error"; return r; }
        jRow[i] = j;
        if (i == 0) break;
        int k = j - center[i] + W;
        if (k < 0 || k >= width) { r.report = "backtrack error"; return r; }
        int a = bp[i][k];
        if (a == 0) { i -= 1; j -= 1; }
        else if (a == 1) { i -= 1; j -= 2; }
        else if (a == 2) { jRow[i - 1] = j - 0.5f; i -= 2; j -= 1; }
        else { r.report = "backtrack error"; return r; }
      }
      if (j != 0) { r.report = "backtrack error"; return r; }

      float[] xa = new float[Na], xd = new float[Na], xl = new float[Na];
      double cd = 0, cl = 0;
      for (int q = 0; q < Na; q++) {
        int jd = Math.max(0, Math.min(Nv - 1, Math.round(jRow[q])));
        int jl = Math.max(0, Math.min(Nv - 1, (int) Math.round(q * ratio)));
        xa[q] = ea[q]; xd[q] = ev[jd]; xl[q] = ev[jl];
        cd += Math.abs(ea[q] - xd[q]); cl += Math.abs(ea[q] - xl[q]);
      }
      r.costDtw = cd / Na; r.costLin = cl / Na;
      r.corrDtw = pearson(xa, xd); r.corrLin = pearson(xa, xl);

      // anchors every ~6 s, path smoothed over +-1.5 s
      int step = Math.max(2, (int) Math.round(6.0 / hop)), sm = Math.max(1, (int) Math.round(1.5 / hop));
      List<double[]> pts = new ArrayList<>();
      for (int q = step; q < Na - step / 2; q += step) {
        int lo = Math.max(0, q - sm), hi = Math.min(Na - 1, q + sm);
        double s = 0; for (int z = lo; z <= hi; z++) s += jRow[z];
        pts.add(new double[]{q * hop, (s / (hi - lo + 1)) * hop});
      }
      r.a = new double[pts.size()]; r.v = new double[pts.size()];
      for (int q = 0; q < pts.size(); q++) { r.a[q] = pts.get(q)[0]; r.v[q] = pts.get(q)[1]; }
      r.ok = true;
      return r;
    }
  }

  // ===================== RENDER =====================

  private static class Job {
    File source, voice, logo, titleOverlay, out;
    JSObject style;
    boolean landscape;
    int w, h, fps, frames;
    double vDur, aDur, finalDur, k, pad;
    double chunkStart = 0, chunkDuration = 0;
    String res, hwDec;
    double[] anchA, anchV;   // anchor points (audio time -> video time)
    double aSpeed = 1.0;     // audio tempo (pitch မပြောင်း)
  }

  private static class VInfo { String codec; double fps, dur; }
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
      logLines.clear(); statsLine = "";

      double vDur = probe(source);
      double aDur = voice != null ? exactDuration(voice) : vDur;   // mp3 VBR မှာ probe က မှားတတ်လို့ decode ပြီး တိတိကျကျ တိုင်း
      if (vDur <= 0) throw new Exception("Video duration မဖတ်နိုင်ပါ။");
      if (aDur <= 0) throw new Exception("Audio duration မဖတ်နိုင်ပါ။");

      // ---- အသံ speed (default 1.1x) → output ကြာချိန် = အသံကြာချိန် / speed ----
      double aSpeed = 1.1;
      JSObject st0 = call.getObject("style");
      if (st0 != null) aSpeed = st0.optDouble("audioSpeed", aSpeed);
      Double spc = call.getDouble("audioSpeed");
      if (spc != null) aSpeed = spc;
      aSpeed = Math.max(0.8, Math.min(1.5, aSpeed));
      if (voice == null || Math.abs(aSpeed - 1.0) < 0.001) aSpeed = 1.0;
      if (aSpeed != 1.0) { addLog(String.format(Locale.US, "Audio speed x%.2f: %.1fs → %.1fs", aSpeed, aDur, aDur / aSpeed)); aDur = aDur / aSpeed; }

      Job j = new Job();
      j.source = source; j.voice = voice; j.logo = logo; j.out = out; j.landscape = isLandscape;
      j.aSpeed = aSpeed;
      j.vDur = vDur; j.aDur = aDur; j.finalDur = aDur;       // နောက်ဆုံး ကြာချိန် = အသံကြာချိန် (အသံက အဓိက)
      VInfo vi = probeVideoInfo(source);
      j.fps = pickFps(vi.fps);
      if (vi.dur > 0 && Math.abs(vi.dur - vDur) > 0.05) {      // container duration ≠ video stream duration → speed မှားမယ့်အကြောင်းရင်း
        addLog("Video duration: container=" + fmt(vDur) + "s | video-stream=" + fmt(vi.dur) + "s → stream ကိုသုံးမယ်");
        vDur = vi.dur; j.vDur = vDur;
      }
      j.res = call.getString("resolution", "720p");
      if ("360p".equals(j.res)) { j.w = isLandscape ? 640 : 360; j.h = isLandscape ? 360 : 640; }
      else if ("1080p".equals(j.res)) { j.w = isLandscape ? 1920 : 1080; j.h = isLandscape ? 1080 : 1920; }
      else { j.w = isLandscape ? 1280 : 720; j.h = isLandscape ? 720 : 1280; }
      j.frames = (int) Math.max(1, Math.round(j.finalDur * j.fps));

      JSObject style = call.getObject("style"); if (style == null) style = new JSObject();
      j.style = style;

      // ---- Anchor များ (အသံအချိန် → video အချိန်) ပေးထားရင် အပိုင်းလိုက် တိတိကျကျ ညှိမယ် ----
      JSArray anchorArr = call.getArray("anchors");
      if (anchorArr == null) { try { anchorArr = style.has("anchors") ? new JSArray(style.getJSONArray("anchors").toString()) : null; } catch (Throwable ignored) {} }
      if (anchorArr != null && voice != null) buildAnchors(j, anchorArr);

      // ---- Audio master sync ----
      // Audio ကို atempo နဲ့ အရင်ပြောင်းပြီးနောက် ရလာမယ့် aDur ကို output timeline အဖြစ် သတ်မှတ်သည်။
      // stretch: video ကို audio ကြာချိန်အတိုင်း အပိုင်းလိုက် အချိန်ညှိ (freeze မလုပ်)
      // trim: video မလုံလောက်လျှင် မဖန်တီးဘဲ ရှင်းလင်းသော error ပြမည်။
      String syncMode = call.getString("syncMode", style.optString("syncMode", "stretch"));
      j.k = 1.0; j.pad = 0;
      if (voice != null) {
        if ("trim".equals(syncMode) && vDur + 0.05 < aDur) {
          throw new Exception("Video ကြာချိန် မလုံလောက်ပါ။ Freeze frame မထည့်ဘဲ render လုပ်ရန် Video " +
              fmt(vDur) + "s၊ ပြုပြင်ပြီး Audio " + fmt(aDur) + "s လိုအပ်ပါတယ်။ Sync Mode ကို stretch ပြောင်းပါ။");
        }
        // setpts multiplier: source video အချိန်ကို output audio အချိန်သို့ map လုပ်သည်။
        // 3s video + 6s audio ဆိုလျှင် multiplier=2.0 ဖြစ်ပြီး video ကို နှေးစေသည်။
        if (!"trim".equals(syncMode)) j.k = aDur / vDur;
      }
      // Scene Cut Detection ကို source video ပေါ်မှာ စစ်ပြီး output timeline သို့ ပြောင်းမည်။
      stageText = "Scene Cut Detection လုပ်နေပါတယ်...";
      List<Double> sceneCuts = detectSceneCuts(source, work, 0.30);
      List<Double> chunkBounds = buildChunkBoundaries(j, sceneCuts);
      addLog("Scene Cut Detection: " + sceneCuts.size() + " cuts | Render chunks: " + (chunkBounds.size() - 1));
      for (int ci = 0; ci < chunkBounds.size() - 1; ci++) {
        addLog(String.format(Locale.US, "Chunk %d: %.3f–%.3fs", ci + 1, chunkBounds.get(ci), chunkBounds.get(ci + 1)));
      }

      // ---- Auto align: မူရင်း video အသံနဲ့ dub အသံရဲ့ စကားပြော/တိတ်ဆိတ် စည်းချက်ကို DTW နဲ့ တိုက်ညှိ ----
      String alignMode = call.getString("alignMode", style.optString("alignMode", "off"));
      if (voice != null && j.anchA == null && !"trim".equals(syncMode) && !"off".equals(alignMode) && aDur >= 90 && aDur <= 2400) {
        stageText = "အသံနဲ့ ရုပ် ညှိနေပါတယ်...";
        try { autoAlign(j, source, voice, work); }
        catch (Throwable ae) { addLog("AutoAlign error → linear နဲ့ဆက်သွားမယ်: " + ae.getMessage()); }
      } else if (voice != null && j.anchA == null) {
        addLog("AutoAlign: မသုံးပါ (mode=" + alignMode + ", sync=" + syncMode + ", audio=" + fmt(aDur) + "s)");
      }

      String titleText = style.optString("titleText", "");
      if (!titleText.trim().isEmpty()) j.titleOverlay = createTitleOverlay(work, titleText, style, j.w, j.h);

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
        FFmpegSession s = encodeChunks(j, enc, "hw".equals(decMode), work, chunkBounds);
        ok = s != null && ReturnCode.isSuccess(s.getReturnCode());
        if (!ok) {
          if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
          lastLogs = tail(s);
          addLog("✖ မအောင်မြင်ပါ → နောက်တစ်နည်း စမ်းမယ်");
          if (j.out.exists()) j.out.delete();
        }
      }
      if (!ok) throw new Exception("Render မအောင်မြင်ပါ။\n" + lastLogs);

      encoding = false; ratio = 1; stageText = "Movies folder ထဲ သိမ်းနေပါတယ်…";
      String syncReport = verifyOutput(out);
      validateOutputDurations(out, j.finalDur);
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

  /** Scene-aware 30s chunk rendering: video chunks သီးခြား encode → concat → audio တစ်ကြိမ်တည်း mux */
  private FFmpegSession encodeChunks(Job j, String encoder, boolean hwDecode, File work,
                                     List<Double> bounds) throws Exception {
    File chunkDir = new File(work, "chunks_" + encoder + "_" + (hwDecode ? "hw" : "sw"));
    if (chunkDir.exists()) deleteTree(chunkDir);
    if (!chunkDir.mkdirs()) throw new Exception("Chunk folder မဖန်တီးနိုင်ပါ။");

    List<File> parts = new ArrayList<>();
    long completedFrames = 0;
    progressFrameBase = 0;
    progressFrameTotal = Math.max(1, j.frames);

    for (int i = 0; i < bounds.size() - 1; i++) {
      if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
      double from = bounds.get(i), to = bounds.get(i + 1);
      int chunkFrames = Math.max(1, (int) Math.round(to * j.fps) - (int) Math.round(from * j.fps));
      double dur = chunkFrames / (double) j.fps;
      if (dur <= 0.02) continue;

      j.chunkStart = from;
      j.chunkDuration = dur;
      File part = new File(chunkDir, String.format(Locale.US, "part_%05d.mp4", i));
      parts.add(part);

      List<String> args = new ArrayList<>(Arrays.asList("-y", "-hide_banner"));
      if (hwDecode && j.hwDec != null) args.addAll(Arrays.asList("-c:v", j.hwDec));
      args.addAll(Arrays.asList("-i", j.source.getAbsolutePath()));

      int next = 1, logoInput = -1, titleInput = -1;
      if (j.logo != null) { logoInput = next++; args.addAll(Arrays.asList("-i", j.logo.getAbsolutePath())); }
      if (j.titleOverlay != null) { titleInput = next++; args.addAll(Arrays.asList("-i", j.titleOverlay.getAbsolutePath())); }

      String graph = buildFilterGraph(j, logoInput, titleInput);
      args.addAll(Arrays.asList("-filter_complex", graph, "-map", "[vout]", "-an"));
      appendVideoEncoder(args, encoder, j);
      args.addAll(Arrays.asList("-pix_fmt", "yuv420p", "-r", String.valueOf(j.fps),
          "-frames:v", String.valueOf(chunkFrames), "-t", String.format(Locale.US, "%.3f", dur),
          "-avoid_negative_ts", "make_zero", part.getAbsolutePath()));

      stageText = String.format(Locale.US, "Chunk %d/%d Render လုပ်နေပါတယ်...", i + 1, bounds.size() - 1);
      addLog("▶ Render chunk " + (i + 1) + "/" + (bounds.size() - 1));
      progressFrameBase = completedFrames;
      FFmpegSession cs = runFfmpeg(args.toArray(new String[0]), chunkFrames);
      if (cs == null || !ReturnCode.isSuccess(cs.getReturnCode())) return cs;
      completedFrames += chunkFrames;
      ratio = Math.min(0.97, completedFrames / (double) progressFrameTotal);
    }

    if (parts.isEmpty()) throw new Exception("Render chunk မထွက်လာပါ။");
    j.chunkStart = 0; j.chunkDuration = j.finalDur;

    File concatList = new File(chunkDir, "concat.txt");
    try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(concatList), "UTF-8"))) {
      for (File part : parts) {
        String path = part.getAbsolutePath().replace("\\", "/").replace("'", "'\\''");
        bw.write("file '" + path + "'");
        bw.newLine();
      }
    }

    File joined = new File(chunkDir, "joined_video.mp4");
    stageText = "Render အပိုင်းများ ပြန်ဆက်နေပါတယ်...";
    FFmpegSession concat = runFfmpeg(new String[]{"-y", "-hide_banner", "-f", "concat", "-safe", "0",
        "-i", concatList.getAbsolutePath(), "-c:v", "copy", "-an", "-movflags", "+faststart",
        joined.getAbsolutePath()}, 0);
    if (concat == null || !ReturnCode.isSuccess(concat.getReturnCode())) return concat;

    // အသံကို နောက်ဆုံးတစ်ကြိမ်သာ encode/mux လုပ်ပြီး master timeline အတိုင်း ဖြတ်မည်။
    File audioSource = j.voice != null ? j.voice : j.source;
    List<String> mux = new ArrayList<>(Arrays.asList("-y", "-hide_banner", "-i", joined.getAbsolutePath(),
        "-i", audioSource.getAbsolutePath(), "-map", "0:v:0", "-map", j.voice != null ? "1:a:0" : "1:a:0?",
        "-c:v", "copy"));
    if (j.voice != null && j.aSpeed != 1.0)
      mux.addAll(Arrays.asList("-af", String.format(Locale.US, "atempo=%.4f", j.aSpeed)));
    mux.addAll(Arrays.asList("-c:a", "aac", "-b:a", j.voice != null ? "192k" : "128k", "-ar", "48000",
        "-t", String.format(Locale.US, "%.3f", j.finalDur), "-movflags", "+faststart", j.out.getAbsolutePath()));
    stageText = "အသံနဲ့ Video ကို နောက်ဆုံး Sync လုပ်နေပါတယ်...";
    progressFrameBase = progressFrameTotal;
    FFmpegSession muxed = runFfmpeg(mux.toArray(new String[0]), 0);
    j.chunkStart = 0; j.chunkDuration = 0;
    return muxed;
  }

  private void appendVideoEncoder(List<String> a, String encoder, Job j) {
    if ("h264_mediacodec".equals(encoder)) {
      String br = "360p".equals(j.res) ? "1500k" : "1080p".equals(j.res) ? "8000k" : "4000k";
      a.addAll(Arrays.asList("-c:v", "h264_mediacodec", "-b:v", br));
    } else if ("libx264".equals(encoder)) {
      a.addAll(Arrays.asList("-c:v", "libx264", "-preset", "ultrafast", "-crf", "27"));
    } else {
      int th = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
      a.addAll(Arrays.asList("-c:v", "mpeg4", "-q:v", "5", "-me_method", "zero",
          "-g", "60", "-threads", String.valueOf(th)));
    }
  }

  /** FFmpeg select(scene) ဖြင့် cut timestamp များ ရှာသည်။ */
  private List<Double> detectSceneCuts(File source, File work, double threshold) {
    List<Double> cuts = new ArrayList<>();
    try {
      FFmpegSession ss = FFmpegKit.executeWithArguments(new String[]{"-hide_banner", "-i",
          source.getAbsolutePath(), "-vf", "select='gt(scene," +
          String.format(Locale.US, "%.2f", threshold) + ")',showinfo", "-an", "-vsync", "0",
          "-f", "null", "-"});
      if (ss == null || !ReturnCode.isSuccess(ss.getReturnCode())) {
        addLog("Scene Cut Detection မအောင်မြင်ပါ → fixed 30s chunks သုံးမယ်");
        return cuts;
      }
      String logs = ss.getAllLogsAsString();
      if (logs != null) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)").matcher(logs);
        while (m.find()) {
          try {
            double t = Double.parseDouble(m.group(1));
            if (t > 0.25) cuts.add(t);
          } catch (Throwable ignored) {}
        }
      }
      Collections.sort(cuts);
      List<Double> unique = new ArrayList<>();
      for (double t : cuts) if (unique.isEmpty() || t - unique.get(unique.size() - 1) > 0.35) unique.add(t);
      addLog("Scene Cut Detection threshold=" + threshold + " found=" + unique.size());
      return unique;
    } catch (Throwable t) {
      addLog("Scene Cut Detection error: " + t.getMessage() + " → fixed chunks");
      return cuts;
    }
  }

  /** 30s အနီးရှိ scene cut ကို boundary အဖြစ်ရွေးပြီး မရှိလျှင် 30s ဖြင့်ခွဲသည်။ */
  private List<Double> buildChunkBoundaries(Job j, List<Double> sourceCuts) {
    List<Double> outputCuts = new ArrayList<>();
    for (double sourceT : sourceCuts) {
      double outT = sourceToOutputTime(j, sourceT);
      if (outT > 0.5 && outT < j.finalDur - 0.5) outputCuts.add(outT);
    }
    Collections.sort(outputCuts);
    List<Double> bounds = new ArrayList<>();
    bounds.add(0.0);
    double cursor = 0;
    while (j.finalDur - cursor > 30.0) {
      double target = cursor + 30.0;
      double chosen = target;
      double best = 4.0;
      for (double cut : outputCuts) {
        double gap = Math.abs(cut - target);
        if (cut > cursor + 8.0 && cut < j.finalDur - 5.0 && gap < best) {
          chosen = cut; best = gap;
        }
      }
      if (chosen - cursor < 8.0) chosen = target;
      bounds.add(chosen);
      cursor = chosen;
    }
    bounds.add(j.finalDur);
    // Chunk boundary တိုင်းကို output frame grid ပေါ်တင်ပြီး cumulative frame ပျောက်ဆုံးမှု မဖြစ်စေ။
    for (int i = 1; i < bounds.size() - 1; i++) {
      bounds.set(i, Math.round(bounds.get(i) * j.fps) / (double) j.fps);
    }
    bounds.set(bounds.size() - 1, j.frames / (double) j.fps);
    // Round လုပ်ပြီး boundary တူသွားပါက ဖယ်ရှားမည်။
    List<Double> clean = new ArrayList<>();
    clean.add(0.0);
    for (int i = 1; i < bounds.size(); i++) {
      double t = bounds.get(i);
      if (t - clean.get(clean.size() - 1) >= 1.0 / j.fps) clean.add(t);
    }
    if (clean.get(clean.size() - 1) < j.frames / (double) j.fps)
      clean.add(j.frames / (double) j.fps);
    return clean;
  }

  private double sourceToOutputTime(Job j, double sourceTime) {
    if (j.anchA != null && j.anchV != null && j.anchA.length == j.anchV.length) {
      for (int i = 1; i < j.anchA.length; i++) {
        if (sourceTime <= j.anchV[i]) {
          double dv = j.anchV[i] - j.anchV[i - 1];
          if (dv <= 0) return j.anchA[i - 1];
          return j.anchA[i - 1] + (sourceTime - j.anchV[i - 1]) *
              (j.anchA[i] - j.anchA[i - 1]) / dv;
        }
      }
      return j.finalDur;
    }
    return sourceTime * j.k;
  }

  private FFmpegSession runFfmpeg(String[] args, int totalFrames) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    final FFmpegSession[] result = {null};
    FFmpegSession s = FFmpegKit.executeWithArgumentsAsync(args, session -> { result[0] = session; done.countDown(); }, log -> addLog(log.getMessage()), stats -> {
      if (totalFrames > 0) ratio = Math.max(0.0, Math.min(1.0,
          (progressFrameBase + stats.getVideoFrameNumber()) / (double) progressFrameTotal));
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
              try { String dd = si.getStringProperty("duration"); if (dd != null) vi.dur = Double.parseDouble(dd); } catch (Throwable ignored) {}
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
    applyAnchors(j, pts);
  }

  private void applyAnchors(Job j, List<double[]> pts) {
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
    if (clean.size() > 700) return;     // command ရှည်လွန်းမှာစိုးလို့ (setpts expression ~၉၀ byte × anchor)
    j.anchA = new double[clean.size()]; j.anchV = new double[clean.size()];
    for (int i = 0; i < clean.size(); i++) { j.anchA[i] = clean.get(i)[0]; j.anchV[i] = clean.get(i)[1]; }
  }

  private void autoAlign(Job j, File source, File voice, File work) throws Exception {
    long t0 = System.currentTimeMillis();
    double hop = Math.max(0.1, Math.min(1.0, Math.round(j.aDur / 6000.0 / 0.05) * 0.05));
    int frameSamples = (int) Math.round(hop * 8000);
    File pa = new File(work, "a.pcm"), pv = new File(work, "v.pcm");
    String bp = "highpass=f=300,lowpass=f=3400";      // စကားသံ band ပဲယူ (ဂီတ/bass လျှော့)
    FFmpegSession sa = FFmpegKit.executeWithArguments(new String[]{"-y", "-hide_banner", "-i", voice.getAbsolutePath(), "-vn", "-af", bp + (j.aSpeed != 1.0 ? String.format(Locale.US, ",atempo=%.4f", j.aSpeed) : ""), "-ac", "1", "-ar", "8000", "-f", "s16le", pa.getAbsolutePath()});
    if (!ReturnCode.isSuccess(sa.getReturnCode())) { addLog("AutoAlign: dub အသံ decode မရ"); return; }
    if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
    FFmpegSession sv = FFmpegKit.executeWithArguments(new String[]{"-y", "-hide_banner", "-i", source.getAbsolutePath(), "-map", "0:a:0", "-vn", "-af", bp, "-ac", "1", "-ar", "8000", "-f", "s16le", pv.getAbsolutePath()});
    if (!ReturnCode.isSuccess(sv.getReturnCode())) { addLog("AutoAlign: video မှာ အသံ မရှိ/ဖတ်မရ → linear"); return; }
    if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");

    float[] ea = Aligner.normalize(pcmEnvelope(pa, frameSamples));
    float[] ev = Aligner.normalize(pcmEnvelope(pv, frameSamples));
    pa.delete(); pv.delete();
    Aligner.Result r = Aligner.align(ea, ev, hop);
    if (!r.ok) { addLog("AutoAlign: မလုပ်နိုင် (" + r.report + ") → linear"); return; }

    List<double[]> pts = new ArrayList<>();
    for (int i = 0; i < r.a.length; i++) pts.add(new double[]{r.a[i], r.v[i]});
    applyAnchors(j, pts);
    addLog(String.format(Locale.US, "AutoAlign: hop=%.2fs frames A=%d V=%d | fit corr=%.2f (linear %.2f) | anchors=%d | %dms",
        hop, ea.length, ev.length, r.corrDtw, r.corrLin, j.anchA == null ? 0 : j.anchA.length - 1, System.currentTimeMillis() - t0));
  }

  private float[] pcmEnvelope(File pcm, int frameSamples) throws Exception {
    int n = (int) (pcm.length() / 2 / frameSamples);
    float[] e = new float[Math.max(0, n)];
    if (n <= 0) return e;
    byte[] buf = new byte[frameSamples * 2];
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(pcm), 1 << 16))) {
      for (int i = 0; i < n; i++) {
        in.readFully(buf);
        double sum = 0;
        for (int k = 0; k < frameSamples; k++) { int sv = (short) ((buf[2 * k] & 0xff) | (buf[2 * k + 1] << 8)); sum += (double) sv * sv; }
        e[i] = (float) Math.log10(Math.sqrt(sum / frameSamples) / 32768.0 + 1e-5);
      }
    }
    return e;
  }

  /** setpts expression: source အချိန် T → output အချိန် f(T) (အပိုင်းလိုက် linear) */
  private String anchorRetime(Job j) {
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

  /** Render ပြီးနောက် output ထဲက video/audio ကြာချိန် နှိုင်းယှဉ်စစ် */
  private String verifyOutput(File f) {
    try {
      com.arthenica.ffmpegkit.MediaInformationSession ms = FFprobeKit.getMediaInformation(f.getAbsolutePath());
      double v = 0, a = 0;
      if (ms.getMediaInformation() != null) for (StreamInformation si : ms.getMediaInformation().getStreams()) {
        String d = si.getStringProperty("duration");
        if (d == null) continue;
        if ("video".equals(si.getType())) v = Double.parseDouble(d);
        else if ("audio".equals(si.getType())) a = Double.parseDouble(d);
      }
      String r = String.format(Locale.US, "Output check: video=%.3fs | audio=%.3fs | diff=%dms", v, a, Math.round(Math.abs(v - a) * 1000));
      addLog(r); return r;
    } catch (Throwable t) { return ""; }
  }

  /** Output ထဲရှိ video/audio stream duration ကို စစ်ပြီး အလွန်ကွာလျှင် မသိမ်းဆည်းစေပါ။ */
  private void validateOutputDurations(File f, double expected) throws Exception {
    com.arthenica.ffmpegkit.MediaInformationSession ms = FFprobeKit.getMediaInformation(f.getAbsolutePath());
    if (ms == null || ms.getMediaInformation() == null) throw new Exception("Output ကို FFprobe ဖြင့် မစစ်နိုင်ပါ။");
    double vd = 0, ad = 0;
    for (StreamInformation si : ms.getMediaInformation().getStreams()) {
      String ds = si.getStringProperty("duration");
      if (ds == null) continue;
      try {
        double d = Double.parseDouble(ds);
        if ("video".equals(si.getType())) vd = d;
        else if ("audio".equals(si.getType())) ad = d;
      } catch (Throwable ignored) {}
    }
    addLog(String.format(Locale.US, "Duration validation: target=%.3fs video=%.3fs audio=%.3fs", expected, vd, ad));
    // AAC encoder priming/padding ကြောင့် audio stream တွင် အနည်းငယ်ကွာနိုင်သည်။
    if (vd <= 0 || Math.abs(vd - expected) > 0.20)
      throw new Exception(String.format(Locale.US,
          "Video duration မကိုက်ညီပါ (target %.3fs / output %.3fs)။ Freeze frame မထည့်ထားသောကြောင့် output ကို မသိမ်းပါ။",
          expected, vd));
    if (ad > 0 && Math.abs(ad - expected) > 0.35)
      throw new Exception(String.format(Locale.US,
          "Audio duration မကိုက်ညီပါ (target %.3fs / output %.3fs)။", expected, ad));
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
    if (j.anchA != null) b.append(anchorRetime(j));
    else if (Math.abs(j.k - 1.0) > 0.0005) b.append("setpts=").append(String.format(Locale.US, "%.8f", j.k)).append("*(PTS-STARTPTS)");
    else b.append("setpts=PTS-STARTPTS");
    if (j.chunkDuration > 0) {
      b.append(",trim=start=").append(String.format(Locale.US, "%.6f", j.chunkStart))
       .append(":duration=").append(String.format(Locale.US, "%.6f", j.chunkDuration))
       .append(",setpts=PTS-STARTPTS");
    }
    b.append(",fps=").append(j.fps);

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

    // Freeze/clone frame လုံးဝမသုံးပါ။ Video timeline ကို setpts ဖြင့်ညှိပြီး
    // output frame count ဖြင့်သာ အဆုံးသတ်သည်။ Source frame မလုံလောက်ပါက render မအောင်မြင်စေပါ။
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
