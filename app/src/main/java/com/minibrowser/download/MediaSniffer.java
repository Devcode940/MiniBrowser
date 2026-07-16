package com.minibrowser.download;

import android.util.Log;
import android.webkit.WebView;

/**
 * MediaSniffer — detects downloadable media on the current page.
 *
 * Two complementary techniques (mirrors super-video-downloader's approach):
 *   1. DOM scan: query <video>/<audio>/<source>/blob URLs via injected JS.
 *   2. URL heuristics: classify a candidate URL as M3U8 / MPD / DIRECT by its
 *      extension or query hint (used by BrowserCore while intercepting traffic).
 *
 * Results are reported through a {@link Callback} on the UI thread.
 */
public final class MediaSniffer {

    private static final String TAG = "MediaSniffer";

    public interface Callback {
        /** Called once per scan with a list of discovered media URLs. */
        void onMediaFound(java.util.List<String> urls);
    }

    private final WebView webView;
    private Callback callback;

    public MediaSniffer(WebView webView, Callback callback) {
        this.webView = webView;
        this.callback = callback;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** Run a one-shot scan of the current DOM for media elements. */
    public void scan() {
        if (webView == null) {
            callback.onMediaFound(new java.util.ArrayList<>());
            return;
        }
        final String js =
            "(function(){try{"
          + "var out=[],seen={};"
          + "function add(u){if(!u)return;if(u.indexOf('blob:')===0){out.push('blob:'+u);return;}"
          + "if(u.indexOf('http')!==0)return;if(seen[u])return;seen[u]=1;out.push(u);}"
          + "var qs=document.querySelectorAll('video,source,audio,embed,object,img,picture source');"
          + "for(var i=0;i<qs.length;i++){var el=qs[i];"
          + "  add(el.currentSrc||el.src||'');"
          + "  if(el.srcset){var first=el.srcset.split(',')[0].trim().split(' ')[0];if(first)add(first);}"
          + "  if(el.querySelectorAll){var ss=el.querySelectorAll('source');for(var j=0;j<ss.length;j++)add(ss[j].src||ss[j].currentSrc||'');}"
          + "}"
          + "try{var imgs=document.getElementsByTagName('img');for(var m=0;m<imgs.length;m++){var im=imgs[m];add(im.currentSrc||im.src||'');if(im.dataset&&im.dataset.src)add(im.dataset.src);}}catch(e){}"
          + "try{var vs=document.getElementsByTagName('video');for(var k=0;k<vs.length;k++){if(vs[k].src)add(vs[k].src);}}catch(e){}"
          + "return JSON.stringify(out);"
          + "}catch(e){return '[]';}})();";
        webView.evaluateJavascript(js, value -> {
            java.util.List<String> urls = parseJsArray(value);
            // De-duplicate & keep only http(s) plus the bare blob markers.
            java.util.List<String> clean = new java.util.ArrayList<>();
            for (String u : urls) {
                if (u == null) continue;
                String t = u.trim();
                if (t.isEmpty()) continue;
                if (!clean.contains(t)) clean.add(t);
            }
            Log.i(TAG, "Sniffed " + clean.size() + " media URL(s)");
            callback.onMediaFound(clean);
        });
    }

    /** Classify a candidate URL into a download type (or null = not media). */
    public static DownloadTask.Type classify(String url) {
        if (url == null) return null;
        String u = url.toLowerCase();
        // Strip query string for extension checks.
        int q = u.indexOf('?');
        String path = q > 0 ? u.substring(0, q) : u;

        if (path.endsWith(".m3u8") || u.contains(".m3u8") || u.contains("/hls/")
                || u.contains("m3u8")) {
            return DownloadTask.Type.M3U8;
        }
        if (path.endsWith(".mpd") || u.contains(".mpd") || u.contains("/dash/")) {
            return DownloadTask.Type.MPD;
        }
        if (u.startsWith("blob:")) return DownloadTask.Type.UNKNOWN; // need capture, not direct
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm")
                || path.endsWith(".mkv") || path.endsWith(".mov")
                || path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac")
                || path.endsWith(".ogg") || path.endsWith(".flac")) {
            return DownloadTask.Type.DIRECT;
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".bmp")
                || path.endsWith(".svg") || u.contains("image/")) {
            return DownloadTask.Type.IMAGE;
        }
        return null;
    }

    private static java.util.List<String> parseJsArray(String value) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (value == null || "null".equals(value)) return out;
        String s = value.trim();
        // value is a JSON string array like: ["a","b"] but may be quoted.
        if (s.startsWith("\"") && s.endsWith("\"")) {
            // evaluateJavascript returned a JSON-encoded string; unwrap once.
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        org.json.JSONArray arr;
        try {
            arr = new org.json.JSONArray(s);
        } catch (Exception e) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            try { out.add(arr.getString(i)); } catch (Exception ignored) { }
        }
        return out;
    }
}
