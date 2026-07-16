package com.minibrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * BlocklistUpdater — lightweight, auto-updating remote blocklist source.
 *
 * Design (privacy + memory-conscious):
 *   • Parses standard "hosts file" format (0.0.0.0 / 127.0.0.1 / ::1  domain).
 *   • Merges parsed domains into the SHARED, thread-safe set used by
 *     BrowserCore.shouldInterceptRequest — so blocking is O(1) as before.
 *   • Caches the last successful fetch on disk (filesDir/blocklist_remote.txt)
 *     so the next cold-start works instantly and offline.
 *   • Conditional GET via If-None-Match / If-Modified-Since → 304 means no
 *     bytes re-downloaded. All network + disk work happens OFF the UI thread.
 *   • Cellular-aware: the periodic auto-refresh is skipped on mobile data when
 *     Cellular Guard is on (the ~2 MB list is heavy). Manual refresh always runs.
 *   • Hard memory cap (MAX_DOMAINS) so a runaway list can't OOM the device.
 *
 * Nothing here is telemetry: the only network peer is the user-chosen (or
 * default) static hosts file, fetched with a generic User-Agent.
 */
public class BlocklistUpdater {

    private static final String TAG = "BlocklistUpdater";
    private static final String PREFS = "minibrowser";
    private static final String K_ENABLED     = "remote_blocklist";
    private static final String K_URL         = "remote_blocklist_url";
    private static final String K_ETAG        = "remote_etag";
    private static final String K_LASTMOD     = "remote_lastmod";
    private static final String K_REFRESH_TS  = "remote_refresh_ts";
    private static final String CACHE_FILE    = "blocklist_remote.txt";
    private static final String CACHE_TMP     = "blocklist_remote.txt.tmp";

    private static final long STALE_MS   = 24L * 3600L * 1000L; // refresh at most daily
    private static final int  MAX_DOMAINS = 200_000;            // memory guard

    /** Default source: StevenBlack's unified hosts (comprehensive; configurable). */
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";

    public interface Callback {
        /**
         * Always invoked on the MAIN thread.
         * @param changed     true if the live set was actually modified
         * @param totalBlocked total entries now in the shared block set
         * @param message     human-readable status
         */
        void onResult(boolean changed, int totalBlocked, String message);
    }

    private final Context appCtx;
    private final Set<String> target;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    public BlocklistUpdater(Context ctx, Set<String> target) {
        this.appCtx = ctx.getApplicationContext();
        this.target = target;
        this.prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ----------------------------- config ----------------------------------
    public boolean isEnabled() { return prefs.getBoolean(K_ENABLED, true); }
    public void setEnabled(boolean on) { prefs.edit().putBoolean(K_ENABLED, on).apply(); }

    public String getUrl() { return prefs.getString(K_URL, DEFAULT_URL); }
    public void setUrl(String u) {
        if (u == null || u.trim().isEmpty()) return;
        // Changing the source invalidates the cached ETag/Last-Modified.
        prefs.edit().putString(K_URL, u.trim()).remove(K_ETAG).remove(K_LASTMOD).apply();
    }

    public boolean isStale() {
        long last = prefs.getLong(K_REFRESH_TS, 0L);
        return System.currentTimeMillis() - last > STALE_MS;
    }

    public String lastRefreshAgo() {
        long last = prefs.getLong(K_REFRESH_TS, 0L);
        if (last <= 0L) return "never";
        long mins = (System.currentTimeMillis() - last) / 60000L;
        if (mins < 1L) return "just now";
        if (mins < 60L) return mins + "m ago";
        long hrs = mins / 60L;
        if (hrs < 24L) return hrs + "h ago";
        return (hrs / 24L) + "d ago";
    }

    // --------------------------- cache load --------------------------------

    /** Load the on-disk cache into the live set. Call OFF the UI thread. */
    public int loadCached() {
        File f = new File(appCtx.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return 0;
        int count = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (target.size() >= MAX_DOMAINS) break;
                target.add(line);
                count++;
            }
        } catch (IOException e) {
            Log.w(TAG, "loadCached: " + e.getMessage());
        }
        return count;
    }

    // --------------------------- refresh logic -----------------------------

    /** Periodic check: only refreshes when enabled, stale, and not on guarded cellular. */
    public void maybeAutoRefresh(boolean onCellular, boolean cellGuard, Callback cb) {
        if (!isEnabled())                       { post(cb, false, "Remote list disabled"); return; }
        if (onCellular && cellGuard)            { post(cb, false, "Skipped on cellular");  return; }
        if (!isStale())                         { post(cb, false, "Up to date");           return; }
        refresh(false, cb);
    }

