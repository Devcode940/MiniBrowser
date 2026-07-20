package com.minibrowser.download;

/**
 * A single download job (one media stream). Its state is mutated only by the
 * {@link DownloadManager} (and the worker), and read by the UI via snapshots.
 *
 * Serialization is manual (to/from JSON in DownloadManager) — no reflection,
 * which keeps R8 happy and avoids a serialization dependency.
 */
public final class DownloadTask {

    public enum Type { M3U8, MPD, DIRECT, IMAGE, UNKNOWN }
    public enum Status { PENDING, DOWNLOADING, PAUSED, MERGING, DONE, FAILED }

    public String id;             // stable, url-hash based
    public String url;            // the source URL (playlist/manifest/file)
    public String pageUrl;        // where it was discovered (optional)
    public Type type = Type.UNKNOWN;
    public Status status = Status.PENDING;

    public String title;
    public String filename;       // final output filename (no path)

    public int progress;          // 0..100
    public long downloadedBytes;
    public long totalBytes;       // -1 if unknown (e.g. live streams)

    public String outputPath;     // absolute path to merged file when DONE
    public String error;          // human-readable failure reason
    public long addedAt;
    public long doneAt;

    public volatile boolean cancelRequested;
    public volatile boolean pauseRequested;

    public DownloadTask() { }

    public DownloadTask(String url, String pageUrl, Type type) {
        this.url = url;
        this.pageUrl = pageUrl;
        this.type = type;
        this.id = Long.toHexString(Math.abs(url.hashCode())) + "_" + System.currentTimeMillis();
        this.addedAt = System.currentTimeMillis();
        this.totalBytes = -1;
        deriveFilename();
    }

    void deriveFilename() {
        String base;
        int slash = url.lastIndexOf('/');
        base = slash >= 0 ? url.substring(slash + 1) : url;
        int q = base.indexOf('?');
        if (q > 0) base = base.substring(0, q);
        base = sanitize(base);
        if (base.isEmpty()) base = "media_" + id;
        // Normalise extension to the container implied by the type.
        String ext = extFor(type);
        int dot = base.lastIndexOf('.');
        if (dot <= 0) {
            base = base + ext;
        } else if (!isMediaExt(base.substring(dot + 1))) {
            base = base.substring(0, dot) + ext;
        }
        this.filename = base;
    }

    /**
     * The URL segment this is derived from is remote-controlled (page/media
     * URL), so it's treated as untrusted input before it's ever used in a
     * {@code new File(outDir, filename)} call. Strips path separators,
     * control characters, and other filesystem-unsafe characters, then caps
     * the length so a pathological URL can't produce an unusable filename.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        try {
            // URL-decode first: a segment like "%2e%2e%2f" should be neutralised
            // the same way a literal "../" is, not smuggled through untouched.
            s = java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception ignored) { }
        // Drop path separators and traversal sequences outright.
        s = s.replace("/", "").replace("\\", "").replace("..", "");
        // Strip control characters and characters illegal/unsafe on common
        // Android/Windows/Linux filesystems: " * : < > ? | and NUL.
        s = s.replaceAll("[\\x00-\\x1f\\x7f\"*:<>?|]", "");
        s = s.trim();
        // Don't allow hidden-file-style or empty-after-strip names.
        while (s.startsWith(".")) s = s.substring(1);
        final int MAX_LEN = 120;
        if (s.length() > MAX_LEN) s = s.substring(s.length() - MAX_LEN);
        return s;
    }

    private static String extFor(Type t) {
        switch (t) {
            case M3U8: return ".ts";
            case MPD:  return ".mp4";
            case DIRECT: return ".mp4";
            case IMAGE: return ".jpg";
            default:   return ".bin";
        }
    }

    private static boolean isMediaExt(String s) {
        String l = s.toLowerCase();
        return l.equals("ts") || l.equals("mp4") || l.equals("m4a") || l.equals("mkv")
                || l.equals("webm") || l.equals("mp3") || l.equals("aac") || l.equals("m4s")
                || l.equals("jpg") || l.equals("jpeg") || l.equals("png") || l.equals("webp")
                || l.equals("gif") || l.equals("bmp") || l.equals("svg");
    }
}
