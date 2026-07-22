package com.minibrowser.security;

import java.net.MalformedURLException;
import java.net.URL;

public class InputValidator {
    public static boolean isEndpointValid(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            new URL(url);
            return url.startsWith("http://") || url.startsWith("https://");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    public static boolean isCssValid(String css) {
        if (css == null) return false;
        return !css.contains("javascript:") && !css.contains("<script");
    }

    public static boolean isJsValid(String js) {
        if (js == null) return false;
        return !js.contains("AndroidBlock.") && !js.contains("__mbNav.");
    }

    public static boolean isBlocklistUrlValid(String url) {
        return isEndpointValid(url);
    }
}
