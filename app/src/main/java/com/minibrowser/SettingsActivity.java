package com.minibrowser;

import android.app.Activity;
import android.os.Bundle;
import com.minibrowser.security.InputValidator;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public boolean validateCustomCss(String css) {
        return InputValidator.isCssValid(css);
    }

    public boolean validateCustomJs(String js) {
        return InputValidator.isJsValid(js);
    }
}
