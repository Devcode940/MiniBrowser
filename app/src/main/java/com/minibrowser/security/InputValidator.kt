package com.minibrowser.security

import java.net.MalformedURLException
import java.net.URL

object InputValidator {
    @JvmStatic
    fun isEndpointValid(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            URL(url)
            url.startsWith("http://") || url.startsWith("https://")
        } catch (e: MalformedURLException) {
            false
        }
    }

    @JvmStatic
    fun isCssValid(css: String?): Boolean {
        if (css == null) return false
        return !css.contains("javascript:") && !css.contains("<script")
    }

    @JvmStatic
    fun isJsValid(js: String?): Boolean {
        if (js == null) return false
        return !js.contains("AndroidBlock.") && !js.contains("__mbNav.")
    }

    @JvmStatic
    fun isBlocklistUrlValid(url: String?): Boolean {
        return isEndpointValid(url)
    }
}
