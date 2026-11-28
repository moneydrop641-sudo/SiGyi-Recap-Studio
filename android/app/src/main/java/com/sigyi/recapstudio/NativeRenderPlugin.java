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
import com.getcapacitor.*;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@CapacitorPlugin(name = "NativeRender")
public class NativeRenderPlugin extends Plugin {
  private volatile boolean cancelled = false;
  private volatile FFmpegSession activeSession;
  private volatile boolean isRendering = false;

  // ✅ ဒီ flag ကို သုံးပြီး x264 ရှိ/မရှိ စစ်မယ်
  private static volatile Boolean useX264 = null;

  @PluginMethod public void pickVideo(PluginCall call) { pick(call, "video/*", "video"); }
  @PluginMethod public void pickAudio(PluginCall call) { pick(call, "audio/*", "audio"); }
  @PluginMethod public void pickTtsFile(PluginCall call) { pick(call, "audio/*", "tts"); }

  @PluginMethod public void pickLogo(PluginCall call) {
    try {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("image/*");
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      startActivityForResult(call, i, "pickLogoResult");
    } catch (Throwable t) {
      call.reject("Logo ရွေးချယ်မှု မဖွင့်နိုင်ပါ: " + safeMsg(t));
    }
  }

  @ActivityCallback
  private void pickLogoResult(PluginCall call, ActivityResult result) {
    if (call == null) return;
    try {
      if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK
          || result.getData() == null || result.getData().getData() == null) {
        call.reject("Logo ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။");
        return;
      }
      Uri uri = result.getData().getData();
      try {
        getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (Throwable ignored) {}

      String name = displayName(uri).replaceAll("[^A-Za-z0-9._-]", "_");
      File dir = new File(getContext().getFilesDir(), "media");
      if (!dir.exists() && !dir.mkdirs()) throw new Exception("Logo storage မဖန်တီးနိုင်ပါ");
      File target = new File(dir, "logo_" + System.currentTimeMillis() + "_" + name);
      try (InputStream in = getContext().getContentResolver().openInputStream(uri);
           FileOutputStream out = new FileOutputStream(target)) {
        if (in == null) throw new Exception("Logo ဖိုင်ဖတ်မရပါ");
        byte[] b = new byte[256 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.getFD().sync();
      }
      JSObject r = new JSObject();
      r.put("path", target.getAbsolutePath());
      r.put("name", displayName(uri));
      r.put("size", target.length());
      r.put("mimeType", getContext().getContentResolver().getType(uri));
      call.resolve(r);
    } catch (Throwable t) {
      call.reject("Logo ဖိုင်ကို ကူးမရပါ: " + safeMsg(t));
    }
  }

  private void pick(PluginCall call, String mime, String kind) {
    try {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType(mime);
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      String callback = "audio".equals(kind) ? "pickAudioResult"
          : "video".equals(kind) ? "pickVideoResult" : "pickTtsResult";
      startActivityForResult(call, i, callback);
    } catch (Throwable t) {
      call.reject("ဖိုင်ရွေးချယ်မှု မဖွင့်နိုင်ပါ: " + safeMsg(t));
    }
  }

  @ActivityCallback private void pickVideoResult(PluginCall call, ActivityResult result) { handlePickResult(call, result, "video"); }
  @ActivityCallback private void pickAudioResult(PluginCall call, ActivityResult result) { handlePickResult(call, result, "audio"); }
  @ActivityCallback private void pickTtsResult(PluginCall call, ActivityResult result) { handlePickResult(call, result, "tts"); }

  private void handlePickResult(PluginCall call, ActivityResult result, String kind) {
    if (call == null) return;
    try {
      if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK
          || result.getData() == null || result.getData().getData() == null) {
        call.reject("ဖိုင်ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။");
        return;
      }
      Uri uri = result.getData().getData();
      try {
        getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (Throwable ignored) {}

      String displayName = displayName(uri);
      String lower = displayName.toLowerCase(Locale.US);
      if (("audio".equals(kind) || "tts".equals(kind))
          && !(lower.endsWith(".mp3") || lower.endsWith(".wav"))) {
        throw new Exception("MP3 သို့မဟုတ် WAV ဖိုင်သာ ရွေးပါ။");
      }
      String name = displayName.replaceAll("[^A-Za-z0-9._-]", "_");
      File dir = new File(getContext().getFilesDir(), "media");
      if (!dir.exists() && !dir.mkdirs()) throw new Exception("App storage မဖန်တီးနိုင်ပါ");

      if (!"tts".equals(kind)) {
        File[] previous = dir.listFiles();
        if (previous != null) for (File old : previous) {
          if (old.getName().startsWith(kind + "_")) old.delete();
        }
      }

      File target = new File(dir, kind + "_" + System.currentTimeMillis() + "_" + name);
      try (InputStream in = getContext().getContentResolver().openInputStream(uri);
           FileOutputStream out = new FileOutputStream(target)) {
        if (in == null) throw new Exception("ဖိုင်ဖတ်မရပါ");
        byte[] b = new byte[1024 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.getFD().sync();
      }
      JSObject r = new JSObject();
      r.put("path", target.getAbsolutePath());
      r.put("name", displayName(uri));
      r.put("size", target.length());
      r.put("mimeType", getContext().getContentResolver().getType(uri));
      call.resolve(r);
    } catch (Throwable t) {
      call.reject("ဖိုင်ကို app storage ထဲ ကူးမရပါ: " + safeMsg(t));
    }
  }

  private String displayName(Uri uri) {
    try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        if (n >= 0) {
          String v = c.getString(n);
          if (v != null && !v.trim().isEmpty()) return v;
        }
      }
    } catch (Throwable ignored) {}
    return "media";
  }

