package com.minibrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolRegistry — owns the Tools-sheet menu: ordering, visibility, and custom
 * user-defined URL tools.
 *
 * Built-in tools are declared once here; their ACTIONS live in MainActivity
 * (buildToolRow). The registry only manages which are visible and in what
 * order, plus any custom "open URL" tools the user adds.
 *
 * Storage (SharedPreferences "minibrowser"):
 *   • "tool_order"     CSV of tool ids (ordered); missing ids keep default order.
 *   • "tool_vis_<id>"  boolean visibility override.
 *   • "custom_tools"   JSON array of {id,name,url}.
 */
public final class ToolRegistry {

    public static final class Tool {
        public final String id;
        public final String label;
        public final String url;       // null for built-ins
        public final boolean isToggle; // only "block"
        public boolean visible;
        public Tool(String id, String label, String url, boolean isToggle, boolean visible) {
            this.id = id; this.label = label; this.url = url;
            this.isToggle = isToggle; this.visible = visible;
        }
    }

    private static final String PREFS = "minibrowser";
    private static final String K_ORDER = "tool_order";
    private static final String K_CUSTOM = "custom_tools";

    // id -> {label, defaultVisible, isToggle}
    private static final Map<String, Object[]> BUILTINS = new LinkedHashMap<>();
    static {
        BUILTINS.put("block",        new Object[]{"Click-to-Block", Boolean.TRUE, Boolean.TRUE});
        BUILTINS.put("translate",    new Object[]{"Translate page", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("download",     new Object[]{"Download media", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("play",         new Object[]{"Play media", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("snack",        new Object[]{"Play in snack", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("downloads",    new Object[]{"Open Downloads", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("ai",           new Object[]{"AI Chat", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("fullscreen",   new Object[]{"Fullscreen", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("offlinesave",  new Object[]{"Save page offline", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("notepad",      new Object[]{"Open Notepad", Boolean.TRUE, Boolean.FALSE});
        BUILTINS.put("css",          new Object[]{"Edit custom.css", Boolean.FALSE, Boolean.FALSE});
        BUILTINS.put("js",           new Object[]{"Edit userscript.js", Boolean.FALSE, Boolean.FALSE});
        BUILTINS.put("offlinepages", new Object[]{"Offline pages", Boolean.TRUE, Boolean.FALSE});
    }

    /** Ordered + visibility-resolved list: built-ins (in persisted order) then customs. */
    public static List<Tool> load(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Build id order: persisted order first, then any defaults not listed.
        List<String> order = new ArrayList<>();
        String csv = p.getString(K_ORDER, null);
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty() && BUILTINS.containsKey(t) && !order.contains(t)) order.add(t);
            }
        }
        for (String id : BUILTINS.keySet()) if (!order.contains(id)) order.add(id);

        List<Tool> out = new ArrayList<>();
        for (String id : order) {
            Object[] meta = BUILTINS.get(id);
            boolean defVis = (Boolean) meta[1];
            boolean isToggle = (Boolean) meta[2];
            boolean vis = p.getBoolean("tool_vis_" + id, defVis);
            out.add(new Tool(id, (String) meta[0], null, isToggle, vis));
        }
        for (Tool c : loadCustoms(ctx)) out.add(c);
        return out;
    }

    public static void setVisible(Context ctx, String id, boolean visible) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("tool_vis_" + id, visible).apply();
    }

    public static void moveUp(Context ctx, String id) {
        List<String> order = persistedOrder(ctx);
        int i = order.indexOf(id);
        if (i > 0) {
            order.remove(i); order.add(i - 1, id);
            saveOrder(ctx, order);
        }
    }

    public static void moveDown(Context ctx, String id) {
        List<String> order = persistedOrder(ctx);
        int i = order.indexOf(id);
        if (i >= 0 && i < order.size() - 1) {
            order.remove(i); order.add(i + 1, id);
            saveOrder(ctx, order);
        }
    }

    public static void addCustom(Context ctx, String name, String url) {
        if (name == null || name.trim().isEmpty() || url == null || url.trim().isEmpty()) return;
        List<Tool> customs = loadCustoms(ctx);
        String id = "custom_" + System.currentTimeMillis();
        customs.add(new Tool(id, name.trim(), url.trim(), false, true));
        saveCustoms(ctx, customs);
    }

    public static void removeCustom(Context ctx, String id) {
        if (id == null || !id.startsWith("custom_")) return;
        List<Tool> keep = new ArrayList<>();
        for (Tool t : loadCustoms(ctx)) if (!t.id.equals(id)) keep.add(t);
        saveCustoms(ctx, keep);
    }

    // ---------------- internals ----------------

    private static List<String> persistedOrder(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> order = new ArrayList<>();
        String csv = p.getString(K_ORDER, null);
        if (csv != null) for (String s : csv.split(",")) {
            String t = s.trim();
            if (BUILTINS.containsKey(t) && !order.contains(t)) order.add(t);
        }
        for (String id : BUILTINS.keySet()) if (!order.contains(id)) order.add(id);
        return order;
    }

    private static void saveOrder(Context ctx, List<String> order) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(order.get(i));
        }
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_ORDER, sb.toString()).apply();
    }

    private static List<Tool> loadCustoms(Context ctx) {
        List<Tool> out = new ArrayList<>();
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = p.getString(K_CUSTOM, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Tool(
                        o.optString("id", ""),
                        o.optString("name", "Custom"),
                        o.optString("url", ""),
                        false, true));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void saveCustoms(Context ctx, List<Tool> customs) {
        JSONArray arr = new JSONArray();
        for (Tool t : customs) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", t.id); o.put("name", t.label); o.put("url", t.url);
            } catch (Exception ignored) { }
            arr.put(o);
        }
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_CUSTOM, arr.toString()).apply();
    }
}
