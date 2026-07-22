package com.minibrowser.tab;

import java.util.ArrayList;
import java.util.List;

public class TabManager {
    private static volatile TabManager instance;
    private final List<Tab> tabs = new ArrayList<>();
    private String currentUserId = "default_user";

    private TabManager() {}

    public static TabManager get() {
        if (instance == null) {
            synchronized (TabManager.class) {
                if (instance == null) instance = new TabManager();
            }
        }
        return instance;
    }

    public void setUserId(String userId) {
        this.currentUserId = userId != null ? userId : "default_user";
    }

    public synchronized void addTab(Tab tab) {
        if (tab != null && currentUserId.equals(tab.userId)) {
            tabs.add(tab);
        }
    }

    public synchronized List<Tab> getUserTabs() {
        List<Tab> userTabs = new ArrayList<>();
        for (Tab t : tabs) {
            if (currentUserId.equals(t.userId)) {
                userTabs.add(t);
            }
        }
        return userTabs;
    }

    public synchronized void closeTab(String id) {
        Tab toRemove = null;
        for (Tab t : tabs) {
            if (t.id.equals(id) && currentUserId.equals(t.userId)) {
                toRemove = t;
                break;
            }
        }
        if (toRemove != null) {
            tabs.remove(toRemove);
            toRemove.core.destroy();
        }
    }
}
