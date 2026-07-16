package com.minibrowser.download;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSnifferTest {

    // ---- M3U8 classification ----

    @Test
    public void classify_m3u8ByExtension() {
        assertEquals(DownloadTask.Type.M3U8,
                MediaSniffer.classify("https://example.com/stream.m3u8"));
    }

    @Test
    public void classify_m3u8ByPath() {
        assertEquals(DownloadTask.Type.M3U8,
                MediaSniffer.classify("https://example.com/hls/live/stream.ts?token=abc"));
    }

    @Test
    public void classify_m3u8ByQueryParam() {
        assertEquals(DownloadTask.Type.M3U8,
                MediaSniffer.classify("https://example.com/api?file=video.m3u8"));
    }

    // ---- MPD classification ----

    @Test
    public void classify_mpdByExtension() {
        assertEquals(DownloadTask.Type.MPD,
                MediaSniffer.classify("https://example.com/manifest.mpd"));
    }

    @Test
    public void classify_mpdByPath() {
        assertEquals(DownloadTask.Type.MPD,
                MediaSniffer.classify("https://example.com/dash/manifest.mpd?v=2"));
    }

    // ---- Direct classification ----

    @Test
    public void classify_directMp4() {
        assertEquals(DownloadTask.Type.DIRECT,
                MediaSniffer.classify("https://example.com/video.mp4"));
    }

    @Test
    public void classify_directM4v() {
        assertEquals(DownloadTask.Type.DIRECT,
                MediaSniffer.classify("https://example.com/movie.m4v"));
    }

    @Test
    public void classify_directWebm() {
        assertEquals(DownloadTask.Type.DIRECT,
                MediaSniffer.classify("https://example.com/clip.webm"));
    }

    @Test
    public void classify_directMp3() {
        assertEquals(DownloadTask.Type.DIRECT,
                MediaSniffer.classify("https://example.com/song.mp3"));
    }

    @Test
    public void classify_directWithQueryString() {
        assertEquals(DownloadTask.Type.DIRECT,
                MediaSniffer.classify("https://example.com/video.mp4?quality=720p"));
    }

    // ---- Image classification ----

    @Test
    public void classify_imageJpg() {
        assertEquals(DownloadTask.Type.IMAGE,
                MediaSniffer.classify("https://example.com/photo.jpg"));
    }

    @Test
    public void classify_imagePng() {
        assertEquals(DownloadTask.Type.IMAGE,
                MediaSniffer.classify("https://example.com/screenshot.png"));
    }

    @Test
    public void classify_imageWebp() {
        assertEquals(DownloadTask.Type.IMAGE,
                MediaSniffer.classify("https://example.com/modern.webp"));
    }

    @Test
    public void classify_imageByContentType() {
        assertEquals(DownloadTask.Type.IMAGE,
                MediaSniffer.classify("https://example.com/serve?content-type=image/jpeg"));
    }

    // ---- Blob ----

    @Test
    public void classify_blobReturnsUnknown() {
        assertEquals(DownloadTask.Type.UNKNOWN,
                MediaSniffer.classify("blob:https://example.com/abc-123"));
    }

    // ---- Non-media ----

    @Test
    public void classify_htmlReturnsNull() {
        assertNull(MediaSniffer.classify("https://example.com/page.html"));
    }

    @Test
    public void classify_cssReturnsNull() {
        assertNull(MediaSniffer.classify("https://example.com/style.css"));
    }

    @Test
    public void classify_jsReturnsNull() {
        assertNull(MediaSniffer.classify("https://example.com/app.js"));
    }

    @Test
    public void classify_pdfReturnsNull() {
        assertNull(MediaSniffer.classify("https://example.com/doc.pdf"));
    }

    // ---- Edge cases ----

    @Test
    public void classify_nullReturnsNull() {
        assertNull(MediaSniffer.classify(null));
    }

    @Test
    public void classify_emptyReturnsNull() {
        assertNull(MediaSniffer.classify(""));
    }

    @Test
    public void classify_uppercaseExtension() {
        assertEquals(DownloadTask.Type.DIRECT,
                MediaSniffer.classify("https://example.com/VIDEO.MP4"));
    }

    @Test
    public void classify_mixedCase() {
        assertEquals(DownloadTask.Type.M3U8,
                MediaSniffer.classify("https://example.com/Stream.M3U8"));
    }

    // ---- DownloadTask.deriveFilename ----

    @Test
    public void deriveFilename_fromUrl() {
        DownloadTask task = new DownloadTask("https://example.com/video.mp4", null, DownloadTask.Type.DIRECT);
        assertEquals("video.mp4", task.filename);
    }

    @Test
    public void deriveFilename_stripsQueryString() {
        DownloadTask task = new DownloadTask("https://example.com/clip.webm?token=abc", null, DownloadTask.Type.DIRECT);
        assertEquals("clip.webm", task.filename);
    }

    @Test
    public void deriveFilename_noExtension_getsTypeExt() {
        DownloadTask task = new DownloadTask("https://example.com/stream", null, DownloadTask.Type.M3U8);
        assertEquals("stream.ts", task.filename);
    }

    @Test
    public void deriveFilename_unknownType_getsBin() {
        DownloadTask task = new DownloadTask("https://example.com/data", null, DownloadTask.Type.UNKNOWN);
        assertEquals("data.bin", task.filename);
    }

    @Test
    public void deriveFilename_nonMediaExt_replacedByTypeExt() {
        DownloadTask task = new DownloadTask("https://example.com/video.exe", null, DownloadTask.Type.DIRECT);
        assertEquals("video.mp4", task.filename);
    }

    @Test
    public void deriveFilename_mpdType() {
        DownloadTask task = new DownloadTask("https://example.com/manifest", null, DownloadTask.Type.MPD);
        assertEquals("manifest.mp4", task.filename);
    }

    @Test
    public void deriveFilename_imageType() {
        DownloadTask task = new DownloadTask("https://example.com/photo", null, DownloadTask.Type.IMAGE);
        assertEquals("photo.jpg", task.filename);
    }

    @Test
    public void deriveFilename_slashOnlyUrl() {
        DownloadTask task = new DownloadTask("https://example.com/", null, DownloadTask.Type.DIRECT);
        assertNotNull(task.filename);
        assertTrue(task.filename.endsWith(".mp4"));
    }
}
