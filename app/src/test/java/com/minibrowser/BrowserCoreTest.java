package com.minibrowser;

import android.net.Uri;
import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserCoreTest {

    // ---- looksLikeUrl ----

    @Test
    public void looksLikeUrl_validDomains() {
        assertTrue(BrowserCore.looksLikeUrl("example.com"));
        assertTrue(BrowserCore.looksLikeUrl("sub.example.com"));
        assertTrue(BrowserCore.looksLikeUrl("a.co"));
        assertTrue(BrowserCore.looksLikeUrl("google.co.uk"));
        assertTrue(BrowserCore.looksLikeUrl("localhost"));
        assertTrue(BrowserCore.looksLikeUrl("example.com/path"));
        assertTrue(BrowserCore.looksLikeUrl("example.com:8080"));
    }

    @Test
    public void looksLikeUrl_notUrls() {
        assertFalse(BrowserCore.looksLikeUrl("hello world"));
        assertFalse(BrowserCore.looksLikeUrl("no dot"));
        assertFalse(BrowserCore.looksLikeUrl(""));
        assertFalse(BrowserCore.looksLikeUrl("x")); // TLD too short
        assertFalse(BrowserCore.looksLikeUrl("spaces have tabs\there"));
    }

    @Test
    public void looksLikeUrl_edgeCases() {
        assertTrue(BrowserCore.looksLikeUrl("1.2.3.4")); // looks like TLD is "3.4" which is valid chars
        assertFalse(BrowserCore.looksLikeUrl("a".repeat(25))); // TLD too long
    }

    // ---- jsString ----

    @Test
    public void jsString_plain() {
        assertEquals("'hello'", BrowserCore.jsString("hello"));
    }

    @Test
    public void jsString_empty() {
        assertEquals("''", BrowserCore.jsString(""));
    }

    @Test
    public void jsString_escapesSingleQuote() {
        assertEquals("'it\\'s'", BrowserCore.jsString("it's"));
    }

    @Test
    public void jsString_escapesBackslash() {
        assertEquals("'a\\\\b'", BrowserCore.jsString("a\\b"));
    }

    @Test
    public void jsString_escapesNewlines() {
        assertEquals("'line1\\nline2'", BrowserCore.jsString("line1\nline2"));
    }

    @Test
    public void jsString_escapesAngleBrackets() {
        assertEquals("'\\u003cscript\\u003e'", BrowserCore.jsString("<script>"));
    }

    @Test
    public void jsString_escapesUnicodeLineSeparators() {
        assertEquals("'\\u2028'", BrowserCore.jsString("\u2028"));
        assertEquals("'\\u2029'", BrowserCore.jsString("\u2029"));
    }

    @Test
    public void jsString_mixedSpecialChars() {
        String input = "it's a \\test\nwith\ttabs";
        String result = BrowserCore.jsString(input);
        assertTrue(result.startsWith("'"));
        assertTrue(result.endsWith("'"));
        assertFalse(result.contains("\n"));  // newline escaped
        assertFalse(result.contains("\t"));  // tab escaped
    }

    // ---- isHeavyResource (via Uri) ----

    @Test
    public void isHeavyResource_videoExtensions() {
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/video.mp4")));
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/clip.webm")));
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/movie.mkv")));
    }

    @Test
    public void isHeavyResource_audioExtensions() {
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/song.mp3")));
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/track.flac")));
    }

    @Test
    public void isHeavyResource_streamExtensions() {
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/live.m3u8")));
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/manifest.mpd")));
    }

    @Test
    public void isHeavyResource_lightResources() {
        assertFalse(BrowserCore.isHeavyResource(Uri.parse("https://example.com/page.html")));
        assertFalse(BrowserCore.isHeavyResource(Uri.parse("https://example.com/style.css")));
        assertFalse(BrowserCore.isHeavyResource(Uri.parse("https://example.com/app.js")));
        assertFalse(BrowserCore.isHeavyResource(Uri.parse("https://example.com/image.png")));
    }

    @Test
    public void isHeavyResource_noPath() {
        assertFalse(BrowserCore.isHeavyResource(Uri.parse("https://example.com")));
    }

    @Test
    public void isHeavyResource_caseInsensitive() {
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/VIDEO.MP4")));
        assertTrue(BrowserCore.isHeavyResource(Uri.parse("https://example.com/Song.MP3")));
    }

    // ---- isSuffixBlocked requires BrowserCore instance; tested via integration ----
}
