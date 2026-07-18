package com.minibrowser;

import org.junit.Test;
import static org.junit.Assert.*;

public class BlocklistUpdaterTest {

    @Test
    public void parseHost_standardHostsFormat() {
        assertEquals("example.com", BlocklistUpdater.parseHost("0.0.0.0 example.com"));
        assertEquals("ads.tracker.com", BlocklistUpdater.parseHost("127.0.0.1 ads.tracker.com"));
    }

    @Test
    public void parseHost_bareDomain() {
        assertEquals("example.com", BlocklistUpdater.parseHost("example.com"));
    }

    @Test
    public void parseHost_comments() {
        assertNull(BlocklistUpdater.parseHost("# This is a comment"));
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 example.com # inline comment"));
    }

    @Test
    public void parseHost_emptyAndBlank() {
        assertNull(BlocklistUpdater.parseHost(null));
        assertNull(BlocklistUpdater.parseHost(""));
        assertNull(BlocklistUpdater.parseHost("   "));
        assertNull(BlocklistUpdater.parseHost("#"));
    }

    @Test
    public void parseHost_reservedNames() {
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 localhost"));
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 broadcasthost"));
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 ip6-localhost"));
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 ip6-loopback"));
    }

    @Test
    public void parseHost_rawIPv4() {
        assertNull(BlocklistUpdater.parseHost("192.168.1.1"));
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 192.168.1.1"));
    }

    @Test
    public void parseHost_ipv6() {
        assertNull(BlocklistUpdater.parseHost("::1 example.com"));
        assertNull(BlocklistUpdater.parseHost("fe80::1%lo0"));
    }

    @Test
    public void parseHost_noDot() {
        assertNull(BlocklistUpdater.parseHost("localhost"));
        assertNull(BlocklistUpdater.parseHost("a"));
    }

    @Test
    public void parseHost_tooShort() {
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 x")); // single char after stripping
    }

    @Test
    public void parseHost_tooLong() {
        String longDomain = "a".repeat(254);
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 " + longDomain));
    }

    @Test
    public void parseHost_invalidChars() {
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 ex@mple.com"));
        assertNull(BlocklistUpdater.parseHost("0.0.0.0 example!.com"));
    }

    @Test
    public void parseHost_subdomains() {
        assertEquals("sub.ads.example.com", BlocklistUpdater.parseHost("0.0.0.0 sub.ads.example.com"));
    }

    @Test
    public void parseHost_hyphensAndNumbers() {
        assertEquals("my-site-123.example.com",
                BlocklistUpdater.parseHost("0.0.0.0 my-site-123.example.com"));
    }

    @Test
    public void parseHost_caseInsensitive() {
        assertEquals("example.com", BlocklistUpdater.parseHost("0.0.0.0 EXAMPLE.COM"));
    }

    @Test
    public void parseHost_multipleWhitespace() {
        assertEquals("example.com", BlocklistUpdater.parseHost("0.0.0.0   example.com"));
    }

    // ---- setUrl validation (requires Android context, so tested separately) ----
    // Note: Full setUrl() testing requires a mock Context. These tests verify the
    // parseHost logic which is used by setUrl internally.

    @Test
    public void parseHost_acceptsHttpUrls() {
        // URLs starting with http:// or https:// should be accepted
        assertEquals("example.com", BlocklistUpdater.parseHost("https://example.com"));
        assertEquals("example.com", BlocklistUpdater.parseHost("http://example.com"));
    }

    @Test
    public void parseHost_rejectsNonHttpSchemes() {
        // URLs without http/https should be rejected in setUrl validation
        assertNull(BlocklistUpdater.parseHost("ftp://example.com"));
        assertNull(BlocklistUpdater.parseHost("file:///path"));
    }
}
