package com.sigyi.recapstudio;

import android.app.DownloadManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ActivityNotFoundException;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

@CapacitorPlugin(name = "GeminiDownloadBridge")
public class GeminiDownloadBridgePlugin extends Plugin {
    private DownloadManager downloadManager;
    private BroadcastReceiver receiver;
    private Dialog geminiDialog;
    private WebView geminiWebView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private static GeminiDownloadBridgePlugin activePlugin;
    private static final int FILE_CHOOSER_REQUEST = 7341;

    @Override public void load() {
        activePlugin = this;
        downloadManager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id < 0) return;
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (Cursor cursor = downloadManager.query(query)) {
                    if (cursor == null || !cursor.moveToFirst()) return;
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                        notifyListeners("downloadError", new JSObject().put("message", "Download failed"));
                        return;
                    }
                    Uri uri = downloadManager.getUriForDownloadedFile(id);
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    String mime = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                    try (InputStream in = getContext().getContentResolver().openInputStream(uri);
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        if (in == null) throw new IllegalStateException("Cannot open downloaded file");
                        byte[] buffer = new byte[48 * 1024]; int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        notifyFile(name, mime, out.toByteArray());
                    }
                } catch (Exception e) {
                    notifyListeners("downloadError", new JSObject().put("message", e.getMessage()));
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else getContext().registerReceiver(receiver, filter);
        // Keep the original Capacitor WebView download path active as a fallback.
        getBridge().getWebView().setDownloadListener((url, userAgent, disposition, mime, length) -> enqueueDownload(url, userAgent, disposition, mime));
    }

    @PluginMethod public void openGeminiWebView(PluginCall call) {
        String url = call.getString("url");
        if (url == null) url = "";
        Uri parsed = Uri.parse(url);
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null || !(parsed.getHost().equalsIgnoreCase("gemini.google.com") || parsed.getHost().toLowerCase(Locale.ROOT).endsWith(".gemini.google.com"))) {
            call.reject("Only https://gemini.google.com links are allowed"); return;
        }
        final String targetUrl = url;
        getActivity().runOnUiThread(() -> {
            try {
                showGeminiDialog(targetUrl);
                call.resolve(new JSObject().put("opened", true));
            } catch (Exception e) { call.reject("Gemini WebView could not open: " + e.getMessage()); }
        });
    }

    private void showGeminiDialog(String url) {
        if (geminiDialog != null && geminiDialog.isShowing()) geminiDialog.dismiss();
        geminiDialog = new Dialog(getActivity());
        geminiDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(getActivity()); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(12, 18, 31));
        LinearLayout bar = new LinearLayout(getActivity()); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(18, 10, 12, 10); bar.setBackgroundColor(Color.rgb(20, 30, 48));
        TextView title = new TextView(getActivity()); title.setText("✦  SI GYI  ·  GEMINI WORKSPACE"); title.setTextColor(Color.WHITE); title.setTextSize(14); title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(getActivity()); close.setText("CLOSE"); close.setTextSize(11); close.setTextColor(Color.rgb(159, 246, 228)); close.setBackgroundColor(Color.TRANSPARENT);
        bar.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)); root.addView(bar);
        ProgressBar progress = new ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4));
        geminiWebView = new WebView(getActivity());
        WebSettings settings = geminiWebView.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setDatabaseEnabled(true); settings.setLoadsImagesAutomatically(true); settings.setJavaScriptCanOpenWindowsAutomatically(true); settings.setSupportMultipleWindows(false); settings.setMediaPlaybackRequiresUserGesture(false); settings.setAllowFileAccess(false); settings.setAllowContentAccess(true);
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(geminiWebView, true);
        geminiWebView.addJavascriptInterface(new BlobDownloadBridge(), "SiGyiGeminiFiles");
        geminiWebView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) { progress.setProgress(newProgress); progress.setVisibility(newProgress >= 100 ? android.view.View.GONE : android.view.View.VISIBLE); }
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*", "application/x-subrip", "text/plain"});
                try { getActivity().startActivityForResult(Intent.createChooser(intent, "Gemini အတွက် Video / WAV / SRT ရွေးပါ"), FILE_CHOOSER_REQUEST); }
                catch (ActivityNotFoundException e) { fileChooserCallback = null; callback.onReceiveValue(null); }
                return true;
            }
        });
        geminiWebView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl(); String host = target.getHost();
                if (host == null || !isGoogleWebHost(host)) { notifyListeners("downloadError", new JSObject().put("message", "Blocked non-Google navigation inside Gemini view")); return true; }
                return false;
            }
            @Override public void onPageFinished(WebView view, String pageUrl) { super.onPageFinished(view, pageUrl); injectBlobDownloadHook(view); }
        });
        geminiWebView.setDownloadListener((downloadUrl, userAgent, disposition, mime, length) -> enqueueDownload(downloadUrl, userAgent, disposition, mime));
        root.addView(geminiWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        geminiDialog.setContentView(root); Window window = geminiDialog.getWindow(); if (window != null) { window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); window.setBackgroundDrawableResource(android.R.color.transparent); }
        close.setOnClickListener(v -> geminiDialog.dismiss());
        geminiDialog.setOnDismissListener(d -> { if (geminiWebView != null) { geminiWebView.stopLoading(); geminiWebView.destroy(); geminiWebView = null; } });
        geminiDialog.show(); if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        geminiWebView.loadUrl(url);
    }

    public static boolean dispatchActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST || activePlugin == null) return false;
        activePlugin.handleFileChooserResult(resultCode, data);
        return true;
    }

    private void handleFileChooserResult(int resultCode, Intent data) {
        if (fileChooserCallback == null) return;
        Uri uri = resultCode == android.app.Activity.RESULT_OK && data != null ? data.getData() : null;
        ValueCallback<Uri[]> callback = fileChooserCallback; fileChooserCallback = null;
        callback.onReceiveValue(uri == null ? null : new Uri[]{uri});
        if (uri != null) importPickedFile(uri);
    }

    private void importPickedFile(Uri uri) {
        try {
            String mime = getContext().getContentResolver().getType(uri);
            String name = "gemini-upload";
            try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) { int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0) name = c.getString(col); }
            }
            if (mime == null) mime = name.toLowerCase(Locale.ROOT).endsWith(".srt") ? "application/x-subrip" : "application/octet-stream";
            if (!(mime.startsWith("video/") || mime.startsWith("audio/") || name.toLowerCase(Locale.ROOT).endsWith(".srt"))) return;
            try (InputStream in = getContext().getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) return;
                byte[] buffer = new byte[32 * 1024]; int n; long total = 0;
                while ((n = in.read(buffer)) != -1) { total += n; if (total > 150L * 1024L * 1024L) { notifyListeners("downloadError", new JSObject().put("message", "Auto-import limit 150MB ဖြစ်ပါတယ်။ Video ကို main Video Upload မှ တိုက်ရိုက်ရွေးပါ။")); return; } out.write(buffer, 0, n); }
                notifyFile(name, mime, out.toByteArray());
            }
        } catch (Exception e) { notifyListeners("downloadError", new JSObject().put("message", "Selected file import failed: " + e.getMessage())); }
    }

    private boolean isGoogleWebHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("google.com") || h.endsWith(".google.com") || h.equals("googleusercontent.com") || h.endsWith(".googleusercontent.com") || h.equals("gstatic.com") || h.endsWith(".gstatic.com") || h.equals("googleapis.com") || h.endsWith(".googleapis.com");
    }

    private void injectBlobDownloadHook(WebView view) {
        String js = "(function(){if(window.__sigyiBlobHook)return;window.__sigyiBlobHook=1;document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;if(!a||!a.href||!a.href.startsWith('blob:'))return;e.preventDefault();var name=a.download||'gemini-download';fetch(a.href).then(function(r){return r.blob()}).then(function(b){if(b.size>100*1024*1024){alert('ဖိုင် 100MB ကျော်နေပါသည်။');return;}var reader=new FileReader();reader.onloadend=function(){var data=String(reader.result).split(',')[1]||'';window.SiGyiGeminiFiles.onBlob(name,b.type||'application/octet-stream',data);};reader.readAsDataURL(b);}).catch(function(){alert('Download ကို ဖတ်မရပါ။');});},true);})();";
        view.evaluateJavascript(js, null);
    }

    private void enqueueDownload(String url, String userAgent, String disposition, String mime) {
        try {
            if (url == null || url.startsWith("blob:")) { notifyListeners("downloadError", new JSObject().put("message", "Blob download requires the page download button; use a standard file download link")); return; }
            String filename = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url)); request.setTitle(filename); request.setDescription("Si Gyi Recap Studio · Gemini download"); request.setMimeType(mime); request.addRequestHeader("User-Agent", userAgent == null ? "" : userAgent);
            String cookie = CookieManager.getInstance().getCookie(url); if (cookie != null) request.addRequestHeader("Cookie", cookie);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(getContext(), Environment.DIRECTORY_DOWNLOADS, filename);
            long id = downloadManager.enqueue(request); notifyListeners("downloadStarted", new JSObject().put("id", id).put("name", filename));
        } catch (Exception e) { notifyListeners("downloadError", new JSObject().put("message", e.getMessage())); }
    }

    private void notifyFile(String name, String mime, byte[] bytes) {
        JSObject payload = new JSObject(); payload.put("name", name == null ? "gemini-download" : name); payload.put("type", mime == null ? "application/octet-stream" : mime); payload.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)); notifyListeners("downloadedFile", payload);
    }

    private class BlobDownloadBridge {
        @JavascriptInterface public void onBlob(String name, String mime, String base64) {
            try { byte[] bytes = Base64.decode(base64, Base64.DEFAULT); notifyFile(name, mime, bytes); }
            catch (Exception e) { notifyListeners("downloadError", new JSObject().put("message", "Blob decode failed: " + e.getMessage())); }
        }
    }

    @Override protected void handleOnDestroy() {
        if (receiver != null) { try { getContext().unregisterReceiver(receiver); } catch (Exception ignored) { } receiver = null; }
        if (geminiDialog != null && geminiDialog.isShowing()) geminiDialog.dismiss();
        if (fileChooserCallback != null) { fileChooserCallback.onReceiveValue(null); fileChooserCallback = null; }
        if (activePlugin == this) activePlugin = null;
    }
}
