package com.minibrowser.security;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import com.minibrowser.MainActivity;
import com.minibrowser.database.entities.CredentialEntity;
import com.minibrowser.database.repositories.CredentialRepository;
import java.lang.ref.WeakReference;
import java.util.List;

public class AutofillBridge {
    private static final String TAG = "AutofillBridge";
    private final WeakReference<MainActivity> activityRef;
    private final CredentialRepository repository;
    private final String secureToken;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AutofillBridge(MainActivity activity, String secureToken) {
        this.activityRef = new WeakReference<>(activity);
        this.repository = new CredentialRepository(activity);
        this.secureToken = secureToken;
    }

    @JavascriptInterface
    public void onFormDetected(String domain, String token) {
        if (!secureToken.equals(token)) return;
        Log.d(TAG, "Form detected on domain: " + domain);
    }

    @JavascriptInterface
    public void onRequestAutofill(final String domain, String token) {
        if (!secureToken.equals(token)) return;
        final MainActivity activity = activityRef.get();
        if (activity == null) return;

        mainHandler.post(() -> {
            List<CredentialEntity> list = repository.getCredentialsForDomain(domain, "default_user");
            if (list == null || list.isEmpty()) {
                return;
            }

            final CredentialEntity cred = list.get(0);
            
            BiometricHelper.authenticate(activity, "Autofill Credentials", "Authorize autofill for " + domain, new BiometricHelper.AuthCallback() {
                @Override
                public void onAuthenticated() {
                    String decryptedPass = KeystoreHelper.decrypt(cred.encryptedPassword);
                    
                    activity.runOnUiThread(() -> {
                        String js = "(function(user, pass) {"
                                + "  var passwordField = document.querySelector('input[type="password"]');"
                                + "  if (passwordField) {"
                                + "    var form = passwordField.form;"
                                + "    var usernameField = null;"
                                + "    if (form) {"
                                + "      usernameField = form.querySelector('input[type="text"], input[type="email"], input[name*="user"], input[name*="email"]');"
                                + "    } else {"
                                + "      usernameField = document.querySelector('input[type="text"], input[type="email"]');"
                                + "    }"
                                + "    if (usernameField) usernameField.value = user;"
                                + "    passwordField.value = pass;"
                                + "  }"
                                + "})('" + cred.username.replace("'", "\'") + "', '" + decryptedPass.replace("'", "\'") + "');";
                        
                        activity.getWebview().evaluateJavascript(js, null);
                        Toast.makeText(activity, "Autofilled credentials safely!", Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(String err) {
                    Toast.makeText(activity, "Authentication failed: " + err, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @JavascriptInterface
    public void onFormSubmitted(final String domain, final String user, final String pass, String token) {
        if (!secureToken.equals(token)) return;
        if (user == null || user.trim().isEmpty() || pass == null || pass.trim().isEmpty()) return;

        final MainActivity activity = activityRef.get();
        if (activity == null) return;

        mainHandler.post(() -> {
            List<CredentialEntity> existing = repository.getCredentialsForDomain(domain, "default_user");
            if (existing != null && !existing.isEmpty()) {
                for (CredentialEntity c : existing) {
                    if (user.equals(c.username)) return;
                }
            }

            new AlertDialog.Builder(activity)
                    .setTitle("Save Password?")
                    .setMessage("Would you like MiniBrowser to encrypt and save your credentials for " + domain + "?")
                    .setPositiveButton("Save", (dialog, which) -> {
                        String encrypted = KeystoreHelper.encrypt(pass);
                        repository.saveCredential(domain, user, encrypted, "default_user");
                        Toast.makeText(activity, "Credentials encrypted and saved safely!", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("No thanks", null)
                    .show();
        });
    }
}
