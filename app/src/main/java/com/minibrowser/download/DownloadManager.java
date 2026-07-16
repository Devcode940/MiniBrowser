package com.minibrowser.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.minibrowser.MiniApp;
import com.minibrowser.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DownloadManager — process-wide singleton coordinating the download queue.
 *
 * Threading model:
 *   • A bounded ExecutorService ({@link #POOL}) runs up to N tasks concurrently.
 *   • The worker ({@link SegmentDownloader}) calls back on a worker thread; we
 *     hop to the main thread before notifying the UI listener.
 *   • State mutations to a task happen only on the worker (during run) or the
 *     main thread (from UI actions); the UI always reads via {@link #snapshot()}.
 *
 * Persistence: the queue is serialized to filesDir/downloads.json whenever it
 * changes, so a relaunch restores history (completed/failed/paused).
 */
public final class DownloadManager {

    private static final String TAG = "DownloadManager";
    private static final String STORE = "downloads.json";
    private static final int POOL_SIZE = 3;
    private static final int NOTIF_ID_BASE = 0xB000;
    private static final long PROGRESS_THROTTLE_MS = 500;

    private static volatile DownloadManager INSTANCE;

    private final Context appCtx;
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE,
            r -> {
                Thread t = new Thread(r, "MB-Downloader");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<DownloadTask> tasks = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    /** UI-side listener; notified on the main thread. */
    public interface Listener {
        void onChanged();
    }

    private DownloadManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        load();
    }

    public static void init(Context ctx) {
        if (INSTANCE == null) {
            synchronized (DownloadManager.class) {
                if (INSTANCE == null) INSTANCE = new DownloadManager(ctx);
            }
        }
    }

    public static DownloadManager get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("DownloadManager.init() not called");
        }
        return INSTANCE;
    }

    // --------------------------- public API ------------------------------

    public void register(Listener l)   { synchronized (listeners) { listeners.add(l); } }
    public void unregister(Listener l)  { synchronized (listeners) { listeners.remove(l); } }

    /** Defensive snapshot copy for the UI. */
    public synchronized List<DownloadTask> snapshot() {
        return new ArrayList<>(tasks);
    }

    public synchronized DownloadTask findByUrl(String url) {
        if (url == null) return null;
        for (DownloadTask t : tasks) if (url.equals(t.url)) return t;
        return null;
    }

    /** Enqueue by URL; {@code type} null auto-classifies via MediaSniffer. */
    public DownloadTask enqueue(String url, String pageUrl, DownloadTask.Type type) {
        if (url == null || url.trim().isEmpty()) return null;
        String u = url.trim();
        DownloadTask.Type t = type != null ? type : MediaSniffer.classify(u);
        if (t == null) t = DownloadTask.Type.UNKNOWN;

        synchronized (this) {
            DownloadTask existing = findByUrl(u);
            if (existing != null) {
                // Re-trigger if it failed or was paused.
                if (existing.status == DownloadTask.Status.FAILED
                        || existing.status == DownloadTask.Status.PAUSED) {
                    start(existing);
                }
                return existing;
            }
            DownloadTask task = new DownloadTask(u, pageUrl, t);
            tasks.add(0, task);
            persist();
            start(task);
            return task;
        }
    }

    public synchronized void pause(DownloadTask task) {
        task.pauseRequested = true;
        task.status = DownloadTask.Status.PAUSED;
        persist();
        notifyChanged();
    }

    public synchronized void resume(DownloadTask task) {
        task.pauseRequested = false;
        task.cancelRequested = false;
        task.error = null;
        task.downloadedBytes = 0;
        task.progress = 0;
        start(task);
    }

    public synchronized void delete(DownloadTask task, boolean deleteFile) {
        task.cancelRequested = true;
        Iterator<DownloadTask> it = tasks.iterator();
        while (it.hasNext()) {
            if (it.next() == task) it.remove();
        }
        if (deleteFile && task.outputPath != null) {
            try { new File(task.outputPath).delete(); } catch (Exception ignored) { }
        }
        cancelNotification(task);
        persist();
        notifyChanged();
    }

    // --------------------------- scheduling ------------------------------

    private void start(final DownloadTask task) {
        task.status = DownloadTask.Status.PENDING;
        task.pauseRequested = false;
        notifyChanged();
        final File outDir = outputDir();
        pool.execute(() -> SegmentDownloader.run(task, outDir, new SegmentDownloader.Listener() {
            long lastNotify = 0;
            @Override
            public void onProgress(DownloadTask t) {
                long now = System.currentTimeMillis();
                if (t.status == DownloadTask.Status.DOWNLOADING && now - lastNotify < PROGRESS_THROTTLE_MS) {
                    return;
                }
                lastNotify = now;
                main.post(() -> {
                    persist();
                    notifyChanged();
                    if (t.status == DownloadTask.Status.DOWNLOADING) updateNotification(t);
                });
            }

            @Override
            public void onComplete(DownloadTask t, File output) {
                main.post(() -> {
                    persist();
                    notifyChanged();
                    finishNotification(t);
                });
            }

            @Override
            public void onError(DownloadTask t, String message) {
                main.post(() -> {
                    persist();
                    notifyChanged();
                    errorNotification(t);
                });
            }
        }));
    }

    private File outputDir() {
        File dir = appCtx.getExternalFilesDir(null);
        if (dir == null) dir = appCtx.getFilesDir();
        File out = new File(dir, "MiniBrowser");
        if (!out.exists()) out.mkdirs();
        return out;
    }

    // --------------------------- persistence -----------------------------

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (DownloadTask t : tasks) arr.put(toJson(t));
            FileOutputStream fos = appCtx.openFileOutput(STORE, Context.MODE_PRIVATE);
            fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException | JSONException e) {
            Log.w(TAG, "persist failed: " + e.getMessage());
        }
    }

    private void load() {
        File f = new File(appCtx.getFilesDir(), STORE);
        if (!f.exists()) return;
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            JSONArray arr = new JSONArray(new String(data, StandardCharsets.UTF_8));
            synchronized (this) {
                for (int i = 0; i < arr.length(); i++) {
                    DownloadTask t = fromJson(arr.getJSONObject(i));
                    if (t != null) {
                        // Don't auto-resume on cold boot; user can hit Resume.
                        if (t.status == DownloadTask.Status.DOWNLOADING
                                || t.status == DownloadTask.Status.PENDING) {
                            t.status = DownloadTask.Status.PAUSED;
                        }
                        tasks.add(t);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + e.getMessage());
        }
    }

    private static JSONObject toJson(DownloadTask t) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", t.id);
        o.put("url", t.url);
        o.put("pageUrl", t.pageUrl);
        o.put("type", t.type.name());
        o.put("status", t.status.name());
        o.put("title", t.title);
        o.put("filename", t.filename);
        o.put("progress", t.progress);
        o.put("downloadedBytes", t.downloadedBytes);
        o.put("totalBytes", t.totalBytes);
        o.put("outputPath", t.outputPath);
        o.put("error", t.error);
        o.put("addedAt", t.addedAt);
        o.put("doneAt", t.doneAt);
        return o;
    }

    private static DownloadTask fromJson(JSONObject o) {
        try {
            DownloadTask t = new DownloadTask();
            t.id = o.optString("id", "");
            t.url = o.optString("url", "");
            t.pageUrl = o.optString("pageUrl", null);
            try { t.type = DownloadTask.Type.valueOf(o.optString("type", "UNKNOWN")); } catch (Exception e) { t.type = DownloadTask.Type.UNKNOWN; }
            try { t.status = DownloadTask.Status.valueOf(o.optString("status", "PENDING")); } catch (Exception e) { t.status = DownloadTask.Status.FAILED; }
            t.title = o.optString("title", null);
            t.filename = o.optString("filename", "media");
            t.progress = o.optInt("progress", 0);
            t.downloadedBytes = o.optLong("downloadedBytes", 0);
            t.totalBytes = o.optLong("totalBytes", -1);
            t.outputPath = o.optString("outputPath", null);
            t.error = o.optString("error", null);
            t.addedAt = o.optLong("addedAt", 0);
            t.doneAt = o.optLong("doneAt", 0);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    // --------------------------- listeners -------------------------------

    private void notifyChanged() {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (Listener l : copy) l.onChanged();
    }

    // --------------------------- notifications ---------------------------

    private NotificationManager nm() {
        return (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private PendingIntent openDownloadsIntent() {
        Intent i = new Intent(appCtx, DownloadActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(appCtx, 0, i, flag);
    }

    private int notifIdFor(DownloadTask t) {
        return NOTIF_ID_BASE + Math.abs(t.id.hashCode()) % 1000;
    }

    private void updateNotification(DownloadTask t) {
        Notification n = baseBuilder(t, "Downloading " + t.filename,
                t.progress + "%")
                .setProgress(100, t.progress, false)
                .setOngoing(true)
                .build();
        NotificationManager m = nm();
        if (m != null) m.notify(notifIdFor(t), n);
    }

    private void finishNotification(DownloadTask t) {
        cancelNotification(t);
        Notification n = baseBuilder(t, "Download complete", t.filename)
                .setContentIntent(openDownloadsIntent())
                .setAutoCancel(true)
                .build();
        NotificationManager m = nm();
        if (m != null) m.notify(notifIdFor(t), n);
    }

    private void errorNotification(DownloadTask t) {
        cancelNotification(t);
        Notification n = baseBuilder(t, "Download failed",
                t.error != null ? t.error : t.filename)
                .setAutoCancel(true)
                .build();
        NotificationManager m = nm();
        if (m != null) m.notify(notifIdFor(t), n);
    }

    private void cancelNotification(DownloadTask t) {
        NotificationManager m = nm();
        if (m != null) m.cancel(notifIdFor(t));
    }

    private NotificationCompat.Builder baseBuilder(DownloadTask t, String title, String text) {
        return new NotificationCompat.Builder(appCtx, MiniApp.CHANNEL_DOWNLOAD)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setContentIntent(openDownloadsIntent())
                .setPriority(NotificationCompat.PRIORITY_LOW);
    }
}
