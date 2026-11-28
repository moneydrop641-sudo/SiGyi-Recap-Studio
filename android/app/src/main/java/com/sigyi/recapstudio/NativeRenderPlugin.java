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
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import androidx.activity.result.ActivityResult;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.getcapacitor.*;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@CapacitorPlugin(name = "NativeRender")
public class NativeRenderPlugin extends Plugin {
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final AtomicReference<FFmpegSession> activeSession = new AtomicReference<>(null);
  private final AtomicBoolean isRendering = new AtomicBoolean(false);
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // ---- safe bridge helpers (run on main thread) ----
  private void safeResolve(PluginCall call, JSObject result) {
    if (call == null) return;
    mainHandler.post(() -> { try { call.resolve(result); } catch (Throwable ignored) {} });
  }
  private void safeReject(PluginCall call, String msg) {
    if (call == null) return;
    mainHandler.post(() -> { try { call.reject(msg); } catch (Throwable ignored) {} });
  }
  private void safeNotify(String event, JSObject data) {
    mainHandler.post(() -> { try { notifyListeners(event, data); } catch (Throwable ignored) {} });
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
      safeReject(call, "Logo ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။"); return;
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
      r.put("size", target.length()); r.put("mimeType", getContext().getContentResolver().getType(uri)); safeResolve(call, r);
    } catch (Throwable t) { safeReject(call, "Logo ဖိုင်ကို ကူးမရပါ: " + t.getMessage()); }
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
      safeReject(call, "ဖိုင်ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။"); return;
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
      r.put("size", target.length()); r.put("mimeType", getContext().getContentResolver().getType(uri)); safeResolve(call, r);
    } catch (Throwable t) { safeReject(call, "ဖိုင်ကို app storage ထဲ ကူးမရပါ: " + t.getMessage()); }
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

  @PluginMethod public void render(PluginCall call) {
    if (!isRendering.compareAndSet(false, true)) {
      safeReject(call, "Render လုပ်ဆောင်နေဆဲဖြစ်ပါတယ်။"); return;
    }

    String vp = call.getString("videoPath"), ap = call.getString("audioPath"), lp = call.getString("logoPath");
    Boolean isLandscape = call.getBoolean("landscape", false);

    if (vp == null || !new File(vp).isFile()) { isRendering.set(false); safeReject(call, "Video ဖိုင်မတွေ့ပါ။"); return; }
    if (ap != null && !new File(ap).isFile()) { isRendering.set(false); safeReject(call, "Audio ဖိုင်မတွေ့ပါ။"); return; }
    if (lp != null && !new File(lp).isFile()) { isRendering.set(false); safeReject(call, "Logo ဖိုင်မတွေ့ပါ။"); return; }

    cancelled.set(false);
    final boolean land = isLandscape != null && isLandscape;
    new Thread(() -> {
      try {
        renderWorker(call, new File(vp), ap == null ? null : new File(ap), lp == null ? null : new File(lp), land);
      } catch (Throwable t) {
        safeReject(call, t.getMessage() == null ? "Render မအောင်မြင်ပါ" : t.getMessage());
      } finally {
        isRendering.set(false);
        activeSession.set(null);
      }
    }, "SiGyiNativeRender").start();
  }

  private void renderWorker(PluginCall call, File source, File voice, File logo, boolean isLandscape) {
    File work = new File(getContext().getCacheDir(), "sigyi_job_" + System.currentTimeMillis());
    if (!work.mkdirs()) { safeReject(call, "Cache folder မဖန်တီးနိုင်ပါ။"); return; }

    long startTime = System.currentTimeMillis();
    File output = null;

    try {
      final double videoSpeed = 0.9;
      final double audioSpeed = 1.1;

      progress(2, "ဗီဒီယိုကို 30fps/48kHz ပြောင်းနေပါတယ်...");
      File fixedVideo = new File(work, "fixed_video.mp4");

      FFmpegSession s1 = executeTracked(new String[]{
          "-y", "-i", source.getAbsolutePath(),
          "-vf", "fps=30,setpts=" + String.format(Locale.US, "%.6f", 1.0 / videoSpeed) + "*PTS",
          "-c:v", "mpeg4", "-q:v", "5",
          "-ar", "48000", "-c:a", "aac", "-b:a", "192k",
          fixedVideo.getAbsolutePath()
      }, 2, 5, 0, "ဗီဒီယိုကို ပြောင်းနေပါတယ်...", startTime);

      if (s1 == null || !ReturnCode.isSuccess(s1.getReturnCode())) {
          String logs = safeLogs(s1);
          throw new Exception("Video ပြောင်းလဲမှု မအောင်မြင်ပါ။" + (logs.isEmpty() ? "" : "\n" + logs));
      }
      if (cancelled.get()) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");

      double vDuration = probe(fixedVideo);
      double aDuration = voice != null ? probe(voice) : vDuration;
      if (vDuration <= 0 && aDuration <= 0) throw new Exception("Duration မဖတ်နိုင်ပါ။");
      double finalDuration = voice != null ? aDuration / audioSpeed : vDuration;

      String res = call.getString("resolution", "720p");
      int w, h, fps = 30;
      if ("360p".equals(res)) { w = isLandscape ? 640 : 360; h = isLandscape ? 360 : 640; }
      else if ("1080p".equals(res)) { w = isLandscape ? 1920 : 1080; h = isLandscape ? 1080 : 1920; }
      else { w = isLandscape ? 1280 : 720; h = isLandscape ? 720 : 1280; }

      JSObject style = call.getObject("style"); if (style == null) style = new JSObject();
      File titleOverlay = null; String titleText = style.optString("titleText", "");
      if (!titleText.trim().isEmpty()) {
        try { titleOverlay = createTitleOverlay(work, titleText, style, w, h); }
        catch (Throwable t) { titleOverlay = null; } // title fail → skip, don't crash render
      }

      List<String> a = new ArrayList<>();
      a.add("-y");
      a.addAll(Arrays.asList("-i", fixedVideo.getAbsolutePath()));
      if (voice != null) a.addAll(Arrays.asList("-i", voice.getAbsolutePath()));

      int nextInput = voice != null ? 2 : 1; int logoInput = -1, titleInput = -1;
      if (logo != null) { logoInput = nextInput++; a.addAll(Arrays.asList("-loop", "1", "-i", logo.getAbsolutePath())); }
      if (titleOverlay != null) { titleInput = nextInput; a.addAll(Arrays.asList("-loop", "1", "-i", titleOverlay.getAbsolutePath())); }

      String filterComplex = buildFilterGraph(style, w, h, fps, finalDuration, logoInput, logo, titleInput, titleOverlay, isLandscape, audioSpeed, voice != null);
      a.addAll(Arrays.asList("-filter_complex", filterComplex, "-map", "[vout]"));

      if (voice != null) {
          a.addAll(Arrays.asList("-map", "[aout]", "-c:a", "aac", "-b:a", "128k", "-ar", "48000"));
      } else {
          a.addAll(Arrays.asList("-map", "0:a?"));
      }

      output = new File(work, "final.mp4");
      // NOTE: no +faststart here — it doubles I/O and causes the "stuck at 95%" symptom.
      a.addAll(Arrays.asList(
          "-c:v", "mpeg4", "-q:v", "5", "-threads", "8",
          "-pix_fmt", "yuv420p", "-movflags", "+faststart",
          "-t", String.format(Locale.US, "%.3f", finalDuration + 0.5),
          output.getAbsolutePath()
      ));

      FFmpegSession s2 = executeTracked(a.toArray(new String[0]), 10, 85, finalDuration, "Render လုပ်နေပါတယ်...", startTime);

      if (s2 == null || !ReturnCode.isSuccess(s2.getReturnCode())) {
          String logs = safeLogs(s2);
          throw new Exception("Render မအောင်မြင်ပါ။" + (logs.isEmpty() ? "" : "\n" + logs));
      }
      if (cancelled.get()) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");

      if (output == null || !output.isFile() || output.length() < 1024) {
          throw new Exception("Output ဖိုင် မမှန်ကန်ပါ။");
      }

      progress(96, "Movies folder ထဲ သိမ်းနေပါတယ်…");
      Uri saved = saveMovies(output);
      if (saved == null) throw new Exception("Movies folder ထဲ သိမ်းမရပါ");

      JSObject result = new JSObject();
      result.put("savedUri", saved.toString());
      result.put("previewPath", output.getAbsolutePath());
      result.put("name", "SiGyi_Recap.mp4");
      result.put("size", output.length());
      progress(100, "Render ပြီးပါပြီ");
      safeResolve(call, result);

    } catch (Throwable t) {
      safeReject(call, t.getMessage() == null ? "Render မအောင်မြင်ပါ" : t.getMessage());
    } finally {
      // don't delete output if it's still referenced by previewPath — but work dir cleanup is fine
      // only delete the work dir if we successfully resolved (output already copied to Movies)
      // keep it simple: delete tree but never touch Movies copy
      try { deleteTree(work); } catch (Throwable ignored) {}
    }
  }

  private String safeLogs(FFmpegSession s) {
    try { if (s != null) { String l = s.getAllLogsAsString(); return l == null ? "" : l; } } catch (Throwable ignored) {}
    return "";
  }

  private FFmpegSession executeTracked(String[] args, int base, int span, double durationSeconds, String stageText, long startTime) throws Exception {
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicReference<FFmpegSession> result = new AtomicReference<>(null);
    final long[] lastNotify = {0};
    final long[] lastPct = {-1};

    try {
      FFmpegKit.executeWithArgumentsAsync(
        args,
        session -> { result.set(session); done.countDown(); },
        null,
        stats -> {
          try {
            if (stats == null) return;
            if (durationSeconds > 0) {
              long now = System.currentTimeMillis();
              if (now - lastNotify[0] > 800) {
                lastNotify[0] = now;
                double seconds = Math.max(0, stats.getTime() / 1000.0);
                double ratio = Math.min(1.0, seconds / durationSeconds);
                int pct = base + (int)(ratio * span);

                long elapsed = now - startTime;
                long estimatedTotal = ratio > 0.01 ? (long)(elapsed / ratio) : 0;
                long remaining = Math.max(0, estimatedTotal - elapsed);

                String timeStr = fmtDur(elapsed);
                String remStr = fmtDur(remaining);
                int capped = Math.min(95, pct); // cap at 95 until save actually starts
                if (capped != lastPct[0]) {
                  lastPct[0] = capped;
                  progressWithTime(capped, stageText, timeStr, remStr);
                }
              }
            }
          } catch (Throwable ignored) {}
        }
      );
    } catch (Throwable t) {
      throw new Exception("FFmpeg launch fail: " + t.getMessage());
    }

    while (!done.await(500, TimeUnit.MILLISECONDS)) {
      if (cancelled.get()) {
        try { FFmpegKit.cancel(); } catch (Throwable ignored) {}
      }
    }
    FFmpegSession s = result.get();
    if (s == null) throw new Exception("FFmpeg session error");
    return s;
  }

  private String fmtDur(long ms) {
    long totalSec = TimeUnit.MILLISECONDS.toSeconds(ms);
    return String.format(Locale.US, "%02d မိနစ် %02d စက္ကန့်", totalSec / 60, totalSec % 60);
  }

  private double probe(File f) {
    final CountDownLatch latch = new CountDownLatch(1);
    final double[] d = {0};
    try {
      FFprobeKit.getMediaInformationAsync(f.getAbsolutePath(), s -> {
        try {
          if (s != null && s.getMediaInformation() != null) {
            String dur = s.getMediaInformation().getDuration();
            if (dur != null) d[0] = Double.parseDouble(dur);
          }
        } catch (Throwable ignored) {}
        latch.countDown();
      });
    } catch (Throwable t) { return 0; }
    try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    return d[0];
  }

  private String buildFilterGraph(JSObject s, int w, int h, int fps, double seconds,
                                  int logoInput, File logo, int titleInput, File titleOverlay,
                                  boolean isLandscape, double audioSpeed, boolean hasAudio) throws Exception {
    String base = videoFilter(s, w, h, fps, seconds, isLandscape);
    String current = "base";
    StringBuilder g = new StringBuilder("[0:v]").append(base).append("[base];");

    if (s.optBoolean("blurEnabled", false)) {
        int bw = even((int)(w * clamp(s.optDouble("blurW", 84), 5, 100) / 100.0));
        int bh = even((int)(h * clamp(s.optDouble("blurH", 12), 3, 40) / 100.0));
        int bx = even((int)(w * clamp(s.optDouble("blurX", 8), 0, 90) / 100.0));
        int by = even((int)(h * clamp(s.optDouble("blurY", 72), 0, 90) / 100.0));

        g.append("[base]drawbox=x=").append(bx).append(":y=").append(by)
         .append(":w=").append(bw).append(":h=").append(bh)
         .append(":color=black:t=fill[boxout];");
        current = "boxout";
    }

    if (logo != null && logoInput >= 0) {
      int logoW = even((int)(w * clamp(s.optDouble("logoSize", 16), 5, 35) / 100.0));
      int margin = Math.max(8, w / 60);
      g.append("[").append(logoInput).append(":v]format=rgba,scale=").append(logoW).append(":-1[brandlogo];")
       .append("[").append(current).append("][brandlogo]overlay=x=W-w-").append(margin)
       .append(":y=").append(margin).append(":eof_action=pass:repeatlast=1[logoout];");
      current = "logoout";
    }

    if (titleOverlay != null && titleInput >= 0) {
      double rawW = clamp(s.optDouble("titleOverlayWidth", w * 0.88), 2, w);
      int titleW = even((int) rawW);
      int tx = even((int)((w - titleW) * clamp(s.optDouble("titleX", 8), 0, 100) / 100.0));
      double rawH = clamp(s.optDouble("titleOverlayHeight", h * 0.2), 2, h);
      int titleH = even((int) rawH);
      int ty = even((int)((h - titleH) * clamp(s.optDouble("titleY", 12), 0, 100) / 100.0));
      if (tx < 0) tx = 0; if (ty < 0) ty = 0;
      g.append("[").append(titleInput).append(":v]format=rgba[titleimg];")
       .append("[").append(current).append("][titleimg]overlay=x=").append(tx)
       .append(":y=").append(ty).append(":eof_action=pass:repeatlast=1[titleout];");
      current = "titleout";
    }

    if (hasAudio) {
        double spd = Math.max(0.5, Math.min(2.0, audioSpeed));
        g.append("[1:a]aresample=async=1000:first_pts=0,atempo=")
         .append(String.format(Locale.US, "%.4f", spd))
         .append(",apad[aout];");
    }

    g.append("[").append(current).append("]null[vout]");
    return g.toString();
  }

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
    int bitmapW = even(Math.min(maxWidth, (int)Math.ceil(widestLine) + padding * 2));
    int bitmapH = even(layout.getHeight() + padding * 2);
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

  private int even(int n) { return Math.max(2, n & ~1); }
  private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

  private String videoFilter(JSObject s, int w, int h, int fps, double seconds, boolean isLandscape) {
    String base;
    if ("fit".equals(s.optString("fitMode", "fit"))) {
        base = "scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease,pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2:color=black";
    } else {
        String cropExpr = isLandscape
            ? "crop=w='min(iw,ih*16/9)':h='min(ih,iw*9/16)':x='(iw-ow)/2':y='(ih-oh)/2'"
            : "crop=w='min(iw,ih*9/16)':h='min(ih,iw*16/9)':x='(iw-ow)/2':y='(ih-oh)/2'";
        base = cropExpr + ",scale=" + w + ":" + h;
    }

    double zoom = Math.max(1, Math.min(1.12, s.optDouble("zoom", 1.02)));
    if (zoom > 1.001) base += ",crop=w='iw/" + zoom + "':h='ih/" + zoom + "':x='(iw-ow)/2':y='(ih-oh)/2',scale=" + w + ":" + h;

    if (s.optBoolean("flip", false)) base += ",hflip";
    base += ",fps=" + fps + ",setsar=1";

    double br = s.optDouble("brightness", 0) / 200.0, ct = 1 + s.optDouble("contrast", 0) / 100.0, sat = 1 + s.optDouble("saturation", 0) / 100.0;
    String preset = s.optString("filter", "cinematic");
    if ("mono".equals(preset)) sat = 0;
    if ("cinematic".equals(preset)) { ct *= 1.035; sat *= .96; }
    if ("vivid".equals(preset)) { ct *= 1.05; sat *= 1.16; }

    double inMin = Math.max(0.0, 0.5 - (ct / 2.0) + br);
    double inMax = Math.min(1.0, 0.5 + (ct / 2.0) + br);
    double inSat = Math.max(0.0, Math.min(1.0, sat));

    base += String.format(Locale.US, ",colorlevels=rimin=%.3f:gimin=%.3f:bimin=%.3f:rimax=%.3f:gimax=%.3f:bimax=%.3f", inMin, inMin, inMin, inMax, inMax, inMax);
    if (inSat != 1.0) base += String.format(Locale.US, ",hue=s=%.3f", inSat);

    int border = Math.max(0, Math.min(18, s.optInt("frameWidth", 0)));
    if (border > 0) {
        String color = s.optString("frameColor", "#d6ef47").replace("#", "0x");
        base += ",drawbox=x=0:y=0:w=iw:h=ih:color=" + color + ":t=" + border;
    }
    return base;
  }

  private void progress(int p, String stage) {
      JSObject d = new JSObject(); d.put("progress", p); d.put("stage", stage);
      safeNotify("renderProgress", d);
  }

  private void progressWithTime(int p, String stage, String elapsed, String remaining) {
      JSObject d = new JSObject(); d.put("progress", p); d.put("stage", stage);
      d.put("elapsed", elapsed); d.put("remaining", remaining);
      safeNotify("renderProgress", d);
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
        if (out == null) throw new Exception("Output stream မရနိုင်ပါ");
        byte[] b = new byte[1024 * 1024]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
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

  @PluginMethod public void cancel(PluginCall call) {
    cancelled.set(true);
    try { FFmpegKit.cancel(); } catch (Throwable ignored) {}
    safeResolve(call, new JSObject());
  }
}
