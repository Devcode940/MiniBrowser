package com.minibrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CloudSyncClient — handles backup and restore of bookmarks and notepad to an
 * external REST web service.
 *
 * Adhering to MiniBrowser's privacy-focused and zero-telemetry rules, the syncing
 * endpoint is fully customizable so users can self-host their own sync server
 * or use any standard JSON storage service.
 *
 * It communicates with the server using standard HttpURLConnection POST (Backup)
 * and GET (Restore) methods, carrying an optional Authorization Token header.
 */
public class CloudSyncClient {

    private static final String TAG = "CloudSyncClient";
    private static final String PREFS = "minibrowser";
    private static final String KEY_ENDPOINT = "sync_endpoint";
    private static final String KEY_TOKEN = "sync_token";
    private static final String NOTEPAD_FILE = "notepad.txt";

    public interface Callback {
        void onSuccess(String msg);
        void onError(String err);
    }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    public CloudSyncClient(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getEndpoint() {
        return prefs.getString(KEY_ENDPOINT, "");
    }

    public void setEndpoint(String url) {
        prefs.edit().putString(KEY_ENDPOINT, url == null ? "" : url.trim()).apply();
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, "");
    }

    public void setToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public boolean isConfigured() {
        String ep = getEndpoint();
        return ep != null && !ep.isEmpty() && (ep.startsWith("http://") || ep.startsWith("https://"));
    }

    // ========================================== BACKUP (PUSH) ==========================================

    public void backup(final Context ctx, final Bookmarks bookmarks, final Callback cb) {
        if (!isConfigured()) {
            cb.onError("Please configure a valid Sync Endpoint URL first.");
            return;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // 1. Serialize local Bookmarks
                JSONArray bArray = new JSONArray();
                for (Bookmarks.Entry e : bookmarks.snapshot()) {
                    JSONObject obj = new JSONObject();
                    obj.put("title", e.title);
                    obj.put("url", e.url);
                    bArray.put(obj);
                }

                // 2. Read local Notepad content
                String noteContent = readNotepad(ctx);

                // 3. Assemble complete JSON sync payload
                JSONObject root = new JSONObject();
                root.put("version", 1);
                root.put("timestamp", System.currentTimeMillis());
                root.put("bookmarks", bArray);
                root.put("notepad", noteContent);

                // 4. Send POST request
                conn = (HttpURLConnection) new URL(getEndpoint()).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                
                String token = getToken();
                if (token != null && !token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }
                
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setDoOutput(true);

                byte[] bodyBytes = root.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    main.post(() -> cb.onSuccess("Backup successful! Data uploaded to cloud."));
                } else {
                    String errDetail = "HTTP " + code;
                    InputStream es = conn.getErrorStream();
                    if (es != null) {
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
                            String firstLine = r.readLine();
                            if (firstLine != null && !firstLine.trim().isEmpty()) {
                                errDetail += ": " + (firstLine.length() > 60 ? firstLine.substring(0, 60) + "..." : firstLine);
                            }
                        }
                    }
                    final String err = errDetail;
                    main.post(() -> cb.onError("Backup failed: " + err));
                }
            } catch (Exception e) {
                Log.e(TAG, "backup: " + e.getMessage(), e);
                main.post(() -> cb.onError("Sync Error: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "CloudBackup").start();
    }

    // ========================================== RESTORE (PULL) ==========================================

    public void restore(final Context ctx, final Bookmarks bookmarks, final Callback cb) {
        if (!isConfigured()) {
            cb.onError("Please configure a valid Sync Endpoint URL first.");
            return;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // 1. Send GET request
                conn = (HttpURLConnection) new URL(getEndpoint()).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                
                String token = getToken();
                if (token != null && !token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }

                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    // 2. Read response JSON
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        char[] buf = new char[4096];
                        int n;
                        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                    }

                    JSONObject root = new JSONObject(sb.toString());
                    
                    // 3. Parse Bookmarks
                    JSONArray bArray = root.optJSONArray("bookmarks");
                    final List<Bookmarks.Entry> parsedBookmarks = new ArrayList<>();
                    if (bArray != null) {
                        for (int i = 0; i < bArray.length(); i++) {
                            JSONObject obj = bArray.getJSONObject(i);
                            String title = obj.optString("title", "");
                            String url = obj.optString("url", "");
                            if (url != null && !url.isEmpty()) {
                                parsedBookmarks.add(new Bookmarks.Entry(title, url));
                            }
                        }
                    }

                    // 4. Parse Notepad
                    final String noteContent = root.optString("notepad", "");

                    // 5. Apply local state restoration
                    bookmarks.replaceAll(parsedBookmarks);
                    writeNotepad(ctx, noteContent);

                    main.post(() -> cb.onSuccess("Restore successful! Bookmarks and Notepad synced."));
                } else {
                    main.post(() -> cb.onError("Restore failed: HTTP " + code));
                }
            } catch (Exception e) {
                Log.e(TAG, "restore: " + e.getMessage(), e);
                main.post(() -> cb.onError("Restore Sync Error: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "CloudRestore").start();
    }

    // ========================================== HELPERS ==========================================

    private String readNotepad(Context ctx) {
        File file = new File(ctx.getFilesDir(), NOTEPAD_FILE);
        if (!file.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.deleteCharAt(sb.length() - 1);
            }
        } catch (IOException ignored) { }
        return sb.toString();
    }

    private void writeNotepad(Context ctx, String content) {
        File file = new File(ctx.getFilesDir(), NOTEPAD_FILE);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
        } catch (IOException ignored) { }
    }
}