    /** Force a refresh now (ignores staleness; still respects enabled for non-forced). */
    public void refresh(final boolean force, final Callback cb) {
        if (!isEnabled() && !force) { post(cb, false, "Remote list disabled"); return; }
        new Thread(() -> doRefresh(cb), "BlocklistFetch").start();
    }

    private void doRefresh(Callback cb) {
        HttpURLConnection conn = null;
        int added = 0;
        try {
            URL u = new URL(getUrl());
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "MiniBrowser/16");
            // Do NOT set Accept-Encoding manually — Android transparently handles gzip.
            String etag   = prefs.getString(K_ETAG, null);
            String lastmod = prefs.getString(K_LASTMOD, null);
            if (etag   != null) conn.setRequestProperty("If-None-Match", etag);
            if (lastmod != null) conn.setRequestProperty("If-Modified-Since", lastmod);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.edit().putLong(K_REFRESH_TS, System.currentTimeMillis()).apply();
                post(cb, false, "Up to date");
                return;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                post(cb, false, "Server error " + code);
                return;
            }

            final String newEtag   = conn.getHeaderField("ETag");
            final String newLastMod = conn.getHeaderField("Last-Modified");

            // Stream-parse and stage the parsed domains to a temp cache file.
            File tmp = new File(appCtx.getFilesDir(), CACHE_TMP);
            File real = new File(appCtx.getFilesDir(), CACHE_FILE);

            try (InputStream in = conn.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 FileOutputStream cacheOut = new FileOutputStream(tmp)) {

                StringBuilder buf = new StringBuilder(4096);
                String line;
                while ((line = r.readLine()) != null) {
                    String domain = parseHost(line);
                    if (domain == null) continue;
                    if (target.size() >= MAX_DOMAINS) break;
                    if (target.add(domain)) added++;
                    buf.append(domain).append('\n');
                    if (buf.length() >= 8192) {
                        cacheOut.write(buf.toString().getBytes(StandardCharsets.UTF_8));
                        buf.setLength(0);
                    }
                }
                if (buf.length() > 0) {
                    cacheOut.write(buf.toString().getBytes(StandardCharsets.UTF_8));
                }
                cacheOut.flush();
                try { cacheOut.getFD().sync(); } catch (IOException ignored) { }
            }

            // Atomic replace of the cache.
            if (!tmp.renameTo(real)) {
                real.delete();
                tmp.renameTo(real);
            }

            SharedPreferences.Editor ed = prefs.edit()
                    .putLong(K_REFRESH_TS, System.currentTimeMillis());
            if (newEtag != null)    ed.putString(K_ETAG, newEtag);
            if (newLastMod != null) ed.putString(K_LASTMOD, newLastMod);
            ed.apply();

            post(cb, true, "Added " + added + " domains");
        } catch (Exception e) {
            Log.w(TAG, "doRefresh: " + e.getMessage());
            post(cb, false, "Update failed");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void post(final Callback cb, final boolean changed, final String msg) {
        if (cb == null) return;
        final int total = target.size();
        main.post(() -> cb.onResult(changed, total, msg));
    }

    // --------------------------- hosts parser ------------------------------

    /**
     * Parse one line of a hosts file → lowercased domain, or null to skip.
     * Handles: comments, leading IPs (0.0.0.0 / 127.0.0.1 / ::1), bare domains,
     * inline comments, loopback/reserved names, and raw IPv4/IPv6 tokens.
     */
    static String parseHost(String raw) {
        if (raw == null) return null;
        int hash = raw.indexOf('#');
        if (hash == 0) return null;            // full-line comment
        if (hash > 0) raw = raw.substring(0, hash);
        raw = raw.trim();
        if (raw.isEmpty()) return null;

        String[] parts = raw.split("\\s+");
        // The host is the LAST token (the IP, if present, comes first).
        String host = parts[parts.length - 1].toLowerCase();

        if (host.indexOf('.') < 0) return null;
        if (host.length() < 3 || host.length() > 253) return null;
        if (host.indexOf(':') >= 0) return null;                 // IPv6
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null; // raw IPv4
        if (isReserved(host)) return null;

        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) {
                return null;
            }
        }
        return host;
    }

    private static boolean isReserved(String h) {
        switch (h) {
            case "localhost": case "localhost.localdomain": case "local":
            case "broadcasthost": case "ip6-localhost": case "ip6-loopback":
            case "ip6-localnet": case "ip6-mcastprefix": case "ip6-allnodes":
            case "ip6-allrouters":
                return true;
        }
        return false;
    }
}
