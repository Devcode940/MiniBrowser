package com.minibrowser;

import org.junit.Test;
import static org.junit.Assert.*;
import com.minibrowser.tab.Tab;
import com.minibrowser.tab.TabManager;
import java.util.List;

public class TabTest {
    @Test
    public void testTabInitializationAndUserIsolation() {
        TabManager tm = TabManager.get();
        tm.setUserId("user_alice");
        
        Tab t1 = new Tab("tab1", null, null, "user_alice");
        Tab t2 = new Tab("tab2", null, null, "user_alice");
        Tab t3 = new Tab("tab3", null, null, "user_bob"); // Isolated tab
        
        tm.addTab(t1);
        tm.addTab(t2);
        tm.addTab(t3); // Rejected for Alice
        
        List<Tab> aliceTabs = tm.getUserTabs();
        assertEquals(2, aliceTabs.size());
        assertEquals("tab1", aliceTabs.get(0).id);
        assertEquals("tab2", aliceTabs.get(1).id);
        
        tm.closeTab("tab1");
        assertEquals(1, tm.getUserTabs().size());
    }
}
