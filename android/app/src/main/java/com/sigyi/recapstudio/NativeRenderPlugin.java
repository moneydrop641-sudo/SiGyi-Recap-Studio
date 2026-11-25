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

@CapacitorPlugin(name = "NativeRender")
public class NativeRenderPlugin extends Plugin {
  private volatile boolean cancelled = false;
  private volatile FFmpegSession activeSession;
  private volatile boolean isRendering = false;

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

  @PluginMethod public void render(PluginCall call) {
    if (isRendering) { call.reject("Render လုပ်ဆောင်နေဆဲဖြစ်ပါတယ်။"); return; }
    
    String vp = call.getString("videoPath"), ap = call.getString("audioPath"), lp = call.getString("logoPath");
    Boolean isLandscape = call.getBoolean("landscape", false);
    
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
    
    int totalParts = 0;
    int currentGlobalPart = 1;
    long startTime = System.currentTimeMillis();
    
    try {
      double vDuration = probe(source);
      double aDuration = voice != null ? probe(voice) : vDuration;
      double finalDuration = voice != null ? aDuration : vDuration;

      if (finalDuration <= 0) throw new Exception("Duration မဖတ်နိုင်ပါ။");

      String res = call.getString("resolution", "720p");
      int w, h, fps = 30, crf = 28;
      if ("360p".equals(res)) { w = isLandscape ? 640 : 360; h = isLandscape ? 360 : 640; fps = 18; crf = 34; }
      else if ("1080p".equals(res)) { w = isLandscape ? 1920 : 1080; h = isLandscape ? 1080 : 1920; crf = 27; }
      else { w = isLandscape ? 1280 : 720; h = isLandscape ? 720 : 1280; }

      JSObject style = call.getObject("style"); if (style == null) style = new JSObject();
      File titleOverlay = null; String titleText = style.optString("titleText", "");
      if (!titleText.trim().isEmpty()) titleOverlay = createTitleOverlay(work, titleText, style, w, h);
      
      // ✅ စုစုပေါင်း Part အရေအတွက်ကို 1 မိနစ်နဲ့ တွက်မယ်
      int partSeconds = 60; 
      int totalPartsCount = (int)Math.ceil(finalDuration / partSeconds);
      
      List<File> allParts = new ArrayList<>();
      
      for (int p = 0; p < totalPartsCount; p++) {
        double partStart = p * partSeconds;
        double partLen = Math.min(partSeconds, finalDuration - partStart);
        
        // ✅ Part တစ်ခုကို 4 chunk ခွဲမယ် (15 စက္ကန့်စီ)
        int chunkCount = 4;
        double chunkLen = partLen / chunkCount;
        List<File> chunkFiles = new ArrayList<>();
        
        for (int c = 0; c < chunkCount; c++) {
            if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
            
            double chunkStart = partStart + (c * chunkLen);
            File chunkFile = new File(work, String.format(Locale.US, "p%d_c%d.mp4", p + 1, c + 1));
            
            List<String> a = new ArrayList<>(Arrays.asList("-y"));
            a.addAll(Arrays.asList("-ss", fmt(chunkStart), "-t", fmt(chunkLen), "-i", source.getAbsolutePath()));
            if (voice != null) a.addAll(Arrays.asList("-ss", fmt(chunkStart), "-t", fmt(chunkLen), "-i", voice.getAbsolutePath()));
            
            int nextInput = voice != null ? 2 : 1; int logoInput = -1, titleInput = -1;
            if (logo != null) { logoInput = nextInput++; a.addAll(Arrays.asList("-loop", "1", "-i", logo.getAbsolutePath())); }
            if (titleOverlay != null) { titleInput = nextInput; a.addAll(Arrays.asList("-loop", "1", "-i", titleOverlay.getAbsolutePath())); }
            
            double partAudioDur = voice != null ? Math.min(chunkLen, aDuration - chunkStart) : chunkLen;
            double partVideoDur = Math.min(chunkLen, vDuration - chunkStart);
            double speedFactor = 1.0;
            if (voice != null && partVideoDur < partAudioDur && partVideoDur > 0) {
                speedFactor = partVideoDur / partAudioDur;
                if (speedFactor < 0.5) speedFactor = 0.5;
            }
            
            String filterComplex = buildFilterGraph(style, w, h, fps, partAudioDur, c, logoInput, logo, titleInput, titleOverlay, w, h, isLandscape, speedFactor);
            a.addAll(Arrays.asList("-filter_complex", filterComplex, "-map", "[vout]"));
            
            if (voice != null) a.addAll(Arrays.asList("-map", "1:a:0", "-c:a", "aac", "-b:a", "128k"));
            else a.addAll(Arrays.asList("-map", "0:a?"));
            
            a.addAll(Arrays.asList("-c:v", "mpeg4", "-q:v", "5", "-threads", "4", "-pix_fmt", "yuv420p", "-movflags", "+faststart", chunkFile.getAbsolutePath()));
            
            int progressBase = 10 + (int)(((p * chunkCount + c) / (double)(totalPartsCount * chunkCount)) * 78);
            int progressSpan = Math.max(1, (int)(78.0 / (totalPartsCount * chunkCount)));
            
            activeSession = executeTracked(a.toArray(new String[0]), call, progressBase, progressSpan, partAudioDur, "Part " + (p+1) + "/" + totalPartsCount + " (Chunk " + (c+1) + "/" + chunkCount + ") ...", startTime, finalDuration, p * chunkCount + c, totalPartsCount * chunkCount);
            
            if (activeSession == null || !ReturnCode.isSuccess(activeSession.getReturnCode())) {
                String logs = activeSession != null ? activeSession.getAllLogsAsString() : null;
                throw new Exception("Chunk မအောင်မြင်ပါ။ \n" + (logs != null ? logs : ""));
            }
            chunkFiles.add(chunkFile);
        }
        
        // ✅ Chunk 4 ခုကို Part တစ်ခုအဖြစ် ပြန်ပေါင်းမယ်
        File partFile = new File(work, String.format(Locale.US, "part_%03d.mp4", p + 1));
        File chunkList = new File(work, String.format(Locale.US, "chunks_%03d.txt", p + 1));
        try (FileOutputStream out = new FileOutputStream(chunkList)) {
            for (File f : chunkFiles) out.write(("file '" + f.getAbsolutePath() + "'\n").getBytes("UTF-8"));
        }
        activeSession = executeTracked(new String[]{"-y", "-f", "concat", "-safe", "0", "-i", chunkList.getAbsolutePath(), "-c", "copy", partFile.getAbsolutePath()}, call, 88, 2, partLen, "Part " + (p+1) + " ပေါင်းနေပါတယ်...", startTime, finalDuration, p, totalPartsCount);
        if (activeSession == null || !ReturnCode.isSuccess(activeSession.getReturnCode())) throw new Exception("Part ပေါင်းမရပါ။");
        
        for (File f : chunkFiles) f.delete();
        chunkList.delete();
        allParts.add(partFile);
      }
      
      // ✅ အားလုံးကို ပေါင်းပြီး နောက်ဆုံး ဗီဒီယို ထုတ်မယ်
      progress(call, 92, "အားလုံးကို ပေါင်းနေပါတယ်...");
      File finalList = new File(work, "all_parts.txt");
      try (FileOutputStream out = new FileOutputStream(finalList)) {
          for (File f : allParts) out.write(("file '" + f.getAbsolutePath() + "'\n").getBytes("UTF-8"));
      }
      File merged = new File(getContext().getCacheDir(), "sigyi_preview_final.mp4");
      if (merged.exists()) merged.delete();
      
      activeSession = executeTracked(new String[]{"-y", "-f", "concat", "-safe", "0", "-i", finalList.getAbsolutePath(), "-c", "copy", "-movflags", "+faststart", merged.getAbsolutePath()}, call, 90, 6, finalDuration, "ပေါင်းနေပါတယ်...", startTime, finalDuration, totalPartsCount-1, totalPartsCount);
      
      if (activeSession == null || !ReturnCode.isSuccess(activeSession.getReturnCode())) throw new Exception("Merge မအောင်မြင်ပါ။");
      
      for (File f : allParts) f.delete(); finalList.delete();
      progress(call, 96, "Movies folder ထဲ သိမ်းနေပါတယ်…"); 
      Uri saved = saveMovies(merged);
      
      JSObject result = new JSObject(); result.put("savedUri", saved.toString()); result.put("previewPath", merged.getAbsolutePath()); result.put("name", "SiGyi_Recap.mp4"); result.put("size", merged.length());
      progress(call, 100, "Render ပြီးပါပြီ"); 
      call.resolve(result);
      
    } catch (Throwable t) { 
      call.reject(t.getMessage() == null ? "Render မအောင်မြင်ပါ" : t.getMessage()); 
    } finally { 
      deleteTree(work); activeSession = null; isRendering = false; 
    }
  }

