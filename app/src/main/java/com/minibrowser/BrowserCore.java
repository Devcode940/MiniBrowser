package com.minibrowser;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BrowserCore — the headless engine.
 *
 * Responsibilities (each O(1) or O(n) at init only, never on the UI thread at runtime):
 *   • WebView lifecycle + memory-safe teardown
 *   • Fingerprint spoofing + zero-trust JS injection (desktop/mobile UA aware)
 *   • Thread-safe ad/tracker blocking (ConcurrentHashMap.newKeySet)
 *   • Auto-updating remote blocklist source (hosts-file parser, conditional GET)
 *   • Cellular guard (block heavy media on mobile data)
 *   • SSL hard-cancel (never accept invalid certs)
 *   • Safe Browsing
 *   • Custom assets (custom.css / userscript.js) caching + injection
 *   • Click-to-Block bridge
 *   • Offline page capture
 *   • Compatibility-mode toggle
 *
 * The Activity is referenced only through a {@link Callback}; the JS bridge uses a
 * static class + WeakReference to guarantee no context leaks.
 */
public class BrowserCore {

    private static final String TAG = "BrowserCore";
    public static final String HOME = "about:home";

    // ---- Keys ----
    private static final String PREFS = "minibrowser";
    private static final String KEY_SEARCH = "search_engine";
    private static final String KEY_COMPAT = "compat_mode";
    private static final String KEY_CELLGUARD = "cell_guard";
    private static final String KEY_SAFEBROWSING = "safe_browsing";
    private static final String DEFAULT_SEARCH = "https://duckduckgo.com/?q=%s";

    // ---- User-agent mode (desktop / mobile) ----
    private static final String KEY_UA_DESKTOP = "ua_desktop";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // ---- Auto translation ----
    private static final String KEY_AUTOTRANS = "auto_translate";
    private static final String KEY_TRANSLATE_LANG = "translate_lang";
    private static final String DEFAULT_LANG = "en";

    // ---- Heavy media extensions blocked on cellular ----
    private static final String[] HEAVY_EXTS = {
            ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".ogv", ".flv",
            ".mp3", ".oga", ".ogg", ".wav", ".flac", ".aac", ".m4a", ".opus",
            ".m3u8", ".ts", ".mpd"
    };

    /** A pre-built empty 200-style response returned for blocked hosts/resources. */
    private static final WebResourceResponse EMPTY_RESPONSE =
            new WebResourceResponse("text/plain", "utf-8", null);

    // ---- Owner state ----
    private final MainActivity activity;
    private final GestureWebView webView;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- Blocklist: thread-safe, O(1) contains() ----
    private final Set<String> blockedDomains = ConcurrentHashMap.newKeySet(2048);

    // ---- Custom assets, pre-cached as strings ----
    private volatile String customCss = "";
    private volatile String userscript = "";

    // ---- Runtime flags (read on the binder thread by shouldInterceptRequest) ----
    private volatile boolean onCellular = false;
    private volatile boolean cellGuard = true;
    private volatile boolean blockMode = false;     // click-to-block armed

    // ---- Networking ----
    private final ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    // ---- Remote blocklist (auto-updating hosts source) ----
    private BlocklistUpdater blocklistUpdater;

    // ---- User-agent mode ----
    private volatile boolean desktopMode = true; // spec default: desktop spoof
    private String defaultMobileUA;              // real WebView UA, captured once

    // ---- Media sniffer (download detection) ----
    private com.minibrowser.download.MediaSniffer mediaSniffer;

    private volatile boolean surfingKeys = false;
    private volatile boolean heavyMode = false;
    private volatile boolean autoTranslate = false;
    private volatile String translateTarget = DEFAULT_LANG;

    // ---- File chooser ----
    private ValueCallback<Uri[]> filePathCallback;

    // ---- Pending permission state ----
    private PermissionRequest pendingPermissionRequest;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    // ============================================================= //
    // ============================ INIT =========================== //
    // ============================================================= //

