package com.minibrowser;

import org.junit.Test;
import static org.junit.Assert.*;
import com.minibrowser.media.AiClient;
import java.util.ArrayList;

public class AiTest {
    @Test
    public void testAiMessageHandlingAndHistory() {
        AiClient.Msg msgUser = new AiClient.Msg(true, "Hello AI");
        AiClient.Msg msgAssistant = new AiClient.Msg(false, "Hello User");
        
        assertTrue(msgUser.user);
        assertEquals("Hello AI", msgUser.text);
        assertFalse(msgAssistant.user);
        assertEquals("Hello User", msgAssistant.text);
        
        ArrayList<AiClient.Msg> history = new ArrayList<>();
        history.add(msgUser);
        history.add(msgAssistant);
        assertEquals(2, history.size());
    }
}
