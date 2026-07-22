package com.minibrowser.tab;

import com.minibrowser.BrowserCore;
import com.minibrowser.GestureWebView;

public class Tab {
    public final String id;
    public final GestureWebView webView;
    public final BrowserCore core;
    public String title = "New Tab";
    public String url = BrowserCore.HOME;
    public boolean isHome = true;
    public String userId;

    public Tab(String id, GestureWebView webView, BrowserCore core, String userId) {
        this.id = id;
        this.webView = webView;
        this.core = core;
        this.userId = userId;
    }
}
