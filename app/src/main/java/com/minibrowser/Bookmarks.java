package com.minibrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Bookmarks — a tiny, thread-safe store backed by SharedPreferences (JSON).
 *
 * No third-party deps: uses the platform org.json. Reads happen synchronously
 * in the constructor (small data); every mutation snapshots under a lock and
 * flushes to disk on a BACKGROUND thread (SharedPreferences.apply() is itself
 * async, but JSON serialization is also moved off the UI thread to honour the
 * "no work on the UI thread" rule).
 */
public class Bookmarks {

    private static final String TAG = "Bookmarks";
    private static final String PREFS = "minibrowser";
    private static final String KEY = "bookmarks";

    public static final class Entry {
        public final String title;
        public final String url;
        public Entry(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private final SharedPreferences prefs;
    private final List<Entry> entries = new ArrayList<>();

    public Bookmarks(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        String json = prefs.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String title = o.optString("title", "");
                String url = o.optString("url", "");
                if (url != null && !url.isEmpty()) {
                    entries.add(new Entry(title, url));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "corrupt bookmarks json: " + e.getMessage());
        }
    }

    /** A defensive copy of all bookmarks (most-recent first). */
    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized boolean contains(String url) {
        if (url == null) return false;
        for (Entry e : entries) {
            if (url.equals(e.url)) return true;
        }
        return false;
    }

    /** Add (or re-add at top) a bookmark. */
    public synchronized void add(String title, String url) {
        if (url == null || url.isEmpty()) return;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (url.equals(it.next().url)) it.remove();
        }
        entries.add(0, new Entry(safeTitle(title, url), url));
        persist();
    }

    public synchronized void remove(String url) {
        if (url == null) return;
        boolean changed = false;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (url.equals(it.next().url)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) persist();
    }

    private static String safeTitle(String title, String url) {
        if (title == null || title.trim().isEmpty()) return url;
        return title.trim();
    }

    private void persist() {
        final JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("title", e.title);
                o.put("url", e.url);
            } catch (JSONException ignored) { }
            arr.put(o);
        }
        final String json = arr.toString();
        new Thread(() -> prefs.edit().putString(KEY, json).apply(), "BookmarkSave").start();
    }
}