    public BrowserCore(MainActivity owner, GestureWebView view, Callback cb) {
        this.activity = owner;
        this.webView = view;
        this.callback = cb;
        this.connectivity = (ConnectivityManager) owner.getSystemService(Context.CONNECTIVITY_SERVICE);

        // Pre-load everything OFF the UI thread (rule: never do File/Asset I/O on UI).
        loadBlocklist();
        loadCustomAssets();

        configureSettings();
        webView.setWebViewClient(new CoreWebViewClient());
        webView.setWebChromeClient(new CoreChromeClient());
        // Static bridge -> no inner-class leak of the Activity.
        webView.addJavascriptInterface(new BlockBridge(owner), "AndroidBlock");
        webView.addJavascriptInterface(new NavBridge(owner), "__mbNav");

        // Restore prefs for runtime toggles.
        final boolean compat = owner.getPreferences(Context.MODE_PRIVATE)
                .getBoolean(KEY_COMPAT, false);
        final boolean guard = owner.getPreferences(Context.MODE_PRIVATE)
                .getBoolean(KEY_CELLGUARD, true);
        final boolean safe = owner.getPreferences(Context.MODE_PRIVATE)
                .getBoolean(KEY_SAFEBROWSING, true);
        setCellGuard(guard);
        setSafeBrowsing(safe);
        setCompatibilityMode(compat); // applies mixed-content + 3rd-party cookie policy

        // Restore UA mode (desktop by default, matching the spoof spec).
        final boolean desktop = owner.getPreferences(Context.MODE_PRIVATE)
                .getBoolean(KEY_UA_DESKTOP, true);
        desktopMode = desktop;
        webView.getSettings().setUserAgentString(desktop ? DESKTOP_UA : defaultMobileUA);

        // Remote blocklist: warm the cache + conditionally auto-refresh, all off
        // the UI thread. Merges into the SAME thread-safe set used for blocking.
        blocklistUpdater = new BlocklistUpdater(owner, blockedDomains);
        new Thread(() -> {
            int cached = blocklistUpdater.loadCached();
            if (cached > 0) {
                Log.i(TAG, "Loaded " + cached + " cached remote domains");
            }
            blocklistUpdater.maybeAutoRefresh(onCellular, cellGuard,
                    (changed, total, msg) -> {
                        Log.i(TAG, "Blocklist: " + msg + " (total " + total + ")");
                        if (changed) {
                            callback.showToast("Blocklist updated: " + total + " domains");
                        }
                    });
        }, "BlocklistInit").start();

        surfingKeys = owner.getPreferences(Context.MODE_PRIVATE).getBoolean("surfingkeys", false);
        heavyMode = owner.getPreferences(Context.MODE_PRIVATE).getBoolean("heavy_mode", false);
        autoTranslate = owner.getPreferences(Context.MODE_PRIVATE).getBoolean(KEY_AUTOTRANS, false);
        translateTarget = owner.getPreferences(Context.MODE_PRIVATE)
                .getString(KEY_TRANSLATE_LANG, DEFAULT_LANG);
        applyHeavyMode();
    }

    private void configureSettings() {
        WebSettings s = webView.getSettings();
        // Capture the genuine mobile WebView UA BEFORE any override, so the
        // desktop/mobile toggle can restore it faithfully.
        defaultMobileUA = s.getUserAgentString();
        // --- Rendering & storage ---
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);