  private FFmpegSession executeTracked(String[] args, PluginCall call, int base, int span, double durationSeconds, String stageText, long startTime, double totalDuration, int currentChunkIndex, int totalChunks) throws Exception {
    CountDownLatch done = new CountDownLatch(1); final FFmpegSession[] result = {null};
    final long[] lastNotify = {0};
    
    FFmpegKit.executeWithArgumentsAsync(args, session -> { result[0] = session; done.countDown(); }, null, stats -> {
      if (durationSeconds > 0) {
        long now = System.currentTimeMillis();
        if (now - lastNotify[0] > 800) {
          lastNotify[0] = now;
          double seconds = stats.getTime() / 1000.0;
          int pct = base + (int)(Math.min(1.0, seconds / durationSeconds) * span);
          
          long elapsed = now - startTime;
          double progressRatio = (currentChunkIndex + (seconds / durationSeconds)) / totalChunks;
          long estimatedTotal = (long)(elapsed / Math.max(0.01, progressRatio));
          long remaining = Math.max(0, estimatedTotal - elapsed);
          
          String timeStr = String.format(Locale.US, "%02d မိနစ် %02d စက္ကန့်", TimeUnit.MILLISECONDS.toMinutes(elapsed), TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60);
          String remStr = String.format(Locale.US, "%02d မိနစ် %02d စက္ကန့်", TimeUnit.MILLISECONDS.toMinutes(remaining), TimeUnit.MILLISECONDS.toSeconds(remaining) % 60);

          progressWithTime(call, Math.min(98, pct), stageText, timeStr, remStr);
        }
      }
    });
    
    while (!done.await(500, TimeUnit.MILLISECONDS)) { if (cancelled) { FFmpegKit.cancel(); } }
    if (result[0] == null) throw new Exception("FFmpeg session error");
    return result[0];
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

  private String buildFilterGraph(JSObject s, int w, int h, int fps, double seconds, int partIndex, int logoInput, File logo, int titleInput, File titleOverlay, int outW, int outH, boolean isLandscape, double speedFactor) throws Exception {
    String base = videoFilter(s, w, h, fps, seconds, partIndex, isLandscape, speedFactor);
    String current = "base";
    StringBuilder g = new StringBuilder("[0:v]").append(base).append("[base];");
    
    if (s.optBoolean("blurEnabled", false)) {
        int bw = (int)(w * clamp(s.optDouble("blurW", 84), 5, 100) / 100.0);
        int bh = (int)(h * clamp(s.optDouble("blurH", 12), 3, 40) / 100.0);
        int bx = (int)(w * clamp(s.optDouble("blurX", 8), 0, 90) / 100.0);
        int by = (int)(h * clamp(s.optDouble("blurY", 72), 0, 90) / 100.0);
        
        g.append("[base]drawbox=x=").append(bx).append(":y=").append(by)
         .append(":w=").append(bw).append(":h=").append(bh)
         .append(":color=black:t=fill[boxout];");
        current = "boxout";
    }
    
    if (logo != null && logoInput >= 0) {
      int logoW = (int)(w * clamp(s.optDouble("logoSize", 16), 5, 35) / 100.0);
      int margin = Math.max(8, w / 60);
      g.append("[").append(logoInput).append(":v]format=rgba,scale=").append(logoW).append(":-1[brandlogo];[").append(current).append("][brandlogo]overlay=x=W-w-").append(margin).append(":y=").append(margin).append(":eof_action=pass:repeatlast=1[logoout];");
      current = "logoout";
    }
    
    if (titleOverlay != null && titleInput >= 0) {
      int titleW = (int)clamp(s.optDouble("titleOverlayWidth", w * 0.88), 2, w);
      int tx = (int)((w - titleW) * clamp(s.optDouble("titleX", 8), 0, 100) / 100.0);
      int titleH = (int)clamp(s.optDouble("titleOverlayHeight", h * 0.2), 2, h);
      int ty = (int)((h - titleH) * clamp(s.optDouble("titleY", 12), 0, 100) / 100.0);
      g.append("[").append(titleInput).append(":v]format=rgba[titleimg];[").append(current).append("][titleimg]overlay=x=").append(tx).append(":y=").append(ty).append(":eof_action=pass:repeatlast=1[titleout];");
      current = "titleout";
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

  private int even(int n) { return Math.max(2, n & ~1); }
  private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

  private String videoFilter(JSObject s, int w, int h, int fps, double seconds, int partIndex, boolean isLandscape, double speedFactor) {
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
    
    if (speedFactor < 1.0) {
        base += ",setpts=" + String.format(Locale.US, "%.4f", 1.0/speedFactor) + "*PTS";
    }

    if (isLandscape) {
        base += ",tpad=stop_mode=clone:stop_duration=5";
    }

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

  private String fmt(double n) { return String.format(Locale.US, "%.3f", n); }
  
  private void progress(PluginCall c, int p, String stage) {
      JSObject d = new JSObject(); d.put("progress", p); d.put("stage", stage); 
      notifyListeners("renderProgress", d);
  }

  private void progressWithTime(PluginCall c, int p, String stage, String elapsed, String remaining) {
      JSObject d = new JSObject(); d.put("progress", p); d.put("stage", stage); 
      d.put("elapsed", elapsed); d.put("remaining", remaining);
      notifyListeners("renderProgress", d);
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
  
  @PluginMethod public void cancel(PluginCall call) { cancelled = true; FFmpegKit.cancel(); call.resolve(); }
}