  @PluginMethod public void render(PluginCall call) {
    try {
      if (isRendering) { call.reject("Render လုပ်ဆောင်နေဆဲဖြစ်ပါတယ်။"); return; }

      String vp = call.getString("videoPath");
      String ap = call.getString("audioPath");
      String lp = call.getString("logoPath");
      Boolean isLandscape = call.getBoolean("landscape", false);
      if (isLandscape == null) isLandscape = false;

      if (vp == null || !new File(vp).isFile()) { call.reject("Video ဖိုင်မတွေ့ပါ။"); return; }
      if (ap != null && !new File(ap).isFile()) { call.reject("Audio ဖိုင်မတွေ့ပါ။"); return; }
      if (lp != null && !new File(lp).isFile()) { call.reject("Logo ဖိုင်မတွေ့ပါ။"); return; }

      cancelled = false;
      isRendering = true;
      final String fvp = vp, fap = ap, flp = lp;
      final boolean fl = isLandscape;
      new Thread(() -> {
        try {
          renderWorker(call, new File(fvp), fap == null ? null : new File(fap),
              flp == null ? null : new File(flp), fl);
        } catch (Throwable t) {
          isRendering = false;
          try { call.reject("Render မအောင်မြင်ပါ: " + safeMsg(t)); } catch (Throwable ignored) {}
        }
      }, "SiGyiNativeRender").start();
    } catch (Throwable t) {
      call.reject("Render မစနိုင်ပါ: " + safeMsg(t));
    }
  }

