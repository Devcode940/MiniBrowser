package com.minibrowser.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AiClient — multi-provider OpenAI-compatible chat client.
 *
 * Ships with presets for many providers (ChatGPT, DeepSeek, Kimi, Qwen, Grok,
 * Gemini, OpenRouter, Copilot, Arena, Blackbox). Every preset uses the
 * provider's OpenAI-compatible /chat/completions endpoint, so the same request
 * shape works for all. Users can also add CUSTOM providers, and override the
 * endpoint / key / model per provider.
 *
 * Keys are stored per-provider in SharedPreferences and never leave the device
 * except to the chosen provider's endpoint. Zero telemetry.
 */
public class AiClient {

    private static final String TAG = "AiClient";
    private static final String PREFS = "minibrowser";
    private static final String K_ACTIVE = "ai_provider";
    private static final String K_CUSTOM = "ai_custom_providers";
    
    // Rate limiting: minimum 2 seconds between requests to prevent API abuse
    private static final long MIN_REQUEST_INTERVAL_MS = 2000;
    private static long lastRequestTime = 0;
    private static final Object requestLock = new Object();

    /** A chat provider. {@code endpoint} is the FULL chat-completions URL. */
    public static final class Provider {
        public final String id;
        public final String name;
        public final String endpoint;
        public final String defaultModel;
        public final boolean builtin;
        public Provider(String id, String name, String endpoint, String defaultModel, boolean builtin) {
            this.id = id; this.name = name; this.endpoint = endpoint;
            this.defaultModel = defaultModel; this.builtin = builtin;
        }
    }

    // ---------------- provider presets ----------------
    // All are OpenAI-compatible POST /chat/completions endpoints.
    private static final Provider[] BUILTINS = {
        new Provider("openai", "ChatGPT (OpenAI)",
                "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", true),
        new Provider("gemini", "Gemini (Google)",
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                "gemini-1.5-flash", true),
        new Provider("deepseek", "DeepSeek",
                "https://api.deepseek.com/chat/completions", "deepseek-chat", true),
        new Provider("kimi", "Kimi (Moonshot)",
                "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k", true),
        new Provider("qwen", "Qwen (DashScope)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "qwen-turbo", true),
        new Provider("grok", "Grok (xAI)",
                "https://api.x.ai/v1/chat/completions", "grok-2", true),
        new Provider("openrouter", "OpenRouter",
                "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini", true),
        new Provider("copilot", "Copilot (GitHub)",
                "https://api.githubcopilot.com/chat/completions", "gpt-4o", true),
        new Provider("arena", "Arena",
                "", "arena-model", true),       // configure endpoint + key
        new Provider("blackbox", "Blackbox",
                "", "blackbox-model", true),    // configure endpoint + key
    };

    public static final class Msg {
        public final boolean user;
        public final String text;
        public Msg(boolean u, String t) { user = u; text = t; }
    }

    public interface Callback {
        void onReply(String text);
        void onError(String message);
    }

    private static final String SECURE_PREFS = "minibrowser_secure";
    private static final String K_MIGRATED = "ai_keys_migrated_to_secure_store";

    private final SharedPreferences prefs;
    private final SharedPreferences securePrefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AiClient(Context c) {
        Context appCtx = c.getApplicationContext();
        prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        securePrefs = buildSecurePrefs(appCtx);
        migrateLegacyKeysIfNeeded();
    }

    /**
     * API keys are sensitive credentials and are stored in a dedicated,
     * AES-256-GCM-encrypted preferences file backed by the Android Keystore —
     * never in the plain "minibrowser" prefs used for non-sensitive settings.
     * Falls back to a plain (unencrypted) file only if Keystore setup itself
     * fails (e.g. a broken device keystore); this keeps the app usable while
     * still isolating keys from the general prefs file.
     */
    private static SharedPreferences buildSecurePrefs(Context appCtx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    appCtx,
                    SECURE_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Failed to init encrypted key store, falling back to isolated plain prefs: " + e.getMessage());
            return appCtx.getSharedPreferences(SECURE_PREFS + "_fallback", Context.MODE_PRIVATE);
        }
    }

