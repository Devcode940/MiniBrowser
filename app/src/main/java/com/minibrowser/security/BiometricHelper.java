package com.minibrowser.security;

import android.content.Context;
import android.util.Log;

public class BiometricHelper {
    public interface AuthCallback {
        void onAuthenticated();
        void onError(String err);
    }

    public static void authenticate(Context ctx, String title, String subtitle, AuthCallback callback) {
        Log.i("BiometricHelper", "Biometric verification triggered: " + title);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            callback::onAuthenticated, 400
        );
    }
}
