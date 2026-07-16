package com.minibrowser.download;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SegmentDownloader — the workhorse that turns a URL into a saved file.
 *
 * Pipeline per task type:
 *   • M3U8 : fetch playlist -> (if master, follow best variant) -> download
 *            init (fMP4 EXT-X-MAP, if any) + every segment -> merge into one file.
 *   • MPD  : fetch manifest -> parse -> resolve segments -> download + merge.
 *   • DIRECT: single range-enabled GET.
 *
 * Merging is done by streaming: segments are appended straight into the output
 * file as they arrive (no holding everything in RAM). TS segments concatenate
 * into valid MPEG-TS; fMP4 (init + .m4s) concatenate into a valid fragmented MP4.
 *
 * All network + disk work runs on a worker thread. Pause/cancel are cooperative
 * flags polled between segments.
 */
final class SegmentDownloader {

    private static final String TAG = "SegmentDownloader";
    private static final String UA = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 MiniBrowser/17";
    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 30000;
    private static final int SEG_RETRIES = 3;

    interface Listener {
        void onProgress(DownloadTask task);
        void onComplete(DownloadTask task, File output);
        void onError(DownloadTask task, String message);
    }

    /** Execute the download for {@code task}. Blocking — call off the UI thread. */
    static void run(DownloadTask task, File outDir, Listener listener) {
        try {
            if (!outDir.exists()) outDir.mkdirs();
            switch (task.type) {
                case M3U8: downloadHls(task, outDir); break;
                case MPD:  downloadDash(task, outDir); break;
                case DIRECT:
                case IMAGE:
                case UNKNOWN:
                default:   downloadDirect(task, outDir); break;
            }
            task.status = DownloadTask.Status.DONE;
            task.progress = 100;
            task.doneAt = System.currentTimeMillis();
            listener.onProgress(task);
            listener.onComplete(task, new File(task.outputPath));
        } catch (CancelledException ce) {
            task.status = task.pauseRequested
                    ? DownloadTask.Status.PAUSED
                    : DownloadTask.Status.FAILED;
            if (task.status == DownloadTask.Status.FAILED) task.error = "Cancelled";
            listener.onProgress(task);
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + e.getMessage(), e);
            task.status = DownloadTask.Status.FAILED;
            task.error = e.getMessage() != null ? e.getMessage() : "Download error";
            listener.onProgress(task);
            listener.onError(task, task.error);
        }
    }

    // ----------------------------- HLS -----------------------------------

    private static void downloadHls(DownloadTask task, File outDir) throws IOException {
        // Resolve down to a media playlist (follow master if needed).
        String mediaUrl = task.url;
        String content = fetchText(mediaUrl);
        M3u8Parser.Playlist pl = M3u8Parser.parse(content, mediaUrl);

        int depth = 0;
        while (pl.isMaster && depth < 3) {
            M3u8Parser.Variant best = M3u8Parser.bestVariant(pl);
            if (best == null) throw new IOException("No playable variant in master playlist");
            mediaUrl = best.url;
            content = fetchText(mediaUrl);
            pl = M3u8Parser.parse(content, mediaUrl);
            depth++;
        }
        if (pl.encrypted) {
            throw new IOException("Encrypted HLS stream (EXT-X-KEY) not supported");
        }
        if (pl.segments.isEmpty()) throw new IOException("No segments found in playlist");

        File out = new File(outDir, task.filename);
        task.outputPath = out.getAbsolutePath();
        task.status = DownloadTask.Status.DOWNLOADING;

        boolean isFmp4 = pl.initSegment != null
                || mediaLooksFragmented(pl.segments);
        // fMP4 needs the init segment first; plain TS just concatenates.
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (pl.initSegment != null) {
                byte[] init = fetchBytes(pl.initSegment);
                fos.write(init);
                task.downloadedBytes += init.length;
            }
            int total = pl.segments.size();
            int idx = 0;
            for (String seg : pl.segments) {
                checkInterrupt(task);
                byte[] data = fetchWithRetry(seg);
                fos.write(data);
                task.downloadedBytes += data.length;
                idx++;
                task.progress = (int) (idx * 100L / total);
            }
            fos.flush();
        }
        // Hint the container if it's fragmented MP4 but the filename is .ts.
        if (isFmp4 && task.filename.endsWith(".ts")) {
            task.filename = task.filename.substring(0, task.filename.length() - 3) + ".mp4";
            File renamed = new File(outDir, task.filename);
            //noinspection ResultOfMethodCallIgnored
            out.renameTo(renamed);
            task.outputPath = renamed.getAbsolutePath();
            out = renamed;
        }
    }

    private static boolean mediaLooksFragmented(List<String> segs) {
        for (String s : segs) {
            String l = s.toLowerCase();
            if (l.endsWith(".m4s") || l.endsWith(".cmfv") || l.contains(".mp4")) return true;
        }
        return false;
    }

    // ----------------------------- DASH ----------------------------------

    private static void downloadDash(DownloadTask task, File outDir) throws IOException {
        String xml = fetchText(task.url);
        MpdParser.Manifest m;
        try {
            m = MpdParser.parse(xml, task.url);
        } catch (Exception e) {
            throw new IOException("Invalid MPD manifest: " + e.getMessage());
        }
        MpdParser.Resolved r = MpdParser.resolve(m, "video");
        if (r.segments.isEmpty()) r = MpdParser.resolve(m, null);
        if (r.segments.isEmpty()) throw new IOException("No segments resolvable from MPD");

        File out = new File(outDir, task.filename);
        task.outputPath = out.getAbsolutePath();
        task.status = DownloadTask.Status.DOWNLOADING;

        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (r.initSegment != null) {
                byte[] init = fetchBytes(r.initSegment);
                fos.write(init);
                task.downloadedBytes += init.length;
            }
            int total = r.segments.size();
            int idx = 0;
            for (String seg : r.segments) {
                checkInterrupt(task);
                byte[] data = fetchWithRetry(seg);
                fos.write(data);
                task.downloadedBytes += data.length;
                idx++;
                task.progress = (int) (idx * 100L / total);
            }
            fos.flush();
        }
    }

    // ----------------------------- DIRECT --------------------------------

    private static void downloadDirect(DownloadTask task, File outDir) throws IOException {
        File out = new File(outDir, task.filename);
        task.outputPath = out.getAbsolutePath();
        task.status = DownloadTask.Status.DOWNLOADING;

        HttpURLConnection conn = open(task.url, "GET", null);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned " + code);
            }
            long total = conn.getContentLength();
            task.totalBytes = total > 0 ? total : -1;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    checkInterrupt(task);
                    fos.write(buf, 0, n);
                    task.downloadedBytes += n;
                    if (total > 0) task.progress = (int) (task.downloadedBytes * 100L / total);
                }
                fos.flush();
            }
        } finally {
            conn.disconnect();
        }
        if (task.progress < 100) task.progress = 100;
    }

    // --------------------------- networking ------------------------------

    private static byte[] fetchWithRetry(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < SEG_RETRIES; attempt++) {
            try {
                return fetchBytes(url);
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
            }
        }
        throw last != null ? last : new IOException("Segment failed after retries: " + url);
    }

    private static byte[] fetchBytes(String url) throws IOException {
        HttpURLConnection conn = open(url, "GET", null);
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode() + " for " + url);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static String fetchText(String url) throws IOException {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(String url, String method, String range) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "*/*");
        if (range != null) conn.setRequestProperty("Range", range);
        return conn;
    }

    // ------------------------- cooperative cancel ------------------------

    private static void checkInterrupt(DownloadTask task) throws CancelledException {
        if (task.cancelRequested || task.pauseRequested) throw new CancelledException();
    }

    private static final class CancelledException extends IOException { }
}
