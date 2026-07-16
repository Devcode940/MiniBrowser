package com.minibrowser.download;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * MpdParser — a pragmatic MPEG-DASH (.mpd) manifest parser, ported from the
 * concepts used by super-video-downloader.
 *
 * Supports the three common segment addressing schemes:
 *   • SegmentTemplate  (with SegmentTimeline OR fixed duration)
 *   • SegmentList      (explicit SegmentURL list)
 *   • BaseURL only     (single progressive representation)
 *
 * For multi-bitrate manifests it selects the highest-bandwidth Representation
 * per AdaptationSet. Callers request segments by type ("video" / "audio").
 *
 * Zero third-party deps: platform org.xmlpull.v1.* only.
 */
final class MpdParser {

    private MpdParser() { }

    static final class Representation {
        String id;
        long bandwidth;
        String mimeType;
        String codecs;
        String baseUrl;          // BaseURL scoped to this representation (cumulative)
        String mediaTemplate;    // SegmentTemplate.media
        String initTemplate;     // SegmentTemplate.initialization
        long startNumber = 1;
        long timescale = 1;
        long duration = -1;      // SegmentTemplate duration (per segment)
        // Populated from SegmentTimeline:
        final List<Long> timelineDurations = new ArrayList<>();
        boolean useSegmentList = false;
        final List<String> segmentUrls = new ArrayList<>();
    }

    static final class AdaptationSet {
        String mimeType;
        String contentType;
        String lang;
        final List<Representation> reps = new ArrayList<>();
    }

    static final class Manifest {
        String mpdBaseUrl;
        final List<AdaptationSet> sets = new ArrayList<>();
        long minBufferTime;
    }