  private void renderWorker(PluginCall call, File source, File voice, File logo, boolean isLandscape) {
    File work = new File(getContext().getCacheDir(), "sigyi_job_" + System.currentTimeMillis());
    if (!work.mkdirs()) {
      isRendering = false;
      call.reject("Cache folder မဖန်တီးနိုင်ပါ။");
      return;
    }

    long startTime = System.currentTimeMillis();

    try {
      // ✅ Audio/Video sync ကို မထိပါ — 0.9x video / 1.1x audio အတိုင်း
      double videoSpeed = 0.9;
      double audioSpeed = 1.1;

      progress(call, 2, "ဗီဒီယိုကို 30fps/48kHz ပြောင်းနေပါတယ်...");
      File fixedVideo = new File(work, "fixed_video.mp4");
      double srcDuration = probeSafe(source);

      // ✅ 30fps fix — codec ကို auto ရွေး (x264 ရှိရင် သုံး)
      String[] fixVideoArgs = buildVideoFixArgs(source, fixedVideo, videoSpeed);
      activeSession = executeTracked(fixVideoArgs, call, 2, 13,
          srcDuration > 0 ? srcDuration : 0, "ဗီဒီယိုကို ပြောင်းနေပါတယ်...", startTime);

      if (activeSession == null || !ReturnCode.isSuccess(activeSession.getReturnCode())) {
        throw new Exception("Video ပြောင်းလဲမှု မအောင်မြင်ပါ။");
      }

      double vDuration = probeSafe(fixedVideo);
      double aDuration = voice != null ? probeSafe(voice) : vDuration;
      double finalDuration = voice != null ? aDuration / audioSpeed : vDuration;
      if (finalDuration <= 0) throw new Exception("Duration မဖတ်နိုင်ပါ။");

      String res = call.getString("resolution", "720p");
      if (res == null) res = "720p";
      int w, h, fps = 30;
      if ("360p".equals(res)) { w = isLandscape ? 640 : 360; h = isLandscape ? 360 : 640; }
      else if ("1080p".equals(res)) { w = isLandscape ? 1920 : 1080; h = isLandscape ? 1080 : 1920; }
      else { w = isLandscape ? 1280 : 720; h = isLandscape ? 720 : 1280; }
      w = even(w);
      h = even(h);

      JSObject style = call.getObject("style");
      if (style == null) style = new JSObject();
      File titleOverlay = null;
      String titleText = style.optString("titleText", "");
      if (titleText != null && !titleText.trim().isEmpty()) {
        try {
          titleOverlay = createTitleOverlay(work, titleText, style, w, h);
        } catch (Throwable t) {
          titleOverlay = null; // title fail ရင် render မရပ်ပါ
        }
      }

      List<String> a = new ArrayList<>();
      a.add("-y");
      a.add("-i"); a.add(fixedVideo.getAbsolutePath());
      if (voice != null) { a.add("-i"); a.add(voice.getAbsolutePath()); }

      int nextInput = voice != null ? 2 : 1;
      int logoInput = -1, titleInput = -1;
      if (logo != null) {
        logoInput = nextInput++;
        a.add("-loop"); a.add("1"); a.add("-i"); a.add(logo.getAbsolutePath());
      }
      if (titleOverlay != null) {
        titleInput = nextInput;
        a.add("-loop"); a.add("1"); a.add("-i"); a.add(titleOverlay.getAbsolutePath());
      }

      String filterComplex;
      try {
        filterComplex = buildFilterGraph(style, w, h, fps, finalDuration,
            logoInput, logo, titleInput, titleOverlay, isLandscape, audioSpeed, voice != null);
      } catch (Throwable t) {
        throw new Exception("Filter ဖန်တီးမှု မအောင်မြင်ပါ: " + safeMsg(t));
      }
      a.add("-filter_complex"); a.add(filterComplex);
      a.add("-map"); a.add("[vout]");

      if (voice != null) {
        a.add("-map"); a.add("[aout]");
        a.add("-c:a"); a.add("aac"); a.add("-b:a"); a.add("128k"); a.add("-ar"); a.add("48000");
      } else {
        a.add("-map"); a.add("0:a?");
      }

      File output = new File(work, "final.mp4");
      // ✅ codec auto + threads 0 + faststart မပါ (95% ရပ်တာကို ဖြေရှင်း)
      appendFinalCodecArgs(a);
      a.add(output.getAbsolutePath());

      // ✅ 18% → 95% (faststart မပါတာမို့ 95 အထိ ရောက်နိုင်)
      activeSession = executeTracked(a.toArray(new String[0]), call, 18, 77,
          finalDuration, "Render လုပ်နေပါတယ်...", startTime);

      if (activeSession == null || !ReturnCode.isSuccess(activeSession.getReturnCode())) {
        String logs = null;
        try { if (activeSession != null) logs = activeSession.getAllLogsAsString(); } catch (Throwable ignored) {}
        throw new Exception("Render မအောင်မြင်ပါ။" + (logs != null && !logs.isEmpty() ? "\n" + logs : ""));
      }

      if (!output.isFile() || output.length() <= 0) {
        throw new Exception("Output ဖိုင် မဖန်တီးနိုင်ပါ။");
      }

      // ✅ FFmpeg ပြီးတာနဲ့ 95% ကို အမှန်ပြ
      progress(call, 95, "ဖိုင်ကို ပြီးစီးအောင် ပြုလုပ်နေပါတယ်...");

      // ✅ saveMovies ကို သီးခြား thread မှာ လုပ် (block ဖြစ်ရင် UI မရပ်)
      progress(call, 96, "Movies folder ထဲ သိမ်းနေပါတယ်…");
      final Uri[] savedRef = {null};
      final Exception[] saveErr = {null};
      Thread saveThread = new Thread(() -> {
        try { savedRef[0] = saveMovies(output); }
        catch (Throwable t) { saveErr[0] = new Exception(t); }
      }, "SiGyiSave");
      saveThread.start();

      int saveTick = 96;
      while (saveThread.isAlive()) {
        try { saveThread.join(1000); } catch (InterruptedException ignored) {}
        if (saveThread.isAlive()) {
          saveTick = Math.min(99, saveTick + 1);
          progress(call, saveTick, "Movies folder ထဲ သိမ်းနေပါတယ်…");
        }
      }

      if (saveErr[0] != null) throw saveErr[0];
      Uri saved = savedRef[0];
      if (saved == null) throw new Exception("Movies folder ထဲ သိမ်းမရပါ");

      JSObject result = new JSObject();
      result.put("savedUri", saved.toString());
      result.put("previewPath", output.getAbsolutePath());
      result.put("name", "SiGyi_Recap.mp4");
      result.put("size", output.length());
      progress(call, 100, "Render ပြီးပါပြီ");
      call.resolve(result);

    } catch (Throwable t) {
      try { call.reject(t.getMessage() == null ? "Render မအောင်မြင်ပါ" : t.getMessage()); }
      catch (Throwable ignored) {}
    } finally {
      try { deleteTree(work); } catch (Throwable ignored) {}
      activeSession = null;
      isRendering = false;
    }
  }

