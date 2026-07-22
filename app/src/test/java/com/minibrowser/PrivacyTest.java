package com.minibrowser;

import org.junit.Test;
import static org.junit.Assert.*;

public class PrivacyTest {
    @Test
    public void testUrlValidationAndFingerprintJsStringEscapes() {
        assertTrue(BrowserCore.looksLikeUrl("google.com"));
        assertTrue(BrowserCore.looksLikeUrl("localhost"));
        assertFalse(BrowserCore.looksLikeUrl("not a url"));
        assertFalse(BrowserCore.looksLikeUrl(null));
        
        assertEquals("'hello'", BrowserCore.jsString("hello"));
        assertEquals("'it\'s'", BrowserCore.jsString("it's"));
        assertEquals("'\u003cscript\u003e'", BrowserCore.jsString("<script>"));
    }
}