    /** Parse the manifest XML. {@code fetchUrl} is the URL it came from. */
    static Manifest parse(String xml, String fetchUrl) throws XmlPullParserException, java.io.IOException {
        Manifest m = new Manifest();
        m.mpdBaseUrl = fetchUrl;
        if (xml == null || xml.isEmpty()) return m;

        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        p.setInput(new StringReader(xml));

        // Stack of BaseURLs as we descend the tree (cumulative).
        List<String> baseUrlStack = new ArrayList<>();
        Manifest current = m;
        AdaptationSet currentSet = null;
        Representation currentRep = null;
        boolean inTimeline = false;

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();

                if ("BaseURL".equals(name)) {
                    String text = readText(p);
                    if (text != null && !text.isEmpty()) baseUrlStack.add(text);
                } else if ("AdaptationSet".equals(name)) {
                    currentSet = new AdaptationSet();
                    currentSet.mimeType = p.getAttributeValue(null, "mimeType");
                    currentSet.contentType = p.getAttributeValue(null, "contentType");
                    currentSet.lang = p.getAttributeValue(null, "lang");
                    current.sets.add(currentSet);
                } else if ("Representation".equals(name)) {
                    currentRep = new Representation();
                    currentRep.id = p.getAttributeValue(null, "id");
                    currentRep.mimeType = currentSet != null ? currentSet.mimeType : null;
                    currentRep.bandwidth = parseLong(p.getAttributeValue(null, "bandwidth"));
                    currentRep.codecs = p.getAttributeValue(null, "codecs");
                    currentRep.baseUrl = joinBase(baseUrlStack);
                    if (currentSet != null) currentSet.reps.add(currentRep);
                } else if ("SegmentTemplate".equals(name)) {
                    if (currentRep != null) {
                        currentRep.mediaTemplate = p.getAttributeValue(null, "media");
                        currentRep.initTemplate = p.getAttributeValue(null, "initialization");
                        currentRep.startNumber = parseLongDefault(p.getAttributeValue(null, "startNumber"), 1);
                        currentRep.timescale = parseLongDefault(p.getAttributeValue(null, "timescale"), 1);
                        currentRep.duration = parseLongDefault(p.getAttributeValue(null, "duration"), -1);
                    }
                } else if ("SegmentList".equals(name)) {
                    if (currentRep != null) currentRep.useSegmentList = true;
                } else if ("SegmentURL".equals(name)) {
                    if (currentRep != null) {
                        String href = p.getAttributeValue(null, "media");
                        if (href == null) href = p.getAttributeValue(null, "href");
                        if (href != null) currentRep.segmentUrls.add(href);
                    }
                } else if ("SegmentTimeline".equals(name)) {
                    inTimeline = true;
                } else if ("S".equals(name) && inTimeline && currentRep != null) {
                    long d = parseLongDefault(p.getAttributeValue(null, "d"), 0);
                    if (d > 0) currentRep.timelineDurations.add(d);
                } else if ("Initialization".equals(name) && currentRep != null) {
                    String src = p.getAttributeValue(null, "sourceURL");
                    if (src != null) currentRep.initTemplate = src;
                }

            } else if (event == XmlPullParser.END_TAG) {
                String name = p.getName();
                if ("AdaptationSet".equals(name)) {
                    currentSet = null;
                } else if ("Representation".equals(name)) {
                    currentRep = null;
                    popBase(baseUrlStack);
                } else if ("BaseURL".equals(name)) {
                    popBase(baseUrlStack);
                } else if ("SegmentTimeline".equals(name)) {
                    inTimeline = false;
                }
            }
            event = p.next();
        }
        return m;
    }

    /** Resolve the full segment list (init + media) for the best rep of a type. */
    static Resolved resolve(Manifest m, String typePref) {
        Resolved r = new Resolved();
        AdaptationSet set = pickSet(m, typePref);
        if (set == null) return r;
        Representation rep = pickBestRep(set);
        if (rep == null) return r;

        URI base = toUri(m.mpdBaseUrl);
        // Cumulative base: manifest URL + representation BaseURL(s).
        URI repBase = resolveChain(base, rep.baseUrl);

        if (rep.initTemplate != null) {
            r.initSegment = subst(repBase, rep.initTemplate, rep);
        }
        if (rep.useSegmentList && !rep.segmentUrls.isEmpty()) {
            for (String u : rep.segmentUrls) r.segments.add(resolve(repBase, u));
        } else if (rep.mediaTemplate != null) {
            // SegmentTimeline-driven numbers
            if (!rep.timelineDurations.isEmpty()) {
                long number = rep.startNumber;
                for (int i = 0; i < rep.timelineDurations.size(); i++) {
                    r.segments.add(substNumber(repBase, rep.mediaTemplate, rep, number));
                    number++;
                }
            } else if (rep.duration > 0 && rep.timescale > 0) {
                // Fixed-duration template: we don't know total duration from MPD
                // alone (no mediaPresentationDuration guarantee) — emit a bounded
                // heuristic set. Most live DASH uses timeline; VOD usually carries
                // mediaPresentationDuration which we approximate via buffer time.
                long count = Math.max(1, m.minBufferTime > 0
                        ? 200 /* fallback upper bound */ : 200);
                for (long n = rep.startNumber; n < rep.startNumber + count; n++) {
                    r.segments.add(substNumber(repBase, rep.mediaTemplate, rep, n));
                }
            }
        } else if (rep.baseUrl != null && !rep.baseUrl.isEmpty()) {
            // Progressive single-segment representation.
            r.segments.add(repBase.toString());
        }
        r.mimeType = rep.mimeType != null ? rep.mimeType : set.mimeType;
        return r;
    }

    static final class Resolved {
        String initSegment;
        final List<String> segments = new ArrayList<>();
        String mimeType;
    }

    // --------------------------- selection -------------------------------

    private static AdaptationSet pickSet(Manifest m, String typePref) {
        AdaptationSet fallback = null;
        for (AdaptationSet s : m.sets) {
            String ct = s.contentType != null ? s.contentType
                    : (s.mimeType != null ? s.mimeType.split("/")[0] : "");
            if (typePref != null && ct.toLowerCase().startsWith(typePref)) return s;
            if (fallback == null) fallback = s;
        }
        return fallback;
    }

    private static Representation pickBestRep(AdaptationSet set) {
        if (set.reps.isEmpty()) return null;
        Representation best = set.reps.get(0);
        for (Representation r : set.reps) {
            if (r.bandwidth > best.bandwidth) best = r;
        }
        return best;
    }

    // --------------------------- URL helpers -----------------------------

    private static String substNumber(URI base, String tmpl, Representation rep, long number) {
        String s = tmpl
                .replace("$RepresentationID$", rep.id != null ? rep.id : "0")
                .replace("$Number$", Long.toString(number))
                .replace("$Bandwidth$", Long.toString(rep.bandwidth));
        // Handle $$ escape if present.
        s = s.replace("$$", "$");
        return resolve(base, s);
    }

    private static String subst(URI base, String tmpl, Representation rep) {
        String s = tmpl
                .replace("$RepresentationID$", rep.id != null ? rep.id : "0")
                .replace("$Bandwidth$", Long.toString(rep.bandwidth));
        s = s.replace("$$", "$");
        return resolve(base, s);
    }

    private static String resolve(URI base, String ref) {
        if (ref == null) return null;
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        if (base == null) return ref;
        try {
            return base.resolve(ref).normalize().toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static URI resolveChain(URI base, String ref) {
        if (ref == null || ref.isEmpty()) return base;
        if (base == null) return toUri(ref);
        try {
            return base.resolve(ref).normalize();
        } catch (Exception e) {
            return base;
        }
    }

    private static URI toUri(String url) {
        if (url == null) return null;
        try {
            return URI.create(url.contains(" ") ? url.replace(" ", "%20") : url);
        } catch (Exception e) {
            return null;
        }
    }

    private static String joinBase(List<String> stack) {
        StringBuilder sb = new StringBuilder();
        for (String s : stack) sb.append(s);
        return sb.toString();
    }

    private static void popBase(List<String> stack) {
        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long parseLongDefault(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static String readText(XmlPullParser p) throws XmlPullParserException, java.io.IOException {
        String result = "";
        int depth = p.getDepth();
        int ev = p.next();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.IGNORABLE_WHITESPACE) {
                if (p.getText() != null) result += p.getText();
            } else if (ev == XmlPullParser.END_TAG && p.getDepth() == depth) {
                break;
            } else if (ev == XmlPullParser.START_TAG) {
                // nested element inside BaseURL — skip
            }
            ev = p.next();
        }
        return result.trim();
    }
}