  // ✅ Video fix (30fps/0.9x) — codec auto
  private String[] buildVideoFixArgs(File source, File out, double videoSpeed) {
    List<String> a = new ArrayList<>();
    a.add("-y");
    a.add("-i"); a.add(source.getAbsolutePath());
    a.add("-vf"); a.add("fps=30,setpts=" + fmt(1.0 / videoSpeed) + "*PTS");
    appendVideoCodecArgs(a);
    a.add("-c:a"); a.add("aac"); a.add("-ar"); a.add("48000"); a.add("-b:a"); a.add("192k");
    a.add(out.getAbsolutePath());
    return a.toArray(new String[0]);
  }

  // ✅ Video codec args — x264 ရှိရင် သုံး၊ မရရင် mpeg4 fallback
  private void appendVideoCodecArgs(List<String> a) {
    if (supportsX264()) {
      a.add("-c:v"); a.add("libx264");
      a.add("-preset"); a.add("ultrafast");
      a.add("-crf"); a.add("23");
      a.add("-tune"); a.add("fastdecode");
    } else {
      a.add("-c:v"); a.add("mpeg4");
      a.add("-q:v"); a.add("5");
    }
    a.add("-threads"); a.add("0");
    a.add("-pix_fmt"); a.add("yuv420p");
  }

  // ✅ Final output codec args — faststart မပါ
  private void appendFinalCodecArgs(List<String> a) {
    if (supportsX264()) {
      a.add("-c:v"); a.add("libx264");
      a.add("-preset"); a.add("ultrafast");
      a.add("-crf"); a.add("23");
    } else {
      a.add("-c:v"); a.add("mpeg4");
      a.add("-q:v"); a.add("5");
    }
    a.add("-threads"); a.add("0");
    a.add("-pix_fmt"); a.add("yuv420p");
    // ❌ +faststart မထည့်ပါ — ဒါက 95% ရပ်တာရဲ့ အဓိကအကြောင်းရင်း
  }

