package com.minibrowser;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;

import com.minibrowser.media.AiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * MainViewModel — holds UI state that survives configuration changes.
 *
 * Separates transient UI state (URL, progress, fullscreen, AI panel) from
 * the Activity so it persists across rotation / process death. The Activity
 * observes or reads from this ViewModel; BrowserCore remains the WebView engine.
 *
 * AI chat history is kept here so the conversation survives rotation.
 */
public class MainViewModel extends ViewModel {

    // ---- Navigation state ----
    private String currentUrl = "";
    private String currentTitle = "";
    private int progress = 0;
    private boolean isHome = true;
    private boolean fullscreen = false;

    // ---- Bottom sheet ----
    private int sheetPage = 0;
    private boolean sheetVisible = false;

    // ---- AI panel ----
    private boolean aiPanelVisible = false;
    private final List<AiClient.Msg> aiHistory = new ArrayList<>();

    // ---- Listeners (Activity attaches/detaches) ----
    public interface StateListener {
        void onUrlChanged(String url);
        void onTitleChanged(String title);
        void onProgressChanged(int progress);
        void onHomeStateChanged(boolean isHome);
        void onFullscreenChanged(boolean fullscreen);
    }

    private StateListener listener;

    public void setListener(StateListener l) { this.listener = l; }
    public void clearListener() { this.listener = null; }

    // ---- URL / title / progress ----

    @NonNull
    public String getCurrentUrl() { return currentUrl; }

    public void setCurrentUrl(String url) {
        this.currentUrl = url != null ? url : "";
        if (listener != null) listener.onUrlChanged(this.currentUrl);
    }

    @NonNull
    public String getCurrentTitle() { return currentTitle; }

    public void setCurrentTitle(String title) {
        this.currentTitle = title != null ? title : "";
        if (listener != null) listener.onTitleChanged(this.currentTitle);
    }

    public int getProgress() { return progress; }

    public void setProgress(int p) {
        this.progress = p;
        if (listener != null) listener.onProgressChanged(p);
    }

    public boolean isHome() { return isHome; }

    public void setHome(boolean home) {
        this.isHome = home;
        if (listener != null) listener.onHomeStateChanged(home);
    }

    public boolean isFullscreen() { return fullscreen; }

    public void setFullscreen(boolean fs) {
        this.fullscreen = fs;
        if (listener != null) listener.onFullscreenChanged(fs);
    }

    // ---- Bottom sheet ----

    public int getSheetPage() { return sheetPage; }
    public void setSheetPage(int page) { this.sheetPage = page; }

    public boolean isSheetVisible() { return sheetVisible; }
    public void setSheetVisible(boolean visible) { this.sheetVisible = visible; }

    // ---- AI panel ----

    public boolean isAiPanelVisible() { return aiPanelVisible; }
    public void setAiPanelVisible(boolean visible) { this.aiPanelVisible = visible; }

    public List<AiClient.Msg> getAiHistory() { return aiHistory; }

    public void addAiMessage(boolean user, String text) {
        aiHistory.add(new AiClient.Msg(user, text));
    }

    public void clearAiHistory() { aiHistory.clear(); }
}
