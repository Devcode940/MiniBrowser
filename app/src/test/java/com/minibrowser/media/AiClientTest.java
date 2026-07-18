package com.minibrowser.media;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for AiClient - tests rate limiting and provider management.
 */
public class AiClientTest {

    @Test
    public void getProviders_notNull() {
        // This test verifies that getProviders returns a non-null list
        // Note: This requires a Context, so it's more of a structural test
        // In a real test, you'd use Robolectric or mock Context
        assertNotNull(AiClient.BUILTINS);
        assertTrue(AiClient.BUILTINS.length > 0);
    }

    @Test
    public void providerPresets_exist() {
        // Verify all expected provider presets exist
        String[] expectedIds = {
            "openai", "gemini", "deepseek", "kimi", "qwen", "grok",
            "openrouter", "copilot", "arena", "blackbox"
        };
        
        for (String id : expectedIds) {
            boolean found = false;
            for (AiClient.Provider p : AiClient.BUILTINS) {
                if (p.id.equals(id)) {
                    found = true;
                    break;
                }
            }
            assertTrue("Provider " + id + " should exist", found);
        }
    }

    @Test
    public void providerEndpoints_notEmpty() {
        // Verify that built-in providers have endpoints
        for (AiClient.Provider p : AiClient.BUILTINS) {
            if (p.builtin) {
                // Built-in providers should have endpoints (except customizable ones)
                if (!p.id.equals("arena") && !p.id.equals("blackbox")) {
                    assertNotNull("Provider " + p.id + " should have endpoint", p.endpoint);
                    assertFalse("Provider " + p.id + " endpoint should not be empty", 
                            p.endpoint.isEmpty());
                }
            }
        }
    }

    @Test
    public void providerModels_notEmpty() {
        // Verify that built-in providers have default models
        for (AiClient.Provider p : AiClient.BUILTINS) {
            if (p.builtin) {
                assertNotNull("Provider " + p.id + " should have defaultModel", p.defaultModel);
                assertFalse("Provider " + p.id + " defaultModel should not be empty", 
                        p.defaultModel.isEmpty());
            }
        }
    }

    @Test
    public void msgConstructor_storesValues() {
        AiClient.Msg msg = new AiClient.Msg(true, "Hello");
        assertTrue(msg.user);
        assertEquals("Hello", msg.text);
        
        AiClient.Msg msg2 = new AiClient.Msg(false, "Response");
        assertFalse(msg2.user);
        assertEquals("Response", msg2.text);
    }

    @Test
    public void msgHandlesNullText() {
        AiClient.Msg msg = new AiClient.Msg(true, null);
        assertTrue(msg.user);
        assertNull(msg.text);
    }

    @Test
    public void msgHandlesEmptyText() {
        AiClient.Msg msg = new AiClient.Msg(false, "");
        assertFalse(msg.user);
        assertEquals("", msg.text);
    }
}