  // ✅ x264 ရှိ/မရှိ တစ်ခါပဲ စစ်
  private boolean supportsX264() {
    if (useX264 != null) return useX264;
    synchronized (NativeRenderPlugin.class) {
      if (useX264 != null) return useX264;
      boolean ok = false;
      try {
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] found = {false};
        FFmpegKit.executeWithArgumentsAsync(new String[]{"-hide_banner", "-encoders"},
            session -> {
              try {
                String logs = session != null ? session.getAllLogsAsString() : null;
                if (logs != null && logs.contains("libx264")) found[0] = true;
              } catch (Throwable ignored) {}
              latch.countDown();
            }, null, null);
        latch.await(10, TimeUnit.SECONDS);
        ok = found[0];
      } catch (Throwable ignored) {}
      useX264 = ok;
      return ok;
    }
  }

  private FFmpegSession executeTracked(String[] args, PluginCall call, int base, int span,
                                       double durationSeconds, String stageText, long startTime) throws Exception {
    final CountDownLatch done = new CountDownLatch(1);
    final FFmpegSession[] result = {null};
    final AtomicLong lastNotify = new AtomicLong(0);
    final AtomicInteger lastPct = new AtomicInteger(base);
    final AtomicBoolean running = new AtomicBoolean(true);
    final double[] lastSeconds = {0};

    try {
      FFmpegKit.executeWithArgumentsAsync(args, session -> {
        result[0] = session;
        running.set(false);
        done.countDown();
      }, null, stats -> {
        try {
          if (durationSeconds <= 0 || stats == null) return;
          long now = System.currentTimeMillis();
          if (now - lastNotify.get() < 800) return;
          lastNotify.set(now);
          double seconds = stats.getTime() / 1000.0;
          if (seconds > lastSeconds[0]) lastSeconds[0] = seconds;
          int pct = base + (int) (Math.min(1.0, lastSeconds[0] / durationSeconds) * span);
          if (pct <= lastPct.get()) return;
          lastPct.set(pct);

          long elapsed = now - startTime;
          double ratio = Math.min(1.0, lastSeconds[0] / durationSeconds);
          long estimatedTotal = ratio > 0.01 ? (long) (elapsed / ratio) : 0;
          long remaining = Math.max(0, estimatedTotal - elapsed);
          progressWithTime(call, Math.min(95, pct), stageText, fmtTime(elapsed), fmtTime(remaining));
        } catch (Throwable ignored) {}
      });
    } catch (Throwable t) {
      running.set(false);
      done.countDown();
      throw new Exception("FFmpeg စတင်မရပါ: " + safeMsg(t));
    }

    // ✅ Timer thread — stats ရပ်ရင်တောင် elapsed/remaining ဆက်ပြ
    Thread timer = new Thread(() -> {
      long lastEmit = 0;
      while (running.get() && !cancelled) {
        try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        long now = System.currentTimeMillis();
        if (now - lastEmit < 800) continue;
        lastEmit = now;
        if (durationSeconds <= 0) continue;
        if (lastPct.get() <= base) continue;
        try {
          long elapsed = now - startTime;
          double ratio = Math.min(1.0, lastSeconds[0] / durationSeconds);
          long estimatedTotal = ratio > 0.01 ? (long) (elapsed / ratio) : 0;
          long remaining = Math.max(0, estimatedTotal - elapsed);
          progressWithTime(call, Math.min(95, lastPct.get()), stageText,
              fmtTime(elapsed), fmtTime(remaining));
        } catch (Throwable ignored) {}
      }
    }, "SiGyiTimer");
    timer.setDaemon(true);
    timer.start();

    try {
      while (!done.await(500, TimeUnit.MILLISECONDS)) {
        if (cancelled) { try { FFmpegKit.cancel(); } catch (Throwable ignored) {} }
      }
    } catch (InterruptedException e) {
      try { FFmpegKit.cancel(); } catch (Throwable ignored) {}
      throw new Exception("Render ကို ရပ်လိုက်ပါပြီ။");
    } finally {
      running.set(false);
      try { timer.interrupt(); } catch (Throwable ignored) {}
    }

    if (result[0] == null) throw new Exception("FFmpeg session error");

    // ✅ နောက်ဆုံး progress အမှန်ပြ
    try {
      long elapsed = System.currentTimeMillis() - startTime;
      progressWithTime(call, Math.min(95, base + span), stageText,
          fmtTime(elapsed), "00 မိနစ် 00 စက္ကန့်");
    } catch (Throwable ignored) {}

    return result[0];
  }

  private double probeSafe(File f) {
    if (f == null || !f.isFile()) return 0;
    final CountDownLatch latch = new CountDownLatch(1);
    final double[] d = {0};
    try {
      FFprobeKit.getMediaInformationAsync(f.getAbsolutePath(), s -> {
        try {
          if (s != null && s.getMediaInformation() != null) {
            String dur = s.getMediaInformation().getDuration();
            if (dur != null && !dur.trim().isEmpty()) d[0] = Double.parseDouble(dur);
          }
        } catch (Throwable ignored) {}
        latch.countDown();
      });
    } catch (Throwable t) { return 0; }
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) return 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 0;
    }
    return d[0] > 0 ? d[0] : 0;
  }

  private String buildFilterGraph(JSObject s, int w, int h, int fps, double seconds,
                                  int logoInput, File logo, int titleInput, File titleOverlay,
                                  boolean isLandscape, double audioSpeed, boolean hasAudio) throws Exception {
    String base = videoFilter(s, w, h, fps, seconds, isLandscape);
    String current = "base";
    StringBuilder g = new StringBuilder("[0:v]").append(base).append("[base];");

    if (s.optBoolean("blurEnabled", false)) {
      int bw = Math.max(2, (int) (w * clamp(s.optDouble("blurW", 84), 5, 100) / 100.0));
      int bh = Math.max(2, (int) (h * clamp(s.optDouble("blurH", 12), 3, 40) / 100.0));
      int bx = (int) (w * clamp(s.optDouble("blurX", 8), 0, 90) / 100.0);
      int by = (int) (h * clamp(s.optDouble("blurY", 72), 0, 90) / 100.0);
      bx = Math.max(0, Math.min(bx, w - bw));
      by = Math.max(0, Math.min(by, h - bh));
      g.append("[base]drawbox=x=").append(bx).append(":y=").append(by)
          .append(":w=").append(bw).append(":h=").append(bh)
          .append(":color=black:t=fill[boxout];");
      current = "boxout";
    }

    if (logo != null && logoInput >= 0) {
      int logoW = Math.max(2, (int) (w * clamp(s.optDouble("logoSize", 16), 5, 35) / 100.0));
      int margin = Math.max(8, w / 60);
      g.append("[").append(logoInput).append(":v]format=rgba,scale=").append(logoW)
          .append(":-1[brandlogo];[").append(current).append("][brandlogo]overlay=x=W-w-")
          .append(margin).append(":y=").append(margin)
          .append(":eof_action=pass:repeatlast=1[logoout];");
      current = "logoout";
    }

    if (titleOverlay != null && titleInput >= 0) {
      int titleW = (int) clamp(s.optDouble("titleOverlayWidth", w * 0.88), 2, w);
      int tx = (int) ((w - titleW) * clamp(s.optDouble("titleX", 8), 0, 100) / 100.0);
      int titleH = (int) clamp(s.optDouble("titleOverlayHeight", h * 0.2), 2, h);
      int ty = (int) ((h - titleH) * clamp(s.optDouble("titleY", 12), 0, 100) / 100.0);
      tx = Math.max(0, Math.min(tx, w - titleW));
      ty = Math.max(0, Math.min(ty, h - titleH));
      g.append("[").append(titleInput).append(":v]format=rgba[titleimg];[")
          .append(current).append("][titleimg]overlay=x=").append(tx).append(":y=")
          .append(ty).append(":eof_action=pass:repeatlast=1[titleout];");
      current = "titleout";
    }

    // ✅ Audio 1.1x — sync မထိပါ
    if (hasAudio) {
      g.append("[1:a]aresample=async=1000:first_pts=0,atempo=")
          .append(String.format(Locale.US, "%.4f", audioSpeed)).append("[aout];");
    }

    g.append("[").append(current).append("]null[vout]");
    return g.toString();
  }

  private File createTitleOverlay(File work, String text, JSObject style, int frameW, int frameH) throws Exception {
    int fontSize = (int) (clamp(style.optDouble("titleSize", 34), 16, 100) * frameH / 1280.0);
    int padding = Math.max(8, frameW / 90);
    int maxWidth = Math.max(80, (int) (frameW * 0.88));
    TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    paint.setTextSize(Math.max(12, fontSize));
    String color = style.optString("titleColor", "#ffffff");
    if (color == null || !color.matches("#[0-9a-fA-F]{6}")) color = "#ffffff";
    try { paint.setColor(Color.parseColor(color)); } catch (Throwable t) { paint.setColor(Color.WHITE); }

    StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint,
            Math.max(40, maxWidth - padding * 2))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(true)
        .setLineSpacing(0f, 1.08f)
        .build();

    float widestLine = 1f;
    for (int line = 0; line < layout.getLineCount(); line++) {
      widestLine = Math.max(widestLine, layout.getLineWidth(line));
    }
    int bitmapW = Math.min(maxWidth, (int) Math.ceil(widestLine) + padding * 2);
    int bitmapH = layout.getHeight() + padding * 2;
    bitmapW = Math.max(2, bitmapW);
    bitmapH = Math.max(2, bitmapH);
    style.put("titleOverlayWidth", bitmapW);
    style.put("titleOverlayHeight", bitmapH);

    Bitmap bitmap;
    try {
      bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888);
    } catch (Throwable t) {
      throw new Exception("Title bitmap ဖန်တီးမရပါ: " + safeMsg(t));
    }
    Canvas canvas = new Canvas(bitmap);
    Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    box.setColor(Color.argb(72, 0, 0, 0));
    canvas.drawRoundRect(new RectF(0, 0, bitmapW, bitmapH),
        Math.max(4, padding / 2), Math.max(4, padding / 2), box);
    canvas.save();
    canvas.translate(padding, padding);
    layout.draw(canvas);
    canvas.restore();

    File out = new File(work, "title_overlay.png");
    try (FileOutputStream stream = new FileOutputStream(out)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
      stream.flush();
      stream.getFD().sync();
    } finally {
      try { bitmap.recycle(); } catch (Throwable ignored) {}
    }
    return out;
  }

  private int even(int n) { return Math.max(2, n & ~1); }
  private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
  private String fmt(double n) { return String.format(Locale.US, "%.6f", n); }
  private String fmtTime(long ms) {
    if (ms < 0) ms = 0;
    return String.format(Locale.US, "%02d မိနစ် %02d စက္ကန့်",
        TimeUnit.MILLISECONDS.toMinutes(ms), TimeUnit.MILLISECONDS.toSeconds(ms) % 60);
  }
  private String safeMsg(Throwable t) {
    if (t == null) return "unknown";
    String m = t.getMessage();
    return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
  }

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
    if (zoom > 1.001) {
      base += ",crop=w='iw/" + zoom + "':h='ih/" + zoom + "':x='(iw-ow)/2':y='(ih-oh)/2',scale=" + w + ":" + h;
    }

    if (s.optBoolean("flip", false)) base += ",hflip";
    base += ",fps=" + fps + ",setsar=1";

    double br = s.optDouble("brightness", 0) / 200.0;
    double ct = 1 + s.optDouble("contrast", 0) / 100.0;
    double sat = 1 + s.optDouble("saturation", 0) / 100.0;
    String preset = s.optString("filter", "cinematic");
    if (preset == null) preset = "cinematic";
    if ("mono".equals(preset)) sat = 0;
    if ("cinematic".equals(preset)) { ct *= 1.035; sat *= .96; }
    if ("vivid".equals(preset)) { ct *= 1.05; sat *= 1.16; }

    double inMin = Math.max(0.0, 0.5 - (ct / 2.0) + br);
    double inMax = Math.min(1.0, 0.5 + (ct / 2.0) + br);
    double inSat = Math.max(0.0, Math.min(1.0, sat));

    base += String.format(Locale.US,
        ",colorlevels=rimin=%.3f:gimin=%.3f:bimin=%.3f:rimax=%.3f:gimax=%.3f:bimax=%.3f",
        inMin, inMin, inMin, inMax, inMax, inMax);
    if (inSat != 1.0) base += String.format(Locale.US, ",hue=s=%.3f", inSat);

    int border = Math.max(0, Math.min(18, s.optInt("frameWidth", 0)));
    if (border > 0) {
      String color = s.optString("frameColor", "#d6ef47");
      if (color == null || !color.matches("#[0-9a-fA-F]{6}")) color = "#d6ef47";
      color = color.replace("#", "0x");
      base += ",drawbox=x=0:y=0:w=iw:h=ih:color=" + color + ":t=" + border;
    }
    return base;
  }

  private void progress(PluginCall c, int p, String stage) {
    try {
      JSObject d = new JSObject();
      d.put("progress", p);
      d.put("stage", stage);
      notifyListeners("renderProgress", d);
    } catch (Throwable ignored) {}
  }

  private void progressWithTime(PluginCall c, int p, String stage, String elapsed, String remaining) {
    try {
      JSObject d = new JSObject();
      d.put("progress", p);
      d.put("stage", stage);
      d.put("elapsed", elapsed);
      d.put("remaining", remaining);
      notifyListeners("renderProgress", d);
    } catch (Throwable ignored) {}
  }

  private Uri saveMovies(File f) throws Exception {
    if (f == null || !f.isFile() || f.length() <= 0) {
      throw new Exception("Output ဖိုင် မမှန်ကန်ပါ");
    }
    if (Build.VERSION.SDK_INT < 29) {
      File movies = getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
      if (movies == null) throw new Exception("Movies storage မရနိုင်ပါ");
      if (!movies.exists() && !movies.mkdirs()) throw new Exception("Movies folder မဖန်တီးနိုင်ပါ");
      File output = new File(movies, "SiGyi_Recap_" + System.currentTimeMillis() + ".mp4");
      copy(f, output);
      return Uri.fromFile(output);
    }
    ContentValues v = new ContentValues();
    v.put(MediaStore.Video.Media.DISPLAY_NAME, "SiGyi_Recap_" + System.currentTimeMillis() + ".mp4");
    v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
    v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SiGyi Recap Studio");
    v.put(MediaStore.Video.Media.IS_PENDING, 1);
    Uri u;
    try {
      u = getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
    } catch (Throwable t) {
      throw new Exception("Movies folder ထဲ ထည့်မရပါ: " + safeMsg(t));
    }
    if (u == null) throw new Exception("Movies folder ထဲ သိမ်းမရပါ");
    try (InputStream in = new BufferedInputStream(new FileInputStream(f), 4 * 1024 * 1024);
         OutputStream out = new BufferedOutputStream(
             getContext().getContentResolver().openOutputStream(u), 4 * 1024 * 1024)) {
      if (out == null) throw new Exception("Output stream မရနိုင်ပါ");
      byte[] b = new byte[4 * 1024 * 1024];
      int n;
      while ((n = in.read(b)) > 0) out.write(b, 0, n);
      out.flush();
    } catch (Throwable t) {
      try { getContext().getContentResolver().delete(u, null, null); } catch (Throwable ignored) {}
      throw new Exception("ဖိုင်ကူးမရပါ: " + safeMsg(t));
    }
    ContentValues ready = new ContentValues();
    ready.put(MediaStore.Video.Media.IS_PENDING, 0);
    try { getContext().getContentResolver().update(u, ready, null, null); } catch (Throwable ignored) {}
    return u;
  }

  private void copy(File a, File b) throws Exception {
    try (InputStream in = new BufferedInputStream(new FileInputStream(a), 4 * 1024 * 1024);
         OutputStream out = new BufferedOutputStream(new FileOutputStream(b), 4 * 1024 * 1024)) {
      byte[] x = new byte[4 * 1024 * 1024];
      int n;
      while ((n = in.read(x)) > 0) out.write(x, 0, n);
      out.flush();
    }
  }

  private void deleteTree(File f) {
    if (f == null || !f.exists()) return;
    if (f.isDirectory()) {
      File[] xs = f.listFiles();
      if (xs != null) for (File x : xs) deleteTree(x);
    }
    try { f.delete(); } catch (Throwable ignored) {}
  }

  @PluginMethod public void cancel(PluginCall call) {
    cancelled = true;
    try { FFmpegKit.cancel(); } catch (Throwable ignored) {}
    try { call.resolve(); } catch (Throwable ignored) {}
  }
      }
