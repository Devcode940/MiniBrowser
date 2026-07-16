package com.minibrowser.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * M3u8Parser — minimal but correct HLS (HTTP Live Streaming) playlist parser,
 * ported from the concepts used by super-video-downloader.
 *
 * Handles:
 *   • Master playlists (EXT-X-STREAM-INF variant selection)
 *   • Media playlists (segment list, EXTINF, #EXT-X-ENDLIST)
 *   • Relative + absolute segment URL resolution (URI.resolve against the base)
 *   • Encryption detection (EXT-X-KEY) — flagged so callers can warn the user
 *
 * It does NOT decrypt AES-128/SAMPLE-AES segments; encrypted streams are reported
 * via {@link Playlist#encrypted} so the manager can fail gracefully.
 *
 * Zero third-party deps: pure java.net.URI + String parsing.
 */
final class M3u8Parser {

    private M3u8Parser() { }

    static final class Variant {
        final String url;
        final long bandwidth;
        final String resolution;
        Variant(String url, long bandwidth, String resolution) {
            this.url = url; this.bandwidth = bandwidth; this.resolution = resolution;
        }
    }

    static final class Playlist {
        boolean isMaster = false;
        boolean encrypted = false;
        String keyMethod;
        String keyUrl;
        Integer targetDurationSec;
        boolean hasEndList = false;
        final List<Variant> variants = new ArrayList<>();
        /** Resolved absolute segment URLs, in play order. */
        final List<String> segments = new ArrayList<>();
        /** Optional init segment for fMP4 (#EXT-X-MAP). */
        String initSegment;
    }

    /**
     * Parse raw playlist text. {@code baseUrl} is the URL the content was fetched
     * from, used to resolve relative segment/variant references.
     */
    static Playlist parse(String content, String baseUrl) {
        Playlist pl = new Playlist();
        if (content == null || content.isEmpty()) return pl;
        // A valid playlist must begin with #EXTM3U.
        if (!content.contains("#EXTM3U")) return pl;

        String[] lines = content.split("\n");
        URI base = baseUrl != null ? toUri(baseUrl) : null;

        long pendingBandwidth = 0;
        String pendingResolution = null;
        boolean nextLineIsSegment = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#")) {
                String tag = line.toUpperCase();
                if (tag.startsWith("#EXT-X-STREAM-INF")) {
                    pl.isMaster = true;
                    pendingBandwidth = parseLongAttr(line, "BANDWIDTH=");
                    pendingResolution = parseAttr(line, "RESOLUTION=");
                    nextLineIsSegment = false;
                } else if (tag.startsWith("#EXTINF")) {
                    nextLineIsSegment = true;
                } else if (tag.startsWith("#EXT-X-KEY")) {
                    pl.encrypted = true;
                    pl.keyMethod = parseAttr(line, "METHOD=");
                    pl.keyUrl = resolve(base, parseQuotedAttr(line, "URI="));
                } else if (tag.startsWith("#EXT-X-MAP")) {
                    pl.initSegment = resolve(base, parseQuotedAttr(line, "URI="));
                } else if (tag.startsWith("#EXT-X-TARGETDURATION")) {
                    pl.targetDurationSec = (int) parseLongAttr(line, ":");
                } else if (tag.startsWith("#EXT-X-ENDLIST")) {
                    pl.hasEndList = true;
                }
                continue;
            }

            // Non-comment, non-empty line -> a URI reference.
            if (pl.isMaster) {
                pl.variants.add(new Variant(resolve(base, line), pendingBandwidth, pendingResolution));
                pendingBandwidth = 0;
                pendingResolution = null;
            } else if (nextLineIsSegment || true) {
                // Segment lines (after EXTINF) — also tolerate playlists without EXTINF.
                String resolved = resolve(base, line);
                if (resolved != null) {
                    pl.segments.add(resolved);
                    nextLineIsSegment = false;
                }
            }
        }
        return pl;
    }

    /** Pick the highest-bandwidth variant from a master playlist. */
    static Variant bestVariant(Playlist pl) {
        if (pl == null || pl.variants.isEmpty()) return null;
        Variant best = pl.variants.get(0);
        for (Variant v : pl.variants) {
            if (v.bandwidth > best.bandwidth) best = v;
        }
        return best;
    }

    // --------------------------- helpers ----------------------------------

    private static URI toUri(String url) {
        try {
            return URI.create(url.contains(" ") ? url.replace(" ", "%20") : url);
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolve(URI base, String ref) {
        if (ref == null || ref.isEmpty()) return null;
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        if (base == null) return ref;
        try {
            return base.resolve(ref).normalize().toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static long parseLongAttr(String line, String key) {
        String s = parseAttr(line, key);
        if (s == null) return 0;
        // Keep only the leading numeric portion.
        int n = 0;
        while (n < s.length() && (Character.isDigit(s.charAt(n)) || s.charAt(n) == '.')) n++;
        try {
            return (long) Double.parseDouble(s.substring(0, n));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String parseAttr(String line, String key) {
        int i = line.toUpperCase().indexOf(key.toUpperCase());
        if (i < 0) return null;
        int start = i + key.length();
        int comma = line.indexOf(',', start);
        int end = comma < 0 ? line.length() : comma;
        return line.substring(start, end).trim();
    }

    private static String parseQuotedAttr(String line, String key) {
        String v = parseAttr(line, key);
        if (v == null) return null;
        v = v.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }
}
