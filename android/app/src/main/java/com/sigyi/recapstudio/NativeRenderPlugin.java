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
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "NativeRender")
public class NativeRenderPlugin extends Plugin {
  private volatile boolean cancelled = false;
  private volatile FFmpegSession activeSession;

  @PluginMethod public void pickVideo(PluginCall call) { pick(call, "video/*", "video"); }
  @PluginMethod public void pickAudio(PluginCall call) { pick(call, "audio/*", "audio"); }
  @PluginMethod public void pickLogo(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*");
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(call, i, result -> {
      if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) { call.reject("Logo ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။"); return; }
      Uri uri = result.getData().getData();
      try {
        getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String name = displayName(uri).replaceAll("[^A-Za-z0-9._-]", "_");
        File dir = new File(getContext().getFilesDir(), "media"); if (!dir.exists() && !dir.mkdirs()) throw new Exception("Logo storage မဖန်တီးနိုင်ပါ");
        File target = new File(dir, "logo_" + System.currentTimeMillis() + "_" + name);
        try (InputStream in = getContext().getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
          if (in == null) throw new Exception("Logo ဖိုင်ဖတ်မရပါ"); byte[] b = new byte[256 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); out.getFD().sync();
        }
        JSObject r = new JSObject(); r.put("path", target.getAbsolutePath()); r.put("name", displayName(uri)); r.put("size", target.length()); r.put("mimeType", getContext().getContentResolver().getType(uri)); call.resolve(r);
      } catch (Exception e) { call.reject("Logo ဖိုင်ကို ကူးမရပါ: " + e.getMessage()); }
    });
  }
  private void pick(PluginCall call, String mime, String kind) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(mime);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(call, i, result -> {
      if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) { call.reject("ဖိုင်ရွေးချယ်မှုကို ပယ်ဖျက်လိုက်ပါပြီ။"); return; }
      Uri uri = result.getData().getData();
      try {
        getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String displayName = displayName(uri);
        if ("audio".equals(kind) && !(displayName.toLowerCase(Locale.US).endsWith(".mp3") || displayName.toLowerCase(Locale.US).endsWith(".wav"))) throw new Exception("MP3 သို့မဟုတ် WAV ဖိုင်သာ ရွေးပါ။");
        String name = displayName.replaceAll("[^A-Za-z0-9._-]", "_");
        File dir = new File(getContext().getFilesDir(), "media"); if (!dir.exists() && !dir.mkdirs()) throw new Exception("App storage မဖန်တီးနိုင်ပါ");
        File[] previous = dir.listFiles(); if (previous != null) for (File old : previous) if (old.getName().startsWith(kind + "_")) old.delete();
        File target = new File(dir, kind + "_" + System.currentTimeMillis() + "_" + name);
        try (InputStream in = getContext().getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
          if (in == null) throw new Exception("ဖိုင်ဖတ်မရပါ"); byte[] b = new byte[1024 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); out.getFD().sync();
        }
        JSObject r = new JSObject(); r.put("path", target.getAbsolutePath()); r.put("name", displayName(uri)); r.put("size", target.length()); r.put("mimeType", getContext().getContentResolver().getType(uri)); call.resolve(r);
      } catch (Exception e) { call.reject("ဖိုင်ကို app storage ထဲ ကူးမရပါ: " + e.getMessage()); }
    });
  }
  private String displayName(Uri uri) {
    try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) { if (c != null && c.moveToFirst()) { int n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (n >= 0) return c.getString(n); } } catch (Exception ignored) {} return "media";
  }

  @PluginMethod public void render(PluginCall call) {
    String vp = call.getString("videoPath"), ap = call.getString("audioPath"), lp = call.getString("logoPath");
    if (vp == null || !new File(vp).isFile()) { call.reject("Video ဖိုင်မတွေ့ပါ။ ပြန်ရွေးပါ။"); return; }
    if (ap != null && !new File(ap).isFile()) { call.reject("Dubbing audio ဖိုင်မတွေ့ပါ။ ပြန်ရွေးပါ။"); return; }
    if (lp != null && !new File(lp).isFile()) { call.reject("Logo ဖိုင်မတွေ့ပါ။ ပြန်ရွေးပါ။"); return; }
    cancelled = false;
    new Thread(() -> renderWorker(call, new File(vp), ap == null ? null : new File(ap), lp == null ? null : new File(lp)), "SiGyiNativeRender").start();
  }
  private void renderWorker(PluginCall call, File source, File voice, File logo) {
    File work = new File(getContext().getCacheDir(), "sigyi_job_" + System.currentTimeMillis());
    if (!work.mkdirs()) { call.reject("Render cache folder မဖန်တီးနိုင်ပါ။ Storage လွတ်နေမှုကို စစ်ပါ။"); return; }
    try {
      double duration = probe(source); if (duration <= 0) throw new Exception("Video duration ကို မဖတ်နိုင်ပါ။");
      String res = call.getString("resolution", "720p"); int w = 720, h = 1280, fps = 24, crf = 28;
      if ("360p".equals(res)) { w=360; h=640; fps=18; crf=34; } else if ("1080p".equals(res)) { w=1080; h=1920; crf=27; }
      JSObject style = call.getObject("style"); if (style == null) style = new JSObject();
      File titleOverlay = null; String titleText = style.optString("titleText", "");
      if (!titleText.trim().isEmpty()) titleOverlay = createTitleOverlay(work, titleText, style, w, h);
      int chunkSeconds = 300; int count = (int)Math.ceil(duration / chunkSeconds);
      List<File> parts = new ArrayList<>();
      for (int p=0; p<count; p++) {
        if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
        double start=p*chunkSeconds, len=Math.min(chunkSeconds,duration-start); File part=new File(work,String.format(Locale.US,"part_%03d.mp4",p+1));
        List<String> a=new ArrayList<>(Arrays.asList("-y","-ss",fmt(start),"-i",source.getAbsolutePath()));
        if (voice!=null) a.addAll(Arrays.asList("-ss",fmt(start),"-i",voice.getAbsolutePath()));
        int nextInput = voice != null ? 2 : 1; int logoInput = -1, titleInput = -1;
        if (logo != null) { logoInput = nextInput++; a.addAll(Arrays.asList("-loop","1","-i",logo.getAbsolutePath())); }
        if (titleOverlay != null) { titleInput = nextInput; a.addAll(Arrays.asList("-loop","1","-i",titleOverlay.getAbsolutePath())); }
        a.addAll(Arrays.asList("-t",fmt(len),"-filter_complex",buildFilterGraph(style,w,h,fps,len,p,logoInput,logo,titleInput,titleOverlay,w,h),"-map","[vout]"));
        if (voice!=null) a.addAll(Arrays.asList("-map","1:a:0?","-af","aresample=async=1:first_pts=0,apad")); else a.addAll(Arrays.asList("-map","0:a?"));
        a.addAll(Arrays.asList("-c:v","libopenh264","-b:v",(resBitrate(crf)),"-threads","2","-pix_fmt","yuv420p","-c:a","aac","-b:a","128k","-movflags","+faststart",part.getAbsolutePath()));
        activeSession=executeTracked(a.toArray(new String[0]), call, 10 + (int)((p/(double)count)*78), Math.max(1, (int)(78.0/count)), len);
        if (cancelled) throw new Exception("Render ကို ပယ်ဖျက်လိုက်ပါပြီ။");
        if (!ReturnCode.isSuccess(activeSession.getReturnCode())) throw new Exception("Part "+(p+1)+" render မအောင်မြင်ပါ: "+activeSession.getFailStackTrace());
        parts.add(part); progress(call,10+(int)(((p+1.0)/count)*78),"Part "+(p+1)+"/"+count+" ပြီးပါပြီ");
      }
      File list=new File(work,"parts.txt"); try(FileOutputStream out=new FileOutputStream(list)){ for(File f:parts) out.write(("file '"+f.getAbsolutePath()+"'\n").getBytes("UTF-8")); }
      progress(call,92,"Part အားလုံးကို Merge လုပ်နေပါတယ်…"); File merged=new File(getContext().getCacheDir(),"sigyi_preview_final.mp4"); if (merged.exists()) merged.delete();
      activeSession=executeTracked(new String[]{"-y","-f","concat","-safe","0","-i",list.getAbsolutePath(),"-c","copy","-movflags","+faststart",merged.getAbsolutePath()}, call, 90, 6, duration);
      if(!ReturnCode.isSuccess(activeSession.getReturnCode())) throw new Exception("Merge မအောင်မြင်ပါ: "+activeSession.getFailStackTrace());
      for (File partFile : parts) partFile.delete(); list.delete();
      progress(call,96,"Movies folder ထဲ သိမ်းနေပါတယ်…"); Uri saved=saveMovies(merged);
      File preview=merged;
      JSObject result=new JSObject(); result.put("savedUri",saved.toString()); result.put("previewPath",preview.getAbsolutePath()); result.put("name","SiGyi_Recap.mp4"); result.put("size",merged.length());
      progress(call,100,"Render ပြီးပါပြီ — Movies/SiGyi Recap Studio ထဲ သိမ်းပြီးပါပြီ။"); call.resolve(result);
    } catch(Exception e){ call.reject(e.getMessage()==null?"Native render မအောင်မြင်ပါ":e.getMessage()); }
    finally { deleteTree(work); activeSession=null; }
  }
  private FFmpegSession executeTracked(String[] args, PluginCall call, int base, int span, double durationSeconds) throws Exception {
    CountDownLatch done = new CountDownLatch(1); final FFmpegSession[] result = {null};
    FFmpegKit.executeWithArgumentsAsync(args, session -> { result[0] = session; done.countDown(); }, null, stats -> {
      if (durationSeconds > 0) {
        double seconds = stats.getTime() / 1000.0;
        int pct = base + (int)(Math.min(1.0, seconds / durationSeconds) * span);
        progress(call, Math.min(98, pct), "Native FFmpeg render လုပ်နေပါတယ်…");
      }
    });
    while (!done.await(500, TimeUnit.MILLISECONDS)) { if (cancelled) { FFmpegKit.cancel(); } }
    if (result[0] == null) throw new Exception("FFmpeg session result မရပါ။");
    return result[0];
  }
  private double probe(File f) throws Exception {
    CountDownLatch latch=new CountDownLatch(1); final double[] d={0};
    FFprobeKit.getMediaInformationAsync(f.getAbsolutePath(), s->{ try { if(s.getMediaInformation()!=null) d[0]=Double.parseDouble(s.getMediaInformation().getDuration()); } catch(Exception ignored){} latch.countDown(); });
    latch.await(30,TimeUnit.SECONDS); return d[0];
  }
  private String buildFilterGraph(JSObject s,int w,int h,int fps,double seconds,int partIndex,int logoInput,File logo,int titleInput,File titleOverlay,int outW,int outH) throws Exception {
    String base = videoFilter(s,w,h,fps,seconds,partIndex);
    String current = "base";
    StringBuilder g = new StringBuilder("[0:v]").append(base).append("[base];");
    if (s.optBoolean("blurEnabled", false)) {
      int bw = even((int)(w * clamp(s.optDouble("blurW",84),5,100) / 100.0));
      int bh = even((int)(h * clamp(s.optDouble("blurH",12),3,40) / 100.0));
      int bx = even((int)(w * clamp(s.optDouble("blurX",8),0,90) / 100.0));
      int by = even((int)(h * clamp(s.optDouble("blurY",72),0,90) / 100.0));
      bw = Math.max(2, Math.min(w - bx, bw)); bh = Math.max(2, Math.min(h - by, bh));
      int sigma = (int)clamp(s.optDouble("blurStrength",18),2,40);
      g.append("[base]split=2[mainv][blursrc];[blursrc]crop=").append(bw).append(":").append(bh).append(":").append(bx).append(":").append(by).append(",boxblur=").append(sigma).append(":1[blurpatch];[mainv][blurpatch]overlay=").append(bx).append(":").append(by).append(":shortest=1[blurout];");
      current = "blurout";
    }
    if (logo != null && logoInput >= 0) {
      int logoW = (int)(w * clamp(s.optDouble("logoSize",16),5,35) / 100.0);
      int margin = Math.max(8, w / 60);
      g.append("[").append(logoInput).append(":v]format=rgba,scale=").append(logoW).append(":-1[brandlogo];[").append(current).append("][brandlogo]overlay=x=W-w-").append(margin).append(":y=").append(margin).append(":eof_action=pass:repeatlast=1[logoout];");
      current = "logoout";
    }
    if (titleOverlay != null && titleInput >= 0) {
      int titleW = (int)clamp(s.optDouble("titleOverlayWidth", w * 0.88), 2, w);
      int tx = (int)((w - titleW) * clamp(s.optDouble("titleX",8),0,100) / 100.0);
      int titleH = (int)clamp(s.optDouble("titleOverlayHeight", h * 0.2), 2, h);
      int ty = (int)((h - titleH) * clamp(s.optDouble("titleY",12),0,100) / 100.0);
      g.append("[").append(titleInput).append(":v]format=rgba[titleimg];[").append(current).append("][titleimg]overlay=x=").append(tx).append(":y=").append(ty).append(":eof_action=pass:repeatlast=1[titleout];");
      current = "titleout";
    }
    g.append("[").append(current).append("]null[vout]");
    return g.toString();
  }
  private File createTitleOverlay(File work,String text,JSObject style,int frameW,int frameH) throws Exception {
    int fontSize = (int)(clamp(style.optDouble("titleSize",34),16,100) * frameH / 1280.0);
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
  private int even(int n){ return Math.max(2, n & ~1); }
  private double clamp(double v,double min,double max){ return Math.max(min,Math.min(max,v)); }
  private String safeColor(String c){ return c != null && c.matches("#[0-9a-fA-F]{6}") ? "0x"+c.substring(1) : "white"; }
  private String videoFilter(JSObject s,int w,int h,int fps,double seconds,int partIndex) {
    String base;
    if("fit".equals(s.optString("fitMode","fit"))) base="scale="+w+":"+h+":force_original_aspect_ratio=decrease,pad="+w+":"+h+":(ow-iw)/2:(oh-ih)/2:color=black";
    else {
      String cropX = "left".equals(s.optString("crop", "center")) ? "0" : "right".equals(s.optString("crop", "center")) ? "iw-ow" : "(iw-ow)/2";
      base="crop=w='min(iw,ih*9/16)':h='min(ih,iw*16/9)':x='"+cropX+"':y='(ih-oh)/2',scale="+w+":"+h;
    }
    double zoom=Math.max(1,Math.min(1.12,s.optDouble("zoom",1.02)));
    if(zoom>1.001) base+=",crop=w='iw/"+zoom+"':h='ih/"+zoom+"':x='(iw-ow)/2':y='(ih-oh)/2',scale="+w+":"+h;
    base+=",zoompan=z='1.035+0.035*sin(on/"+(fps*5)+"*PI)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s="+w+"x"+h+":fps="+fps;
    if(s.optBoolean("flip",false)) base+=",hflip"; base+=",fps="+fps+",setsar=1";
    int total=(int)Math.ceil(seconds*fps); if(partIndex>0) base+=",freezeframes=first=0:last=5:replace=0"; for(int fr=5*fps;fr+5<total;fr+=5*fps) base+=",freezeframes=first="+fr+":last="+(fr+5)+":replace="+fr;
    double br=s.optDouble("brightness",0)/200.0, ct=1+s.optDouble("contrast",0)/100.0, sat=1+s.optDouble("saturation",0)/100.0; String preset=s.optString("filter","cinematic");
    if("mono".equals(preset))sat=0; if("cinematic".equals(preset)){ct*=1.035;sat*=.96;} if("vivid".equals(preset)){ct*=1.05;sat*=1.16;}
    base += String.format(Locale.US,",eq=brightness=%.3f:contrast=%.3f:saturation=%.3f",br,ct,sat);
    int border = Math.max(0, Math.min(18, s.optInt("frameWidth", 0)));
    if (border > 0) { String color = s.optString("frameColor", "#d6ef47").replace("#", "0x"); base += ",drawbox=x=0:y=0:w=iw:h=ih:color="+color+":t="+border; }
    return base;
  }
  private String resBitrate(int crf){ return crf >= 34 ? "700k" : crf >= 28 ? "1800k" : "3500k"; }
  private String fmt(double n){return String.format(Locale.US,"%.3f",n);}
  private void progress(PluginCall c,int p,String stage){JSObject d=new JSObject();d.put("progress",p);d.put("stage",stage);notifyListeners("renderProgress",d);}
  private Uri saveMovies(File f)throws Exception{
    if (Build.VERSION.SDK_INT < 29) {
      File movies = getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
      if (movies == null) throw new Exception("Movies storage မရနိုင်ပါ");
      File output = new File(movies, "SiGyi_Recap_" + System.currentTimeMillis() + ".mp4"); copy(f, output); return Uri.fromFile(output);
    }
    ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,"SiGyi_Recap_"+System.currentTimeMillis()+".mp4");v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
    v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/SiGyi Recap Studio"); v.put(MediaStore.Video.Media.IS_PENDING,1);
    Uri u=getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception("Movies folder ထဲ သိမ်းမရပါ");
    try(InputStream in=new FileInputStream(f);OutputStream out=getContext().getContentResolver().openOutputStream(u)){byte[]b=new byte[1024*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}
    ContentValues ready=new ContentValues(); ready.put(MediaStore.Video.Media.IS_PENDING,0); getContext().getContentResolver().update(u,ready,null,null); return u;
  }
  private void copy(File a,File b)throws Exception{try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){byte[]x=new byte[1024*1024];int n;while((n=in.read(x))>0)out.write(x,0,n);}}
  private void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[]xs=f.listFiles();if(xs!=null)for(File x:xs)deleteTree(x);}f.delete();}
  @PluginMethod public void cancel(PluginCall call){cancelled=true;FFmpegKit.cancel();call.resolve();}
}
