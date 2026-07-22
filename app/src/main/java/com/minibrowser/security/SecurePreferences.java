package com.minibrowser.security;

import android.content.Context;
import android.content.SharedPreferences;

public class SecurePreferences {
    private final SharedPreferences prefs;

    public SecurePreferences(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences("minibrowser_secure_keys", Context.MODE_PRIVATE);
    }

    public void putString(String key, String val) {
        if (val == null) {
            prefs.edit().remove(key).apply();
            return;
        }
        String encrypted = KeystoreHelper.encrypt(val);
        prefs.edit().putString(key, encrypted).apply();
    }

    public String getString(String key, String def) {
        String encrypted = prefs.getString(key, null);
        if (encrypted == null) return def;
        return KeystoreHelper.decrypt(encrypted);
    }
}