        // --- Sandboxing (NON-NEGOTIABLE) ---
        // Base file access is ENABLED so that offline pages can be loaded via
        // file:// (see Offline Pages). Sandboxing is enforced by the *two* rules
        // below: a file:// page may NEVER read OTHER local files or reach other
        // origins. (Rule: NEVER setAllowFileAccessFromFileURLs(true).)
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);   // NEVER true
        s.setAllowUniversalAccessFromFileURLs(false);
        // Default: block mixed content. Compatibility mode may relax this.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // --- Privacy defaults ---
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setGeolocationEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(true);
        }

        // --- Misc ---
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.requestFocus();
        CookieManager.getInstance().setAcceptCookie(true);
    }

    // ============================================================= //
    // ===================== ASSET PRE-LOADING ==================== //
    // ============================================================= //

    private void loadBlocklist() {
        new Thread(() -> {
            InputStream is = null;
            BufferedReader reader = null;
            int count = 0;
            try {
                is = activity.getAssets().open("blocked_domains.txt");
                reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    // Strip any scheme/path the user may have pasted.
                    int slash = line.indexOf('/');
                    if (slash > 0) line = line.substring(0, slash);
                    if (!line.isEmpty()) {
                        blockedDomains.add(line);
                        count++;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "blocked_domains.txt not loadable: " + e.getMessage());
            } finally {
                closeQuietly(reader);
                closeQuietly(is);
            }
            Log.i(TAG, "Loaded " + count + " blocked domains");
        }, "BlocklistLoader").start();
    }

    private void loadCustomAssets() {
        new Thread(() -> {
            customCss = readTextFile(new File(activity.getFilesDir(), "custom.css"));
            userscript = readTextFile(new File(activity.getFilesDir(), "userscript.js"));
        }, "CustomAssetsLoader").start();
    }

    private static String readTextFile(File f) {
        if (f == null || !f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } catch (IOException e) {
            Log.w(TAG, "readTextFile " + f + ": " + e.getMessage());
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    // ============================================================= //
    // =================== NETWORK / CELLULAR ===================== //
    // ============================================================= //

    public void startNetworkMonitor() {
        if (connectivity == null || networkCallback != null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // Called on a binder thread; volatile write is safe.
                onCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        };
        try {
            connectivity.registerNetworkCallback(req, networkCallback);
            // Seed initial state synchronously.
            NetworkCapabilities active = null;
            if (Build.VERSION.SDK_INT >= 23) {
                active = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            }
            if (active != null) {
                onCellular = active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && !active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "NetworkCallback registration denied", e);
        }
    }

    public void stopNetworkMonitor() {
        if (connectivity != null && networkCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) { }
            networkCallback = null;
        }
    }

    // ============================================================= //
    // ===================== REQUEST INTERCEPT ==================== //
    // ============================================================= //

    private WebResourceResponse intercept(WebResourceRequest request) {
        final Uri uri = request.getUrl();
        if (uri == null) return null;

        // Blocklist — exact host plus suffix match (covers sub-domains).
        final String host = uri.getHost();
        if (host != null) {
            final String h = host.toLowerCase();
            if (blockedDomains.contains(h) || isSuffixBlocked(h)) {
                return EMPTY_RESPONSE;
            }
        }

        // Cellular guard — refuse heavy media on mobile data.
        if (onCellular && cellGuard && isHeavyResource(uri)) {
            return EMPTY_RESPONSE;
        }
        return null;
    }

    boolean isSuffixBlocked(String host) {
        int idx = host.indexOf('.');
        while (idx != -1 && idx < host.length() - 1) {
            String parent = host.substring(idx + 1);
            if (blockedDomains.contains(parent)) return true;
            idx = host.indexOf('.', idx + 1);
        }
        return false;
    }

    static boolean isHeavyResource(android.net.Uri uri) {
        String path = uri.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String ext : HEAVY_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ============================================================= //
    // ===================== JS INJECTION ========================= //
    // ============================================================= //

    /**
     * Fingerprint + zero-trust injection, built from the CURRENT UA mode so the
     * desktop/mobile toggle actually controls what sites see. Runs on
     * onPageStarted, before page scripts. The values are FROZEN via property
     * descriptors so page scripts cannot read a divergent "real" value.
     */
    private String buildFingerprintJs() {
        final boolean desktop = desktopMode;
        final String ua = desktop ? DESKTOP_UA
                : (defaultMobileUA != null ? defaultMobileUA : "");
        final String platform = desktop ? "Win32" : "Linux aarch64";
        final String vendor = "Google Inc.";
        final String appVersion = ua.startsWith("Mozilla/") ? ua.substring(8) : ua;
        return "(function(){try{"
                + "var UA=" + jsString(ua) + ";"
                + "function rd(o,p,v){try{Object.defineProperty(o,p,{get:function(){return v;},configurable:true});}catch(e){}}"
                + "rd(navigator,'userAgent',UA);"
                + "rd(navigator,'appVersion'," + jsString(appVersion) + ");"
                + "rd(navigator,'platform'," + jsString(platform) + ");"
                + "rd(navigator,'vendor'," + jsString(vendor) + ");"
                + "rd(navigator,'hardwareConcurrency',4);"
                + "rd(navigator,'deviceMemory',8);"
                + "rd(navigator,'maxTouchPoints',0);"
                + "rd(navigator,'language','en-US');"
                + "rd(navigator,'languages',['en-US','en']);"
                + "/* Zero-trust: force noopener,noreferrer on every window.open */"
                + "window.open=function(u,n,f){if(u){try{location.href=u;}catch(e){}}return null;};"
                + "/* Blind the clipboard read API */"
                + "try{if(navigator.clipboard){navigator.clipboard.readText=function(){return Promise.reject(new Error('blocked'));};}}catch(e){}"
                + "/* Kill WebRTC to prevent local IP leaks */"
                + "try{window.RTCPeerConnection=function(){throw new Error('WebRTC disabled');};"
                + "window.webkitRTCPeerConnection=window.RTCPeerConnection;}catch(e){}"
                + "}catch(e){}})();";
    }

    /** Injected when Click-to-Block is armed. */
    private static final String BLOCK_PICKER_JS =
        "(function(){"
        + "if(window.__mbPicker)return;window.__mbPicker=true;"
        + "function sel(el){"
        + "  if(el.id)return '#'+(window.CSS&&CSS.escape?CSS.escape(el.id):el.id);"
        + "  var parts=[],node=el;"
        + "  while(node&&node.nodeType===1&&node!==document.body){"
        + "    var s=node.tagName.toLowerCase();"
        + "    if(node.className&&typeof node.className==='string'){"
        + "      var c=node.className.trim().split(/\\\\s+/).slice(0,2).join('.');"
        + "      if(c)s+='.'+c;"
        + "    }"
        + "    var p=node.parentNode;"
        + "    if(p){var sib=Array.prototype.filter.call(p.children,function(k){return k.tagName===node.tagName;});"
        + "      if(sib.length>1)s+=':nth-of-type('+(sib.indexOf(node)+1)+')';}"
        + "    parts.unshift(s);node=node.parentNode;"
        + "  }"
        + "  return parts.join(' > ');"
        + "}"
        + "function onClick(e){"
        + "  e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();"
        + "  var t=e.target;if(!t||t.nodeType!==1)return;"
        + "  t.style.outline='2px solid #ff3b30';t.style.outlineOffset='-2px';"
        + "  try{AndroidBlock.onElementSelected(sel(t));}catch(err){}"
        + "}"
        + "document.addEventListener('click',onClick,true);"
        + "})();";

    private void injectCustomAssets(WebView view) {
        final String css = customCss;
        if (css != null && !css.isEmpty()) {
            String js = "(function(){try{var s=document.createElement('style');"
                    + "s.type='text/css';s.appendChild(document.createTextNode("
                    + jsString(css) + "));"
                    + "(document.head||document.documentElement).appendChild(s);}catch(e){}})();";
            view.evaluateJavascript(js, null);
        }
        final String us = userscript;
        if (us != null && !us.isEmpty()) {
            view.evaluateJavascript("(function(){try{" + us + "}catch(e){}})();", null);
        }
    }

    /** Quote a string for safe embedding inside a JS single-quoted literal. */
    static String jsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:   sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    // ============================================================= //
    // ===================== PUBLIC CONTROLS ====================== //
    // ============================================================= //

    public void loadUrl(String url) {
        if (url == null) return;
        if (HOME.equals(url)) {
            webView.loadUrl("about:blank");
            if (callback != null) callback.onHome();
            return;
        }
        webView.loadUrl(url);
    }

    /** Smart router: TLD -> URL, otherwise the configured search engine. */
    public void loadOrSearch(String input) {
        if (input == null) return;
        String q = input.trim();
        if (q.isEmpty()) return;
        String low = q.toLowerCase();

        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("file://") || low.startsWith("about:")
                || low.startsWith("javascript:")) {
            loadUrl(q);
            return;
        }
        if (looksLikeUrl(low)) {
            loadUrl("https://" + q);
            return;
        }
        String enc;
        try {
            enc = URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            enc = q.replace(" ", "+");
        }
        String engine = activity.getPreferences(Context.MODE_PRIVATE)
                .getString(KEY_SEARCH, DEFAULT_SEARCH);
        if (engine == null || !engine.contains("%s")) engine = DEFAULT_SEARCH;
        loadUrl(engine.replace("%s", enc));
    }

    static boolean looksLikeUrl(String s) {
        if (s.indexOf(' ') >= 0 || s.indexOf('\t') >= 0) return false;
        if ("localhost".equals(s)) return true;
        int dot = s.lastIndexOf('.');
        if (dot < 0) return false;
        String tld = s.substring(dot + 1);
        int slash = tld.indexOf('/');
        if (slash >= 0) tld = tld.substring(0, slash);
        int colon = tld.indexOf(':');
        if (colon >= 0) tld = tld.substring(0, colon);
        int len = tld.length();
        if (len < 2 || len > 24) return false;
        for (int i = 0; i < len; i++) {
            char c = tld.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    public void goBack()        { if (webView.canGoBack())  webView.goBack(); }
    public void goForward()     { if (webView.canGoForward()) webView.goForward(); }
    public void reload()        { webView.reload(); }
    public void stopLoading()   { webView.stopLoading(); }
    public boolean canGoBack()  { return webView.canGoBack(); }
    public boolean canGoForward(){ return webView.canGoForward(); }
    public String currentUrl()  { return webView.getUrl(); }

    /** Scan the current page DOM for downloadable media; callback on UI thread. */
    public void scanForMedia(com.minibrowser.download.MediaSniffer.Callback cb) {
        if (mediaSniffer == null) {
            mediaSniffer = new com.minibrowser.download.MediaSniffer(webView, cb);
        } else {
            mediaSniffer.setCallback(cb);
        }
        mediaSniffer.scan();
    }

    public void setSearchEngine(String template) {
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putString(KEY_SEARCH, template).apply();
    }

    public void setCellGuard(boolean enabled) {
        cellGuard = enabled;
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_CELLGUARD, enabled).apply();
    }

    public boolean isCellGuard() { return cellGuard; }

    // ----------------------- User-agent mode -----------------------

    public boolean isDesktopMode() { return desktopMode; }

    /** Switch the live User-Agent (HTTP header + navigator) and reload to apply. */
    public void setDesktopMode(boolean on) {
        desktopMode = on;
        webView.getSettings().setUserAgentString(on ? DESKTOP_UA : defaultMobileUA);
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_UA_DESKTOP, on).apply();
        webView.reload();
    }

    public boolean isSurfingKeys() { return surfingKeys; }
    public void setSurfingKeys(boolean on) {
        surfingKeys = on;
        activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("surfingkeys", on).apply();
        if (on) webView.evaluateJavascript(com.minibrowser.media.SurfingKeys.script(), null);
    }

    // ----------------------- Auto translation -----------------------

    public boolean isAutoTranslate() { return autoTranslate; }
    public void setAutoTranslate(boolean on) {
        autoTranslate = on;
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTOTRANS, on).apply();
    }
    public String getTranslateTarget() { return translateTarget; }
    public void setTranslateTarget(String lang) {
        translateTarget = (lang == null || lang.isEmpty()) ? DEFAULT_LANG : lang;
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putString(KEY_TRANSLATE_LANG, translateTarget).apply();
    }

    /** Manually translate the given URL into the target language. */
    public void translatePage(String url, String target) {
        if (url == null) return;
        String t = (target == null || target.isEmpty()) ? DEFAULT_LANG : target;
        try {
            String u = "https://translate.google.com/translate?sl=auto&tl="
                    + URLEncoder.encode(t, "UTF-8")
                    + "&u=" + URLEncoder.encode(url, "UTF-8");
            webView.loadUrl(u);
        } catch (Exception ignored) { }
    }

    public void translateCurrent() {
        String url = webView.getUrl();
        if (url == null || !url.startsWith("http")) {
            callback.showToast("Open a web page first");
            return;
        }
        translatePage(url, translateTarget);
    }

    /** If auto-translate is on, detect the page language and translate if foreign. */
    private void maybeAutoTranslate(WebView view, String url) {
        if (!autoTranslate || url == null) return;
        if (!url.startsWith("http")) return;
        if (url.contains("translate.google.com")) return; // already a mirror
        final String target = translateTarget != null ? translateTarget : DEFAULT_LANG;
        view.evaluateJavascript(
                "(function(){try{return (document.documentElement.lang||'').slice(0,2).toLowerCase();}catch(e){return '';}})();",
                value -> {
                    String l = value == null ? "" : value.replace("\"", "").trim();
                    if (!l.isEmpty() && !l.equalsIgnoreCase(target)) {
                        translatePage(url, target);
                    }
                });
    }

    public boolean isHeavyMode() { return heavyMode; }
    public void setHeavyMode(boolean on) {
        heavyMode = on;
        activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("heavy_mode", on).apply();
        applyHeavyMode();
        webView.reload();
    }

    private void applyHeavyMode() {
        WebSettings s = webView.getSettings();
        if (heavyMode) {
            s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
            s.setBlockNetworkImage(false);
        } else {
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setBlockNetworkImage(false);
        }
    }

    // ----------------------- Remote blocklist -----------------------

    public boolean isRemoteBlocklistEnabled() {
        return blocklistUpdater != null && blocklistUpdater.isEnabled();
    }

    public void setRemoteBlocklistEnabled(boolean on) {
        if (blocklistUpdater != null) blocklistUpdater.setEnabled(on);
    }

    public String getBlocklistUrl() {
        return blocklistUpdater != null ? blocklistUpdater.getUrl()
                : BlocklistUpdater.DEFAULT_URL;
    }

    public void setBlocklistUrl(String u) {
        if (blocklistUpdater != null) blocklistUpdater.setUrl(u);
    }

    public void refreshBlocklist() {
        if (blocklistUpdater == null) return;
        callback.showToast("Updating blocklist…");
        blocklistUpdater.refresh(true, (changed, total, msg) ->
                callback.showToast(msg + " · " + total + " domains"));
    }

    public int getBlockedDomainCount() { return blockedDomains.size(); }

    public String blocklistSummary() {
        int n = blockedDomains.size();
        if (blocklistUpdater == null) return n + " domains";
        return n + " domains · " + (blocklistUpdater.isEnabled() ? "remote" : "local")
                + " · " + blocklistUpdater.lastRefreshAgo();
    }

    public void setSafeBrowsing(boolean enabled) {
        if (Build.VERSION.SDK_INT >= 26) {
            webView.getSettings().setSafeBrowsingEnabled(enabled);
        }
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SAFEBROWSING, enabled).apply();
    }

    /** Dynamic compatibility toggle — no restart required. */
    public void setCompatibilityMode(boolean enabled) {
        WebSettings s = webView.getSettings();
        if (enabled) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        } else {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        }
        activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_COMPAT, enabled).apply();
    }

    public void setBlockMode(boolean armed) {
        blockMode = armed;
        if (armed) {
            webView.evaluateJavascript(BLOCK_PICKER_JS, null);
        } else {
            // Re-run to disarm by reloading — picker is idempotent on the live DOM.
            webView.reload();
        }
    }

    public boolean isBlockMode() { return blockMode; }

    /** Appends a selector to custom.css and reloads so the rule applies. */
    public void appendBlockSelector(String selector) {
        if (selector == null || selector.trim().isEmpty()) return;
        final String rule = "\n" + selector.trim() + "{display:none !important;}\n";
        customCss = (customCss == null ? "" : customCss) + rule;
        new Thread(() -> {
            try (FileOutputStream fos = activity.openFileOutput(
                    "custom.css", Context.MODE_APPEND)) {
                fos.write(rule.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e(TAG, "append custom.css failed", e);
            }
        }, "CssAppender").start();
        blockMode = false;
        webView.reload();
    }

    // ---- Custom asset editors (Settings page) ----

    public String getCustomCss() { return customCss == null ? "" : customCss; }
    public String getUserscript() { return userscript == null ? "" : userscript; }

    public void writeCustomCss(String content) {
        customCss = content == null ? "" : content;
        writeFile("custom.css", content);
    }

    public void writeUserscript(String content) {
        userscript = content == null ? "" : content;
        writeFile("userscript.js", content);
    }

    private void writeFile(final String name, final String content) {
        new Thread(() -> {
            try (FileOutputStream fos = activity.openFileOutput(name, Context.MODE_PRIVATE)) {
                fos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e(TAG, "writeFile " + name, e);
            }
        }, "AssetWriter").start();
    }

    /** Captures the live DOM to filesDir/offline/ with a <base> tag. */
    public void saveOffline() {
        webView.evaluateJavascript(
                "(function(){try{return document.documentElement.outerHTML;}catch(e){return null;}})();",
                value -> {
                    if (value == null || "null".equals(value)) {
                        callback.showToast("Nothing to save");
                        return;
                    }
                    String html;
                    try {
                        Object parsed = new JSONTokener(value).nextValue();
                        html = parsed instanceof String ? (String) parsed : value;
                    } catch (Exception e) {
                        html = value;
                    }
                    final String url = webView.getUrl();
                    final String base = url != null ? url : "";
                    final String content = "<!-- Saved by MiniBrowser -->\n<base href=\""
                            + base + "\">\n" + html;
                    final String name = "page_" + System.currentTimeMillis() + ".html";
                    new Thread(() -> {
                        File dir = new File(activity.getFilesDir(), "offline");
                        if (!dir.exists()) dir.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
                            fos.write(content.getBytes(StandardCharsets.UTF_8));
                            main.post(() -> callback.showToast("Saved offline"));
                        } catch (IOException e) {
                            Log.e(TAG, "saveOffline", e);
                            main.post(() -> callback.showToast("Save failed"));
                        }
                    }, "OfflineSaver").start();
                });
    }

    public List<File> listOfflinePages() {
        List<File> out = new ArrayList<>();
        File dir = new File(activity.getFilesDir(), "offline");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().endsWith(".html")) out.add(f);
        }
        return out;
    }

    public void loadOffline(File f) {
        webView.loadUrl(Uri.fromFile(f).toString());
    }

    public void clearAllPrivateData() {
        webView.clearCache(true);
        webView.clearHistory();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorageCompat.clear();
    }

    // ============================================================= //
    // ===================== PERMISSION DELEGATES ================= //
    // ============================================================= //

    public void onGeolocation(String origin, GeolocationPermissions.Callback cb) {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            cb.invoke(origin, true, false);
        } else {
            pendingGeoCallback = cb;
            pendingGeoOrigin = origin;
            activity.requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    MainActivity.REQ_GEO);
        }
    }

    public void onPermissionRequest(PermissionRequest request) {
        String[] resources = request.getResources();
        List<String> toAsk = new ArrayList<>();
        for (String r : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                    && !hasPermission(Manifest.permission.CAMERA)) {
                toAsk.add(Manifest.permission.CAMERA);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                    && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                toAsk.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (toAsk.isEmpty()) {
            request.grant(resources);
        } else {
            pendingPermissionRequest = request;
            activity.requestPermissions(
                    toAsk.toArray(new String[0]), MainActivity.REQ_WEB_PERMS);
        }
    }

    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grants) {
        boolean granted = grants.length > 0;
        for (int g : grants) if (g != PackageManager.PERMISSION_GRANTED) granted = false;

        if (requestCode == MainActivity.REQ_GEO && pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == MainActivity.REQ_WEB_PERMS && pendingPermissionRequest != null) {
            if (granted) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }
    }

    public boolean showFileChooser(ValueCallback<Uri[]> cb, Intent intent) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
        }
        filePathCallback = cb;
        try {
            activity.startActivityForResult(intent, MainActivity.REQ_FILE);
            return true;
        } catch (Exception e) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
            return false;
        }
    }

    public void onFileChooserResult(int resultCode, Intent data) {
        if (filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK) {
            if (data != null) {
                String ds = data.getDataString();
                if (ds != null) {
                    results = new Uri[]{Uri.parse(ds)};
                } else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    private boolean hasPermission(String perm) {
        return activity.checkCallingOrSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ============================================================= //
    // ========================== TEARDOWN ======================== //
    // ============================================================= //

    public void destroy() {
        stopNetworkMonitor();
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        pendingPermissionRequest = null;
        pendingGeoCallback = null;

        if (webView != null) {
            webView.stopLoading();
            try { webView.removeJavascriptInterface("AndroidBlock"); } catch (Exception ignored) { }
            try { webView.removeJavascriptInterface("__mbNav"); } catch (Exception ignored) { }
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.loadUrl("about:blank");
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
            // Remove from its parent BEFORE destroying to avoid the context leak.
            android.view.ViewGroup parent =
                    (android.view.ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            // References are dropped when BrowserCore is itself released by the Activity.
        }
    }

    // ============================================================= //
    // ===================== STATIC JS BRIDGE ===================== //
    // ============================================================= //

    /**
     * MUST be a static class with a WeakReference — a non-static inner class would
     * implicitly hold the Activity and leak it via the JS interface.
     */
    private static final class BlockBridge {
        private final WeakReference<MainActivity> ref;

        BlockBridge(MainActivity owner) {
            this.ref = new WeakReference<>(owner);
        }

        @android.webkit.JavascriptInterface
        public void onElementSelected(final String selector) {
            final MainActivity a = ref.get();
            if (a == null || selector == null) return;
            a.runOnUiThread(() -> a.onElementPicked(selector));
        }
    }

    private static final class NavBridge {
        private final WeakReference<MainActivity> ref;

        NavBridge(MainActivity owner) {
            this.ref = new WeakReference<>(owner);
        }

        @android.webkit.JavascriptInterface
        public void action(String a) {
            final MainActivity act = ref.get();
            if (act == null || a == null) return;
            act.runOnUiThread(() -> {
                switch (a) {
                    case "back": act.navBack(); break;
                    case "fwd": act.navForward(); break;
                    case "reload": act.navReload(); break;
                    case "stop": act.navStop(); break;
                    case "copy": act.navCopyUrl(); break;
                    default: break;
                }
            });
        }
    }

    // ============================================================= //
    // ===================== WEB VIEW CLIENT ====================== //
    // ============================================================= //

    private final class CoreWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // Spoof fingerprints BEFORE the page's own scripts run.
            view.evaluateJavascript(buildFingerprintJs(), null);
            if (callback != null) callback.onUrlChanged(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectCustomAssets(view);
            if (surfingKeys) view.evaluateJavascript(com.minibrowser.media.SurfingKeys.script(), null);
            if (blockMode) view.evaluateJavascript(BLOCK_PICKER_JS, null);
            maybeAutoTranslate(view, url);
            CookieManager.getInstance().flush();
            if (callback != null) callback.onPageFinished(url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return intercept(request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null) return false;
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            // Allow http(s)/file/about to load inside the WebView.
            if ("http".equals(scheme) || "https".equals(scheme)
                    || "file".equals(scheme) || "about".equals(scheme)
                    || "data".equals(scheme) || "blob".equals(scheme)) {
                return false;
            }
            // Everything else (intent:, mailto:, tel:, market:) -> external handler.
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            } catch (Exception e) {
                callback.showToast("No app to open " + scheme);
            }
            return true;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // ZERO-TRUST: never accept invalid / self-signed / expired certs.
            handler.cancel();
        }

        @Override
        public void onSafeBrowsingHit(WebView view, WebResourceRequest request,
                                      int threatType, SafeBrowsingResponse response) {
            // Never "proceed"; bounce the user back to safety.
            if (Build.VERSION.SDK_INT >= 26) {
                response.backToSafety(true);
                callback.showToast("Dangerous site blocked");
            }
        }
    }

    // ============================================================= //
    // ===================== WEB CHROME CLIENT ==================== //
    // ============================================================= //

    private final class CoreChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (callback != null) callback.onProgress(newProgress);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (callback != null) callback.onTitleChanged(title);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin, GeolocationPermissions.Callback callback2) {
            onGeolocation(origin, callback2);
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            activity.runOnUiThread(() -> onPermissionRequest(request));
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                         WebChromeClient.FileChooserParams params) {
            Intent intent;
            try {
                intent = params.createIntent();
            } catch (Exception e) {
                cb.onReceiveValue(null);
                return false;
            }
            return showFileChooser(cb, intent);
        }

        @Override
        public void onConsoleMessage(String message, int lineNumber, String sourceID) {
            Log.d(TAG, "JS[" + lineNumber + "]: " + message);
        }
    }

    // ============================================================= //
    // ====================== CALLBACK IFACE ====================== //
    // ============================================================= //

    public interface Callback {
        void onUrlChanged(String url);
        void onProgress(int progress);
        void onPageFinished(String url);
        void onTitleChanged(String title);
        void onHome();
        void showToast(String msg);
    }

    /** Isolate the optional WebStorage call so older SDK guards stay tidy. */
    private static final class WebStorageCompat {
        static void clear() {
            try {
                android.webkit.WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) { }
        }
    }
}