    /** One-time move of any keys previously saved in the plaintext "minibrowser" prefs. */
    private void migrateLegacyKeysIfNeeded() {
        if (prefs.getBoolean(K_MIGRATED, false)) return;
        SharedPreferences.Editor plainEditor = prefs.edit();
        SharedPreferences.Editor secureEditor = securePrefs.edit();
        boolean movedAny = false;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("ai_key_")) {
                String value = prefs.getString(key, null);
                if (value != null && !value.isEmpty()) {
                    secureEditor.putString(key, value);
                    movedAny = true;
                }
                plainEditor.remove(key);
            }
        }
        plainEditor.putBoolean(K_MIGRATED, true);
        plainEditor.apply();
        if (movedAny) secureEditor.apply();
        if (movedAny) Log.i(TAG, "Migrated AI provider keys to encrypted storage.");
    }

    // ---------------- provider management ----------------

    public List<Provider> getProviders() {
        List<Provider> out = new ArrayList<>();
        for (Provider p : BUILTINS) out.add(p);
        for (Provider p : loadCustoms()) out.add(p);
        return out;
    }

    public String getActiveProviderId() {
        String id = prefs.getString(K_ACTIVE, null);
        if (id == null || findProvider(id) == null) {
            id = BUILTINS[0].id;
        }
        return id;
    }

    public void setActiveProvider(String id) {
        if (findProvider(id) != null) {
            prefs.edit().putString(K_ACTIVE, id).apply();
        }
    }

    public Provider getActiveProvider() {
        return findProvider(getActiveProviderId());
    }

    private Provider findProvider(String id) {
        if (id == null) return null;
        for (Provider p : BUILTINS) if (p.id.equals(id)) return p;
        for (Provider p : loadCustoms()) if (p.id.equals(id)) return p;
        return null;
    }

    public void addCustomProvider(String name, String endpoint, String model) {
        if (name == null || name.trim().isEmpty()) return;
        String id = "custom_" + System.currentTimeMillis();
        List<Provider> customs = loadCustoms();
        customs.add(new Provider(id, name.trim(),
                endpoint == null ? "" : endpoint.trim(),
                model == null ? "" : model.trim(), false));
        saveCustoms(customs);
        prefs.edit().putString(K_ACTIVE, id).apply();
    }

    public void removeCustomProvider(String id) {
        if (id == null || !id.startsWith("custom_")) return;
        List<Provider> customs = loadCustoms();
        List<Provider> keep = new ArrayList<>();
        for (Provider p : customs) if (!p.id.equals(id)) keep.add(p);
        saveCustoms(keep);
        securePrefs.edit().remove("ai_key_" + id).apply();
        prefs.edit().remove("ai_endpoint_" + id).remove("ai_model_" + id).apply();
        if (id.equals(getActiveProviderId())) {
            prefs.edit().putString(K_ACTIVE, BUILTINS[0].id).apply();
        }
    }

    private List<Provider> loadCustoms() {
        List<Provider> out = new ArrayList<>();
        String json = prefs.getString(K_CUSTOM, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Provider(
                        o.optString("id", ""),
                        o.optString("name", "Custom"),
                        o.optString("endpoint", ""),
                        o.optString("model", ""),
                        false));
            }
        } catch (Exception e) {
            Log.w(TAG, "loadCustoms: " + e.getMessage());
        }
        return out;
    }

    private void saveCustoms(List<Provider> customs) {
        JSONArray arr = new JSONArray();
        for (Provider p : customs) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", p.id); o.put("name", p.name);
                o.put("endpoint", p.endpoint); o.put("model", p.defaultModel);
            } catch (Exception ignored) { }
            arr.put(o);
        }
        prefs.edit().putString(K_CUSTOM, arr.toString()).apply();
    }

    // ---------------- per-provider credentials ----------------

    public String getEndpoint() {
        String id = getActiveProviderId();
        String override = prefs.getString("ai_endpoint_" + id, null);
        if (override != null && !override.trim().isEmpty()) return override.trim();
        Provider p = findProvider(id);
        return p != null ? p.endpoint : "";
    }
    public void setEndpoint(String u) {
        prefs.edit().putString("ai_endpoint_" + getActiveProviderId(), u == null ? "" : u.trim()).apply();
    }

    public String getKey() { return securePrefs.getString("ai_key_" + getActiveProviderId(), ""); }
    public void setKey(String k) {
        securePrefs.edit().putString("ai_key_" + getActiveProviderId(), k == null ? "" : k.trim()).apply();
    }

    public String getModel() {
        String id = getActiveProviderId();
        String override = prefs.getString("ai_model_" + id, null);
        if (override != null && !override.trim().isEmpty()) return override.trim();
        Provider p = findProvider(id);
        return p != null ? p.defaultModel : "";
    }
    public void setModel(String m) {
        prefs.edit().putString("ai_model_" + getActiveProviderId(), m == null ? "" : m.trim()).apply();
    }

    public boolean isConfigured() {
        String e = getEndpoint();
        return e != null && !e.trim().isEmpty();
    }

    // ---------------- chat ----------------

    public void send(final List<Msg> history, final Callback cb) {
        if (!isConfigured()) {
            cb.onError("Set an endpoint for " + getActiveProvider().name + " first.");
            return;
        }
        
        // Rate limiting check
        synchronized (requestLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTime;
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                final long waitTime = (MIN_REQUEST_INTERVAL_MS - elapsed) / 1000;
                main.post(() -> cb.onError("Rate limited. Wait " + waitTime + "s before next request."));
                return;
            }
            lastRequestTime = now;
        }
        
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(getEndpoint().trim()).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                String key = getKey();
                if (key != null && !key.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                }
                conn.setRequestProperty("HTTP-Referer", "https://minibrowser.app");
                conn.setRequestProperty("X-Title", "MiniBrowser");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);

                JSONArray msgs = new JSONArray();
                for (Msg m : history) {
                    JSONObject o = new JSONObject();
                    o.put("role", m.user ? "user" : "assistant");
                    o.put("content", m.text);
                    msgs.put(o);
                }
                JSONObject body = new JSONObject();
                body.put("model", getModel());
                body.put("messages", msgs);
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(data); }

                int code = conn.getResponseCode();
                InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                String resp = readAll(is);
                if (code >= 400) {
                    final String msg = "HTTP " + code + ": " + snippet(resp);
                    main.post(() -> cb.onError(msg));
                    return;
                }
                JSONObject root = new JSONObject(resp);
                JSONArray choices = root.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    main.post(() -> cb.onError("Empty AI response"));
                    return;
                }
                String reply = choices.getJSONObject(0)
                        .getJSONObject("message").optString("content", "").trim();
                if (reply.isEmpty()) reply = choices.getJSONObject(0).optString("text", "").trim();
                final String out = reply;
                main.post(() -> cb.onReply(out));
            } catch (Exception e) {
                main.post(() -> cb.onError("AI error: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "AiClient").start();
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private static String snippet(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
