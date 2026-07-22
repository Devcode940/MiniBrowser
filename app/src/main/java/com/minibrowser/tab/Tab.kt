package com.minibrowser.tab

import com.minibrowser.BrowserCore
import com.minibrowser.GestureWebView

class Tab(
    @JvmField val id: String,
    @JvmField val webView: GestureWebView?,
    @JvmField val core: BrowserCore?,
    @JvmField var userId: String?
) {
    @JvmField var title: String = "New Tab"
    @JvmField var url: String = BrowserCore.HOME
    @JvmField var isHome: Boolean = true
    @JvmField var isPrivacyShieldsEnabled: Boolean = true

    constructor(webView: GestureWebView?, core: BrowserCore?, shields: Boolean, userId: String?) : this(
        id = Long.toHexString(java.security.SecureRandom().nextLong()),
        webView = webView,
        core = core,
        userId = userId
    ) {
        this.isPrivacyShieldsEnabled = shields
        if (!shields && core != null) {
            core.setAdBlockEnabled(false)
            core.setCompatibilityMode(true)
        }
    }
}
