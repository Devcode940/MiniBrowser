package com.minibrowser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import com.minibrowser.media.AiClient;
import com.minibrowser.media.SnackPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements BrowserCore.Callback {

    public static final int REQ_FILE = 0xA001;
    public static final int REQ_GEO = 0xA002;
    public static final int REQ_WEB_PERMS = 0xA003;

    private MainViewModel vm;
    private BrowserCore core; // active tab core
    private GestureWebView webview; // active tab webview
    private FrameLayout webviewContainer;
    private Bookmarks bookmarks;
    private AiClient aiClient;
    private SnackPlayer snackPlayer;

    private FrameLayout root;
    private View toolbar;
    private EditText urlBar;
    private EditText homeSearch;
    private ImageButton btnBack, btnForward, btnReload, btnMenu, btnBookmark;
    private ProgressBar progress;
    private ScrollView homeOverlay;
    private GridLayout shortcutGrid;

    private View bottomSheet;
    private ViewGroup sheetPages;
    private View[] sheetPageViews;
    private int currentSheetPage = 0;
    private View[] sheetDots;
    private TextView sheetTitle;
    private final String[] SHEET_TITLES = {"Privacy", "Tools", "Settings"};

    private LinearLayout aiPanel;
    private Spinner aiSpinner;
    private ScrollView aiScroll;
    private LinearLayout aiMessages;
    private EditText aiInput;
    private boolean aiSpinnerReady = false;

    private boolean sheetVisible = false;
    private boolean isHome = true;
    private boolean fullscreen = false;
    private boolean aiPanelVisible = false;
    private String currentTitle = "";

    private final String[] ENGINE_NAMES = {"DuckDuckGo", "Google", "Brave Search", "Startpage", "Bing"};
    private final String[] ENGINE_TEMPLATES = {
            "https://duckduckgo.com/?q=%s",
            "https://www.google.com/search?q=%s",
            "https://search.brave.com/search?q=%s",
            "https://www.startpage.com/sp/search?query=%s",
            "https://www.bing.com/search?q=%s"
    };
    private final String[] LANG_NAMES = {
            "English", "Español", "Français", "Deutsch", "Italiano", "Português",
            "Русский", "中文", "日本語", "한국어", "العربية", "हिन्दी", "Kiswahili"
    };
    private final String[] LANG_CODES = {"en","es","fr","de","it","pt","ru","zh","ja","ko","ar","hi","sw"};

    // ============================ MULTI-TAB STRUCTURES ============================

    private static class Tab {
        final GestureWebView webView;
        final BrowserCore core;
        String title = "New Tab";
        String url = BrowserCore.HOME;
        boolean isHome = true;
        boolean isPrivacyShieldsEnabled = true;

        Tab(GestureWebView webView, BrowserCore core, boolean shields) {
            this.webView = webView;
            this.core = core;
            this.isPrivacyShieldsEnabled = shields;
            if (!shields) {
                core.setAdBlockEnabled(false);
                core.setCompatibilityMode(true);
                core.setCellGuard(false);
            }
        }
    }

    private final List<Tab> tabsList = new ArrayList<>();
    private int currentTabIdx = 0;

    private Tab createNewTabWithShields(String url, boolean shields) {
        GestureWebView newWebView = new GestureWebView(this);
        newWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        BrowserCore newCore = new BrowserCore(this, newWebView, this);
        Tab tab = new Tab(newWebView, newCore, shields);

        newWebView.setGestureListener(new GestureWebView.GestureListener() {
            @Override public void onEdgeBack()    { newCore.goBack(); }
            @Override public void onEdgeForward() { newCore.goForward(); }
        });
        newWebView.setScrollListener((dx, dy, atTop) -> {
            if (fullscreen) {
                if (dy > 8) hideChrome();
                else if (dy < -8 || atTop) showChrome();
            }
        });

        if (url != null && !url.equals(BrowserCore.HOME)) {
            tab.isHome = false;
            tab.url = url;
            newCore.loadUrl(url);
        } else {
            tab.isHome = true;
            tab.url = "";
        }

        return tab;
    }

    private Tab createNewTab(String url) {
        return createNewTabWithShields(url, true); // default is privacy shields enabled
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabsList.size()) return;

        // Reset split screen when manually switching tabs
        if (splitScreenActive) {
            toggleSplitScreen();
        }

        // Remove current webview from layout container
        webviewContainer.removeAllViews();

        currentTabIdx = index;
        Tab activeTab = tabsList.get(index);

        // Add the active tab's webview to container
        webviewContainer.addView(activeTab.webView);

        // Update active references
        webview = activeTab.webView;
        core = activeTab.core;
        isHome = activeTab.isHome;
        currentTitle = activeTab.title;

        // Sync with UI
        if (isHome) {
            homeOverlay.setVisibility(View.VISIBLE);
            urlBar.setText("");
            urlBar.setHint(R.string.search_hint);
        } else {
            homeOverlay.setVisibility(View.GONE);
            urlBar.setText(activeTab.url);
        }

        updateNavButtons();
    }

    public GestureWebView getWebview() {
        return webview;
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabsList.size()) return;
        Tab toClose = tabsList.remove(index);
        toClose.core.destroy(); // Safely destroy dynamic components of closed tab

        // Adjust active index safely
        if (currentTabIdx >= tabsList.size()) {
            currentTabIdx = tabsList.size() - 1;
        } else if (currentTabIdx == index) {
            if (currentTabIdx < 0) currentTabIdx = 0;
        } else if (currentTabIdx > index) {
            currentTabIdx--;
        }

        selectTab(currentTabIdx);
    }

    private void showTabsDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        container.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < tabsList.size(); i++) {
            final int idx = i;
            Tab t = tabsList.get(i);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding((int) dp(12), (int) dp(14), (int) dp(12), (int) dp(14));

            if (i == currentTabIdx) {
                item.setBackgroundColor(0x224F8CFF); // Highlight current active tab
            } else {
                item.setBackgroundResource(getSelectableBackground());
            }

            TextView tv = new TextView(this);
            tv.setText((i + 1) + ". " + (t.isHome ? "New Tab" : t.title) + " " + (t.isPrivacyShieldsEnabled ? "[Shields On]" : "[Normal]"));
            tv.setTextColor(getColor(R.color.text_primary));
            tv.setTextSize(15);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            item.addView(tv);

            if (tabsList.size() > 1) {
                TextView close = new TextView(this);
                close.setText("✕");
                close.setTextColor(getColor(R.color.danger));
                close.setTextSize(16);
                close.setPadding((int) dp(12), (int) dp(4), (int) dp(12), (int) dp(4));
                close.setClickable(true);
                close.setOnClickListener(v -> {
                    closeTab(idx);
                    alertDialogTabs.dismiss();
                    showTabsDialog();
                });
                item.addView(close);
            }

            item.setClickable(true);
            item.setOnClickListener(v -> {
                selectTab(idx);
                alertDialogTabs.dismiss();
            });

            list.addView(item);

            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(getColor(R.color.divider));
            list.addView(div);
        }

        sv.addView(list);
        container.addView(sv);

        // Add standard private shields tab
        TextView addTabBtn = new TextView(this);
        addTabBtn.setText("+ New Privacy Tab");
        addTabBtn.setTextColor(getColor(R.color.accent));
        addTabBtn.setGravity(Gravity.CENTER);
        addTabBtn.setPadding(0, (int) dp(14), 0, (int) dp(14));
        addTabBtn.setTextSize(15);
        addTabBtn.setClickable(true);
        addTabBtn.setBackgroundResource(getSelectableBackground());
        addTabBtn.setOnClickListener(v -> {
            Tab newTab = createNewTabWithShields(BrowserCore.HOME, true);
            tabsList.add(newTab);
            selectTab(tabsList.size() - 1);
            alertDialogTabs.dismiss();
        });
        container.addView(addTabBtn);

        // Add normal unshielded tab
        TextView addNormalTabBtn = new TextView(this);
        addNormalTabBtn.setText("+ New Normal Tab (Unshielded)");
        addNormalTabBtn.setTextColor(getColor(R.color.c_offline));
        addNormalTabBtn.setGravity(Gravity.CENTER);
        addNormalTabBtn.setPadding(0, (int) dp(14), 0, (int) dp(14));
        addNormalTabBtn.setTextSize(15);
        addNormalTabBtn.setClickable(true);
        addNormalTabBtn.setBackgroundResource(getSelectableBackground());
        addNormalTabBtn.setOnClickListener(v -> {
            Tab newTab = createNewTabWithShields(BrowserCore.HOME, false);
            tabsList.add(newTab);
            selectTab(tabsList.size() - 1);
            alertDialogTabs.dismiss();
        });
        container.addView(addNormalTabBtn);

        alertDialogTabs = new AlertDialog.Builder(this)
                .setTitle("Tabs Manager")
                .setView(container)
                .setNegativeButton("Close", null)
                .create();
        alertDialogTabs.show();
    }

    private AlertDialog alertDialogTabs;

    private Tab findTabForCore(BrowserCore sender) {
        for (Tab t : tabsList) {
            if (t.core == sender) return t;
        }
        if (splitTabNormal != null && splitTabNormal.core == sender) return splitTabNormal;
        return null;
    }

    // ============================ DUAL SPLIT-SCREEN & MINIMIZING ============================

    private boolean splitScreenActive = false;
    private LinearLayout splitContainerLayout;
    private FrameLayout paneNormal;
    private FrameLayout panePrivate;
    private TextView minBarNormal;
    private TextView minBarPrivate;
    
    private Tab splitTabNormal; // Bottom Panel (Normal Surf)
    private Tab splitTabPrivate; // Top Panel (Privacy Surf)
    
    private boolean normalPaneMinimized = false;
    private boolean privatePaneMinimized = false;

    private void toggleSplitScreen() {
        if (splitScreenActive) {
            // Disable split screen
            splitScreenActive = false;
            webviewContainer.removeAllViews();
            
            // Clean up Normal Tab
            if (splitTabNormal != null) {
                splitTabNormal.core.destroy();
                splitTabNormal = null;
            }
            splitTabPrivate = null;
            
            // Restore normal active tab
            selectTab(currentTabIdx);
            toast("Split screen mode closed");
        } else {
            // Enable split screen
            splitScreenActive = true;
            webviewContainer.removeAllViews();
            
            // Pane 1: Top (Current active tab as Privacy Shielded Side!)
            splitTabPrivate = tabsList.get(currentTabIdx);
            
            // Pane 2: Bottom (Create fresh Normal Web Surf tab!)
            splitTabNormal = createNewTabWithShields(BrowserCore.HOME, false);
            
            // Stacks top/bottom on mobile layout
            splitContainerLayout = new LinearLayout(this);
            splitContainerLayout.setOrientation(LinearLayout.VERTICAL);
            splitContainerLayout.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            
            panePrivate = new FrameLayout(this);
            LinearLayout.LayoutParams lpPrivate = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            panePrivate.setLayoutParams(lpPrivate);
            
            paneNormal = new FrameLayout(this);
            LinearLayout.LayoutParams lpNormal = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            paneNormal.setLayoutParams(lpNormal);
            
            // Create minimized bars
            minBarPrivate = new TextView(this);
            minBarPrivate.setText("▲ Privacy Shield Tab [Minimized - Tap to Restore]");
            minBarPrivate.setBackgroundColor(0xFF14171F);
            minBarPrivate.setTextColor(getColor(R.color.accent));
            minBarPrivate.setGravity(Gravity.CENTER);
            minBarPrivate.setPadding(0, (int) dp(14), 0, (int) dp(14));
            minBarPrivate.setVisibility(View.GONE);
            minBarPrivate.setClickable(true);
            
            minBarNormal = new TextView(this);
            minBarNormal.setText("▼ Normal Web Surf Tab [Minimized - Tap to Restore]");
            minBarNormal.setBackgroundColor(0xFF14171F);
            minBarNormal.setTextColor(getColor(R.color.c_offline));
            minBarNormal.setGravity(Gravity.CENTER);
            minBarNormal.setPadding(0, (int) dp(14), 0, (int) dp(14));
            minBarNormal.setVisibility(View.GONE);
            minBarNormal.setClickable(true);
            
            minBarPrivate.setOnClickListener(v -> restorePrivatePane());
            minBarNormal.setOnClickListener(v -> restoreNormalPane());
            
            // Control and minimizing bar
            LinearLayout controlBar = new LinearLayout(this);
            controlBar.setOrientation(LinearLayout.HORIZONTAL);
            controlBar.setBackgroundColor(getColor(R.color.divider));
            controlBar.setPadding((int) dp(12), (int) dp(6), (int) dp(12), (int) dp(6));
            controlBar.setGravity(Gravity.CENTER_VERTICAL);
            
            TextView label = new TextView(this);
            label.setText("Split Panel Controls:");
            label.setTextColor(getColor(R.color.text_secondary));
            label.setTextSize(12);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            
            TextView btnMinPrivate = new TextView(this);
            btnMinPrivate.setText("Minimize Top");
            btnMinPrivate.setTextColor(getColor(R.color.accent));
            btnMinPrivate.setPadding((int) dp(10), (int) dp(6), (int) dp(10), (int) dp(6));
            btnMinPrivate.setTextSize(12);
            btnMinPrivate.setClickable(true);
            btnMinPrivate.setBackgroundResource(getSelectableBackground());
            btnMinPrivate.setOnClickListener(v -> minimizePrivatePane());
            
            TextView btnMinNormal = new TextView(this);
            btnMinNormal.setText("Minimize Bottom");
            btnMinNormal.setTextColor(getColor(R.color.c_offline));
            btnMinNormal.setPadding((int) dp(10), (int) dp(6), (int) dp(10), (int) dp(6));
            btnMinNormal.setTextSize(12);
            btnMinNormal.setClickable(true);
            btnMinNormal.setBackgroundResource(getSelectableBackground());
            btnMinNormal.setOnClickListener(v -> minimizeNormalPane());
            
            controlBar.addView(label);
            controlBar.addView(btnMinPrivate);
            controlBar.addView(btnMinNormal);
            
            if (splitTabPrivate.webView.getParent() != null) {
                ((ViewGroup) splitTabPrivate.webView.getParent()).removeView(splitTabPrivate.webView);
            }
            panePrivate.addView(splitTabPrivate.webView);
            paneNormal.addView(splitTabNormal.webView);
            
            splitContainerLayout.addView(minBarPrivate);
            splitContainerLayout.addView(panePrivate);
            splitContainerLayout.addView(controlBar);
            splitContainerLayout.addView(paneNormal);
            splitContainerLayout.addView(minBarNormal);
            
            webviewContainer.addView(splitContainerLayout);
            
            normalPaneMinimized = false;
            privatePaneMinimized = false;
            
            // Focus on top private pane url first
            webview = splitTabPrivate.webView;
            core = splitTabPrivate.core;
            
            splitTabNormal.core.loadUrl("https://duckduckgo.com"); // load DDG default in normal surf bottom panel
            
            toast("Split screen loaded! Top: Privacy | Bottom: Normal Surf");
        }
    }

    private void minimizePrivatePane() {
        if (privatePaneMinimized || normalPaneMinimized) return;
        privatePaneMinimized = true;
        panePrivate.setVisibility(View.GONE);
        minBarPrivate.setVisibility(View.VISIBLE);
        
        // Give Normal Pane remaining space
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) paneNormal.getLayoutParams();
        lp.weight = 1f;
        paneNormal.setLayoutParams(lp);
        
        // Switch control focus to Normal Pane
        webview = splitTabNormal.webView;
        core = splitTabNormal.core;
        urlBar.setText(splitTabNormal.url);
        updateNavButtons();
        
        toast("Privacy tab minimized!");
    }

    private void restorePrivatePane() {
        if (!privatePaneMinimized) return;
        privatePaneMinimized = false;
        panePrivate.setVisibility(View.VISIBLE);
        minBarPrivate.setVisibility(View.GONE);
        
        // Balanced 50/50 space
        LinearLayout.LayoutParams lpNormal = (LinearLayout.LayoutParams) paneNormal.getLayoutParams();
        lpNormal.weight = 1f;
        paneNormal.setLayoutParams(lpNormal);
        
        LinearLayout.LayoutParams lpPrivate = (LinearLayout.LayoutParams) panePrivate.getLayoutParams();
        lpPrivate.weight = 1f;
        panePrivate.setLayoutParams(lpPrivate);
        
        // Switch control focus back to default active Private Pane
        webview = splitTabPrivate.webView;
        core = splitTabPrivate.core;
        urlBar.setText(splitTabPrivate.url);
        updateNavButtons();
        
        toast("Privacy tab restored!");
    }

    private void minimizeNormalPane() {
        if (normalPaneMinimized || privatePaneMinimized) return;
        normalPaneMinimized = true;
        paneNormal.setVisibility(View.GONE);
        minBarNormal.setVisibility(View.VISIBLE);
        
        // Give Private Pane remaining space
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panePrivate.getLayoutParams();
        lp.weight = 1f;
        panePrivate.setLayoutParams(lp);
        
        // Switch control focus to Private Pane
        webview = splitTabPrivate.webView;
        core = splitTabPrivate.core;
        urlBar.setText(splitTabPrivate.url);
        updateNavButtons();
        
        toast("Normal tab minimized!");
    }

    private void restoreNormalPane() {
        if (!normalPaneMinimized) return;
        normalPaneMinimized = false;
        paneNormal.setVisibility(View.VISIBLE);
        minBarNormal.setVisibility(View.GONE);
        
        // Balanced 50/50 space
        LinearLayout.LayoutParams lpNormal = (LinearLayout.LayoutParams) paneNormal.getLayoutParams();
        lpNormal.weight = 1f;
        paneNormal.setLayoutParams(lpNormal);
        
        LinearLayout.LayoutParams lpPrivate = (LinearLayout.LayoutParams) panePrivate.getLayoutParams();
        lpPrivate.weight = 1f;
        panePrivate.setLayoutParams(lpPrivate);
        
        // Switch control focus to Private Pane
        webview = splitTabPrivate.webView;
        core = splitTabPrivate.core;
        urlBar.setText(splitTabPrivate.url);
        updateNavButtons();
        
        toast("Normal tab restored!");
    }

    // ========================================== LIFECYCLE ==========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vm = new ViewModelProvider(this).get(MainViewModel.class);

        root = findViewById(R.id.root);
        webviewContainer = findViewById(R.id.webview_container);
        toolbar = findViewById(R.id.toolbar);
        bindViews();

        bookmarks = new Bookmarks(this);
        aiClient = new AiClient(this);

        setupToolbar();
        setupHome();
        setupBottomSheet();
        buildAiPanel();

        // Start with a fresh tab
        Tab t = createNewTab(BrowserCore.HOME);
        tabsList.add(t);
        selectTab(0);

        if (savedInstanceState != null) {
            webview.restoreState(savedInstanceState);
        } else {
            goHome();
        }

        syncViewModelToUi();
    }

    private void bindViews() {
        urlBar = findViewById(R.id.url_bar);
        homeSearch = findViewById(R.id.home_search);
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);
        btnReload = findViewById(R.id.btn_reload);
        btnMenu = findViewById(R.id.btn_menu);
        btnBookmark = findViewById(R.id.btn_bookmark);
        progress = findViewById(R.id.progress);
        homeOverlay = findViewById(R.id.home_overlay);
        shortcutGrid = findViewById(R.id.shortcut_grid);
        bottomSheet = findViewById(R.id.bottom_sheet);
        sheetPages = findViewById(R.id.sheet_pages);
        sheetTitle = findViewById(R.id.sheet_title);
    }

    private void syncViewModelToUi() {
        fullscreen = vm.isFullscreen();
        isHome = vm.isHome();
        sheetVisible = vm.isSheetVisible();
        currentSheetPage = vm.getSheetPage();
        if (fullscreen) {
            hideChrome();
        }
        if (sheetVisible && sheetPageViews != null) {
            for (int i = 0; i < sheetPageViews.length; i++) {
                sheetPageViews[i].setVisibility(i == currentSheetPage ? View.VISIBLE : View.GONE);
            }
            sheetTitle.setText(SHEET_TITLES[currentSheetPage]);
            updateDots(currentSheetPage);
            applySheetHeightCap();
        }
    }

    @Override protected void onResume() { super.onResume(); core.startNetworkMonitor(); }
    @Override protected void onPause() { super.onPause(); core.stopNetworkMonitor(); }
    @Override protected void onDestroy() {
        if (snackPlayer != null) snackPlayer.close();
        
        boolean clearOnExit = getPref().getBoolean("clear_on_exit", false);
        for (Tab t : tabsList) {
            if (clearOnExit) {
                t.webView.clearCache(true);
                t.webView.clearHistory();
            }
            t.core.destroy();
        }
        tabsList.clear();
        
        if (splitTabNormal != null) {
            splitTabNormal.core.destroy();
            splitTabNormal = null;
        }

        if (clearOnExit) {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
            try {
                android.webkit.WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (fullscreen) { setFullscreen(false); return; }
        if (aiPanel.getVisibility() == View.VISIBLE) { hideAiPanel(); return; }
        if (sheetVisible) { hideSheet(); return; }
        if (!isHome && core.canGoBack()) core.goBack();
        else if (!isHome) goHome();
        else super.onBackPressed();
    }

    void navBack() { if (core.canGoBack()) core.goBack(); else goHome(); }
    void navForward() { core.goForward(); }
    void navReload() { core.reload(); }
    void navStop() { core.stopLoading(); }
    void navCopyUrl() { copyCurrentUrl(); }

    // --------------------------- TOOLBAR ---------------------------

    private void setupToolbar() {
        btnBack.setOnClickListener(v -> { if (core.canGoBack()) core.goBack(); else goHome(); });
        btnForward.setOnClickListener(v -> core.goForward());
        btnReload.setOnClickListener(v -> core.reload());
        btnReload.setOnLongClickListener(v -> { goHome(); return true; });
        btnMenu.setOnClickListener(v -> toggleSheet());
        btnBookmark.setOnClickListener(v -> toggleBookmark());
        btnBookmark.setOnLongClickListener(v -> { showBookmarks(); return true; });
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                String text = urlBar.getText().toString().trim();
                if (!text.isEmpty()) { hideKeyboard(v); core.loadOrSearch(text); }
                return true;
            }
            return false;
        });
        urlBar.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) urlBar.selectAll(); });
    }

    // --------------------------- HOMEPAGE ---------------------------

    private void setupHome() {
        homeSearch.setOnEditorActionListener((v, actionId, event) -> {
            String text = homeSearch.getText().toString().trim();
            if (!text.isEmpty() && (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO)) {
                homeSearch.setText(""); hideKeyboard(v); core.loadOrSearch(text);
            }
            return true;
        });
        buildShortcuts();
    }

    private static final class Shortcut {
        final String label, letter, url, circle;
        final boolean isNotepad, isAbout;
        Shortcut(String label, String letter, String url, String circle, boolean isNotepad, boolean isAbout) {
            this.label = label; this.letter = letter; this.url = url; this.circle = circle; this.isNotepad = isNotepad; this.isAbout = isAbout;
        }
    }

    private void buildShortcuts() {
        Shortcut[] items = {
                new Shortcut("Agent", "A", "https://chatgpt.com", "circle_agent", false, false),
                new Shortcut("Qwen", "Q", "https://chat.qwenlm.ai", "circle_qwen", false, false),
                new Shortcut("GitHub", "G", "https://github.com", "circle_github", false, false),
                new Shortcut("DDG", "D", "https://duckduckgo.com", "circle_search", false, false),
                new Shortcut("Wiki", "W", "https://www.wikipedia.org", "circle_wiki", false, false),
                new Shortcut("Reddit", "R", "https://www.reddit.com", "circle_offline", false, false),
                new Shortcut("Notepad", "N", null, "circle_notepad", true, false),
                new Shortcut("About", "i", null, "circle_about", false, true),
        };
        for (Shortcut s : items) {
            int resId = getResources().getIdentifier(s.circle, "drawable", getPackageName());
            Drawable circle = resId != 0 ? getResources().getDrawable(resId) : null;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = 0;
            glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            glp.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = (int) dp(8);
            glp.setMargins(pad, pad, pad, pad);
            item.setLayoutParams(glp);

            TextView icon = new TextView(this);
            int size = (int) dp(56);
            icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            icon.setBackground(circle);
            icon.setGravity(Gravity.CENTER);
            icon.setTextColor(0xFFFFFFFF);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            icon.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.BOLD));
            icon.setText(s.letter);

            TextView label = new TextView(this);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = (int) dp(8);
            label.setLayoutParams(labelLp);
            label.setTextColor(getColor(R.color.text_secondary));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setSingleLine(true);
            label.setText(s.label);

            item.addView(icon);
            item.addView(label);
            final Shortcut sc = s;
            item.setOnClickListener(v -> {
                if (sc.isNotepad) startActivity(new Intent(this, NotepadActivity.class));
                else if (sc.isAbout) showAbout();
                else core.loadUrl(sc.url);
            });
            shortcutGrid.addView(item);
        }
    }

    private void goHome() {
        Tab activeTab = tabsList.get(currentTabIdx);
        activeTab.isHome = true;
        isHome = true;
        vm.setHome(true);
        core.loadUrl(BrowserCore.HOME);
        homeOverlay.setVisibility(View.VISIBLE);
        urlBar.setText("");
        urlBar.setHint(R.string.search_hint);
        updateNavButtons();
    }

    // --------------------------- BOTTOM SHEET ---------------------------

    private void setupBottomSheet() {
        findViewById(R.id.sheet_close).setOnClickListener(v -> hideSheet());
        findViewById(R.id.sheet_prev).setOnClickListener(v -> flipSheet(-1));
        findViewById(R.id.sheet_next).setOnClickListener(v -> flipSheet(1));
        ViewGroup dots = findViewById(R.id.sheet_dots);
        sheetDots = new View[dots.getChildCount()];
        for (int i = 0; i < dots.getChildCount(); i++) sheetDots[i] = dots.getChildAt(i);
        sheetPageViews = new View[]{
                wrapScroll(buildPrivacyPage()),
                wrapScroll(buildToolsPage()),
                wrapScroll(buildSettingsPage()),
        };
        sheetPages.addView(sheetPageViews[0]);
        for (int i = 1; i < sheetPageViews.length; i++) {
            sheetPageViews[i].setVisibility(View.GONE);
            sheetPages.addView(sheetPageViews[i]);
        }
        currentSheetPage = 0;
        sheetTitle.setText(SHEET_TITLES[0]);
        updateDots(0);
        bottomSheet.setVisibility(View.GONE);
    }

    private void toggleSheet() { if (sheetVisible) hideSheet(); else showSheet(); }

    private void showSheet() {
        sheetVisible = true;
        vm.setSheetVisible(true);
        bottomSheet.setVisibility(View.VISIBLE);
        bottomSheet.post(() -> {
            bottomSheet.setTranslationY(bottomSheet.getHeight());
            bottomSheet.animate().translationY(0f).setDuration(220).start();
            applySheetHeightCap();
        });
    }

    private void hideSheet() {
        if (!sheetVisible) return;
        sheetVisible = false;
        vm.setSheetVisible(false);
        bottomSheet.post(() -> {
            if (bottomSheet.getHeight() == 0) { bottomSheet.setVisibility(View.GONE); return; }
            bottomSheet.animate().translationY(bottomSheet.getHeight()).setDuration(200)
                    .withEndAction(() -> bottomSheet.setVisibility(View.GONE)).start();
        });
    }

    private void flipSheet(int dir) {
        int n = sheetPageViews.length;
        int next = (currentSheetPage + dir + n) % n;
        sheetPageViews[currentSheetPage].setVisibility(View.GONE);
        sheetPageViews[next].setVisibility(View.VISIBLE);
        currentSheetPage = next;
        vm.setSheetPage(next);
        sheetTitle.setText(SHEET_TITLES[next]);
        updateDots(next);
        applySheetHeightCap();
    }

    private void updateDots(int active) {
        for (int i = 0; i < sheetDots.length; i++)
            sheetDots[i].setBackgroundResource(i == active ? R.drawable.dot_active : R.drawable.dot_inactive);
    }

    private View buildPrivacyPage() {
        LinearLayout page = newSheetPage();
        boolean compat = getPref().getBoolean("compat_mode", false);
        boolean guard = getPref().getBoolean("cell_guard", true);
        boolean safe = getPref().getBoolean("safe_browsing", true);
        boolean adblock = getPref().getBoolean("adblock_enabled", true);
        boolean clearOnExit = getPref().getBoolean("clear_on_exit", false);

        page.addView(makeToggle("Ad & Tracker Blocker", adblock, b -> {
            for (Tab t : tabsList) {
                t.core.setAdBlockEnabled(b);
            }
            toast(b ? "Adblock shields on" : "Adblock shields off");
        }));
        page.addView(makeDivider());

        page.addView(makeToggle("Compatibility Mode", compat, b -> { core.setCompatibilityMode(b); toast(b ? "Compatibility mode on" : "Strict mode on"); }));
        page.addView(makeDivider());
        page.addView(makeToggle("Cellular Guard", guard, b -> { core.setCellGuard(b); toast(b ? "Cellular guard on" : "Cellular guard off"); }));
        page.addView(makeDivider());
        page.addView(makeToggle("Safe Browsing", safe, b -> { core.setSafeBrowsing(b); toast(b ? "Safe browsing on" : "Safe browsing off"); }));
        page.addView(makeDivider());

        page.addView(makeToggle("Clear Data on Exit", clearOnExit, b -> {
            getPref().edit().putBoolean("clear_on_exit", b).apply();
            toast(b ? "Auto-clear active" : "Auto-clear disabled");
        }));
        page.addView(makeDivider());

        page.addView(makeAction("Clear browsing data", R.color.danger, v -> confirmClearData()));
        return page;
    }

    /** Tools page is driven by the (customizable) ToolRegistry. */
    private View buildToolsPage() {
        LinearLayout page = newSheetPage();
        boolean first = true;
        for (ToolRegistry.Tool t : ToolRegistry.load(this)) {
            if (!t.visible) continue;
            if (!first) page.addView(makeDivider());
            first = false;
            View row = buildToolRow(t);
            if (row != null) page.addView(row);
        }
        if (first) page.addView(makeStatus("No tools. Add some in Settings → Customize tools."));
        return page;
    }

    private View buildToolRow(ToolRegistry.Tool t) {
        switch (t.id) {
            case "split":
                return makeAction("Toggle Split Screen", R.color.accent, v -> { hideSheet(); toggleSplitScreen(); });
            case "tabs":
                return makeAction("Manage Tabs", R.color.accent, v -> { hideSheet(); showTabsDialog(); });
            case "block":
                return makeToggle("Click-to-Block", core.isBlockMode(), b -> {
                    core.setBlockMode(b);
                    toast(b ? "Tap an element to block it" : "Picker off");
                    if (b) hideSheet();
                });
            case "translate":
                return makeAction("Translate page", R.color.accent, v -> { hideSheet(); core.translateCurrent(); });
            case "download":
                return makeAction("Download media", R.color.accent, v -> { hideSheet(); downloadMedia(); });
            case "play":
                return makeAction("Play media", R.color.accent, v -> { hideSheet(); playMedia(); });
            case "snack":
                return makeAction("Play in snack", R.color.accent, v -> { hideSheet(); playSnack(); });
            case "downloads":
                return makeAction("Open Downloads", R.color.c_offline, v -> { hideSheet(); startActivity(new Intent(this, com.minibrowser.download.DownloadActivity.class)); });
            case "ai":
                return makeAction("AI Chat", R.color.c_agent, v -> { hideSheet(); showAiPanel(); });
            case "fullscreen":
                return makeAction(fullscreen ? "Exit Fullscreen" : "Fullscreen", R.color.accent, v -> { hideSheet(); setFullscreen(!fullscreen); });
            case "offlinesave":
                return makeAction("Save page offline", R.color.accent, v -> { core.saveOffline(); hideSheet(); });
            case "notepad":
                return makeAction("Open Notepad", R.color.c_notepad, v -> startActivity(new Intent(this, NotepadActivity.class)));
            case "css":
                return makeAction("Edit custom.css", R.color.accent, v -> openAssetEditor("custom.css", core.getCustomCss(), c -> core.writeCustomCss(c)));
            case "js":
                return makeAction("Edit userscript.js", R.color.accent, v -> openAssetEditor("userscript.js", core.getUserscript(), c -> core.writeUserscript(c)));
            case "offlinepages":
                return makeAction("Offline pages", R.color.c_offline, v -> showOfflineList());
            default:
                // custom user tool: open URL
                if (t.url != null) {
                    return makeAction(t.label, R.color.c_qwen, v -> { hideSheet(); core.loadUrl(t.url); });
                }
                return null;
        }
    }

    /** Recreate the Tools page (after customize changes) preserving visibility. */
    private void rebuildToolsPage() {
        if (sheetPageViews == null) return;
        ScrollView sv = wrapScroll(buildToolsPage());
        boolean wasVisible = sheetPageViews[1].getVisibility() == View.VISIBLE;
        sheetPages.removeView(sheetPageViews[1]);
        sheetPages.addView(sv, 1);
        sheetPageViews[1] = sv;
        sv.setVisibility(wasVisible ? View.VISIBLE : View.GONE);
        applySheetHeightCap();
    }

    private View buildSettingsPage() {
        LinearLayout page = newSheetPage();
        page.addView(makeStatus(core.blocklistSummary()));
        page.addView(makeDivider());
        page.addView(makeToggle("Desktop mode", core.isDesktopMode(), b -> { core.setDesktopMode(b); toast(b ? "Desktop mode" : "Mobile mode"); }));
        page.addView(makeDivider());
        page.addView(makeToggle("Heavy site mode", core.isHeavyMode(), b -> { core.setHeavyMode(b); toast(b ? "Heavy mode on" : "Heavy mode off"); }));
        page.addView(makeDivider());
        page.addView(makeToggle("SurfingKeys (vim nav)", core.isSurfingKeys(), b -> { core.setSurfingKeys(b); toast(b ? "SurfingKeys on — press f" : "SurfingKeys off"); }));
        page.addView(makeDivider());
        page.addView(makeToggle("Auto-translate", core.isAutoTranslate(), b -> {
            core.setAutoTranslate(b);
            toast(b ? "Auto-translate on (" + core.getTranslateTarget() + ")" : "Auto-translate off");
        }));
        page.addView(makeDivider());
        page.addView(makeAction("Translation language (" + core.getTranslateTarget() + ")", R.color.accent, v -> showTranslateLangPicker()));
        page.addView(makeDivider());
        page.addView(makeAction("Customize tools", R.color.accent, v -> showCustomizeToolsDialog()));
        page.addView(makeDivider());
        page.addView(makeToggle("Remote blocklist", core.isRemoteBlocklistEnabled(), b -> { core.setRemoteBlocklistEnabled(b); toast(b ? "Remote list on" : "Remote list off"); }));
        page.addView(makeDivider());
        page.addView(makeAction("Refresh blocklist", R.color.accent, v -> core.refreshBlocklist()));
        page.addView(makeDivider());
        page.addView(makeAction("AI providers", R.color.c_agent, v -> showAiSettings()));
        page.addView(makeDivider());
        page.addView(makeAction("Bookmarks", R.color.c_offline, v -> showBookmarks()));
        page.addView(makeDivider());
        page.addView(makeAction("Cloud Sync (Backup/Restore)", R.color.accent, v -> showCloudSyncDialog()));
        page.addView(makeDivider());
        page.addView(makeAction("Proxy Routing (Tor/Custom)", R.color.accent, v -> showProxySettingsDialog()));
        page.addView(makeDivider());
        page.addView(makeAction("Saved Passwords (Enclave)", R.color.accent, v -> showPasswordManagerDialog()));
        page.addView(makeDivider());
        page.addView(makeAction("Search engine", R.color.accent, v -> chooseSearchEngine()));
        page.addView(makeDivider());
        page.addView(makeAction("Share current page", R.color.accent, v -> shareCurrent()));
        page.addView(makeDivider());
        page.addView(makeAction("Copy URL", R.color.accent, v -> copyCurrentUrl()));
        page.addView(makeDivider());
        page.addView(makeAction("Home", R.color.text_secondary, v -> { hideSheet(); goHome(); }));
        page.addView(makeDivider());
        page.addView(makeAction("About MiniBrowser", R.color.c_about, v -> showAbout()));
        return page;
    }

    // --------------------------- SHEET HELPERS ---------------------------

    private LinearLayout newSheetPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    private interface ToggleListener { void onChange(boolean on); }

    private View makeToggle(String label, boolean initial, ToggleListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) dp(4), (int) dp(10), (int) dp(4), (int) dp(10));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, checked) -> l.onChange(checked));
        row.addView(tv);
        row.addView(sw);
        return row;
    }

    private View makeAction(String label, int colorRes, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getColor(colorRes));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setPadding((int) dp(6), (int) dp(14), (int) dp(6), (int) dp(14));
        tv.setClickable(true);
        tv.setBackgroundResource(getSelectableBackground());
        tv.setOnClickListener(l);
        return tv;
    }

    private View makeStatus(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding((int) dp(6), (int) dp(10), (int) dp(6), (int) dp(10));
        return tv;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(getColor(R.color.divider));
        return v;
    }

    private int getSelectableBackground() {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private ScrollView wrapScroll(View child) {
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(child);
        return sv;
    }

    private void applySheetHeightCap() {
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.66);
        bottomSheet.post(() -> {
            ViewGroup.LayoutParams lp = bottomSheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bottomSheet.setLayoutParams(lp);
            bottomSheet.requestLayout();
            View parent = (View) bottomSheet.getParent();
            int width = parent != null ? parent.getWidth() : 0;
            if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
            int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            bottomSheet.measure(widthSpec, heightSpec);
            if (bottomSheet.getMeasuredHeight() > maxH) {
                lp.height = maxH;
                bottomSheet.setLayoutParams(lp);
            }
        });
    }

    // --------------------------- CUSTOMIZE TOOLS ---------------------------

    private void showCustomizeToolsDialog() {
        ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] rebuild = new Runnable[1];
        Runnable refresh = () -> refreshCustomizeList(list);
        rebuild[0] = refresh;
        refresh.run();
        sv.addView(list);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Customize tools")
                .setView(sv)
                .setPositiveButton("Done", (d, w) -> rebuildToolsPage())
                .setNegativeButton("Cancel", (d, w) -> rebuildToolsPage())
                .create();
        dlg.show();

        // "Add custom tool" pinned footer
        TextView add = new TextView(this);
        add.setText("+ Add custom tool");
        add.setTextColor(getColor(R.color.accent));
        add.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        add.setClickable(true);
        add.setBackgroundResource(getSelectableBackground());
        add.setOnClickListener(v -> {
            dlg.dismiss();
            showAddCustomToolDialog();
        });
        list.addView(add);
    }

    private void refreshCustomizeList(LinearLayout container) {
        container.removeAllViews();
        List<ToolRegistry.Tool> tools = ToolRegistry.load(this);
        for (int i = 0; i < tools.size(); i++) {
            final ToolRegistry.Tool t = tools.get(i);
            final String id = t.id;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding((int) dp(8), (int) dp(4), (int) dp(8), (int) dp(4));

            CheckBox cb = new CheckBox(this);
            cb.setChecked(t.visible);
            cb.setText(t.label);
            cb.setTextColor(getColor(R.color.text_primary));
            cb.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageButton up = new ImageButton(this);
            up.setImageResource(android.R.drawable.arrow_up_float);
            up.setBackground(null);
            up.setAlpha(i == 0 ? 0.25f : 1f);
            up.setEnabled(i > 0);
            up.setOnClickListener(v -> { ToolRegistry.moveUp(this, id); refreshCustomizeList(container); });

            ImageButton down = new ImageButton(this);
            down.setImageResource(android.R.drawable.arrow_down_float);
            down.setBackground(null);
            down.setAlpha(i == tools.size() - 1 ? 0.25f : 1f);
            down.setEnabled(i < tools.size() - 1);
            down.setOnClickListener(v -> { ToolRegistry.moveDown(this, id); refreshCustomizeList(container); });

            item.addView(cb);
            if (id.startsWith("custom_")) {
                ImageButton del = new ImageButton(this);
                del.setImageResource(R.drawable.ic_close);
                del.setBackground(null);
                del.setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Delete custom tool?")
                            .setMessage(t.label)
                            .setPositiveButton("Delete", (d, w) -> {
                                ToolRegistry.removeCustom(this, id);
                                refreshCustomizeList(container);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
                item.addView(del);
            } else {
                item.addView(up);
                item.addView(down);
            }

            cb.setOnCheckedChangeListener((b, checked) -> ToolRegistry.setVisible(this, id, checked));
            container.addView(item);
            container.addView(makeDivider());
        }
    }

    private void showAddCustomToolDialog() {
        final EditText name = new EditText(this);
        name.setHint("Tool name");
        name.setSingleLine(true);
        name.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));

        final EditText url = new EditText(this);
        url.setHint("https://...");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setSingleLine(true);
        url.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(name);
        box.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("Add custom tool")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    ToolRegistry.addCustom(this, name.getText().toString(), url.getText().toString());
                    toast("Tool added");
                    rebuildToolsPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------- CLOUD SYNC ---------------------------

    private void showCloudSyncDialog() {
        CloudSyncClient syncClient = new CloudSyncClient(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView desc = new TextView(this);
        desc.setText("Sync your Bookmarks and Notepad notes to an external REST service of your choice.");
        desc.setTextSize(13);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setPadding(0, 0, 0, (int) dp(10));
        layout.addView(desc);

        final EditText ep = new EditText(this);
        ep.setHint("REST API Endpoint URL (POST/GET)");
        ep.setText(syncClient.getEndpoint());
        ep.setSingleLine(true);
        ep.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        layout.addView(ep);

        final EditText token = new EditText(this);
        token.setHint("Bearer Authorization Token (Optional)");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(syncClient.getToken());
        token.setSingleLine(true);
        token.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        layout.addView(token);

        LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.HORIZONTAL);
        btnContainer.setGravity(Gravity.CENTER);
        btnContainer.setPadding(0, (int) dp(14), 0, 0);

        TextView btnBackup = new TextView(this);
        btnBackup.setText("↑ Backup (Push)");
        btnBackup.setTextColor(getColor(R.color.accent));
        btnBackup.setTextSize(15);
        btnBackup.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        btnBackup.setClickable(true);
        btnBackup.setBackgroundResource(getSelectableBackground());
        btnBackup.setOnClickListener(v -> {
            syncClient.setEndpoint(ep.getText().toString());
            syncClient.setToken(token.getText().toString());
            toast("Syncing up…");
            syncClient.backup(this, bookmarks, new CloudSyncClient.Callback() {
                @Override public void onSuccess(String msg) { toast(msg); }
                @Override public void onError(String err) { toast(err); }
            });
        });

        TextView btnRestore = new TextView(this);
        btnRestore.setText("↓ Restore (Pull)");
        btnRestore.setTextColor(getColor(R.color.active));
        btnRestore.setTextSize(15);
        btnRestore.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        btnRestore.setClickable(true);
        btnRestore.setBackgroundResource(getSelectableBackground());
        btnRestore.setOnClickListener(v -> {
            syncClient.setEndpoint(ep.getText().toString());
            syncClient.setToken(token.getText().toString());
            
            new AlertDialog.Builder(this)
                .setTitle("Restore Cloud Data?")
                .setMessage("This will replace your current Bookmarks and Notepad content with the cloud copy.")
                .setPositiveButton("Restore", (d, w) -> {
                    toast("Fetching down…");
                    syncClient.restore(this, bookmarks, new CloudSyncClient.Callback() {
                        @Override public void onSuccess(String msg) { toast(msg); }
                        @Override public void onError(String err) { toast(err); }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        btnContainer.addView(btnBackup);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams((int) dp(16), 1));
        btnContainer.addView(spacer);
        btnContainer.addView(btnRestore);

        layout.addView(btnContainer);

        new AlertDialog.Builder(this)
                .setTitle("Cloud Backup & Sync")
                .setView(layout)
                .setPositiveButton("Save Settings", (d, w) -> {
                    syncClient.setEndpoint(ep.getText().toString());
                    syncClient.setToken(token.getText().toString());
                    toast("Sync settings saved");
                    rebuildSettingsPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------- PROXY ROUTING ---------------------------

    private void showProxySettingsDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView desc = new TextView(this);
        desc.setText("Route your browsing traffic through a local SOCKS5 proxy (like Tor/Orbot) or HTTP proxy.");
        desc.setTextSize(13);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setPadding(0, 0, 0, (int) dp(10));
        layout.addView(desc);

        // Toggle to enable/disable
        boolean isProxyEnabled = getPreferences(Context.MODE_PRIVATE).getBoolean("proxy_enabled", false);
        View toggleRow = makeToggle("Enable Proxy Route", isProxyEnabled, b -> {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("proxy_enabled", b).apply();
        });
        layout.addView(toggleRow);

        // Spinner to select Proxy Type (SOCKS or HTTP)
        TextView typeLabel = new TextView(this);
        typeLabel.setText("Proxy Type");
        typeLabel.setTextColor(getColor(R.color.text_primary));
        typeLabel.setTextSize(14);
        typeLabel.setPadding(0, (int) dp(10), 0, (int) dp(4));
        layout.addView(typeLabel);

        final Spinner typeSpinner = new Spinner(this);
        String[] types = {"SOCKS5 (SOCKS)", "HTTP"};
        final String[] typeSchemes = {"socks", "http"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, types);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);
        
        String savedType = getPreferences(Context.MODE_PRIVATE).getString("proxy_type", "socks");
        typeSpinner.setSelection(savedType.equals("http") ? 1 : 0);
        layout.addView(typeSpinner);

        final EditText epHost = new EditText(this);
        epHost.setHint("Proxy Host (e.g. 127.0.0.1)");
        epHost.setText(getPreferences(Context.MODE_PRIVATE).getString("proxy_host", "127.0.0.1"));
        epHost.setSingleLine(true);
        epHost.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        layout.addView(epHost);

        final EditText epPort = new EditText(this);
        epPort.setHint("Proxy Port (e.g. 9050 for Tor)");
        epPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        epPort.setText(String.valueOf(getPreferences(Context.MODE_PRIVATE).getInt("proxy_port", 9050)));
        epPort.setSingleLine(true);
        epPort.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        layout.addView(epPort);

        new AlertDialog.Builder(this)
                .setTitle("Proxy Routing (Tor/Custom)")
                .setView(layout)
                .setPositiveButton("Save & Apply", (d, w) -> {
                    String host = epHost.getText().toString().trim();
                    String portStr = epPort.getText().toString().trim();
                    int port = 9050;
                    try { port = Integer.parseInt(portStr); } catch (Exception ignored) {}
                    String typeScheme = typeSchemes[typeSpinner.getSelectedItemPosition()];

                    getPreferences(Context.MODE_PRIVATE).edit()
                            .putString("proxy_host", host)
                            .putInt("proxy_port", port)
                            .putString("proxy_type", typeScheme)
                            .apply();

                    // Apply to ALL cores dynamically
                    for (Tab t : tabsList) {
                        t.core.applyProxySettings();
                    }
                    if (splitTabNormal != null) {
                        splitTabNormal.core.applyProxySettings();
                    }
                    toast("Proxy settings updated");
                    rebuildSettingsPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------- ENCLAVE PASSWORD MANAGER ---------------------------

    private void showPasswordManagerDialog() {
        com.minibrowser.database.repositories.CredentialRepository repo = 
                new com.minibrowser.database.repositories.CredentialRepository(this);
        
        ScrollView sv = new ScrollView(this);
        final LinearLayout rootL = new LinearLayout(this);
        rootL.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        rootL.setPadding(pad, pad, pad, pad);

        List<com.minibrowser.database.entities.CredentialEntity> list = repo.getAllCredentials("default_user");
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No saved passwords. They will be encrypted and saved automatically when you log in!");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            rootL.addView(empty);
        } else {
            for (com.minibrowser.database.entities.CredentialEntity c : list) {
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(0, (int) dp(6), 0, (int) dp(6));

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                TextView tv = new TextView(this);
                tv.setText(c.domain + "\nUser: " + c.username);
                tv.setTextColor(getColor(R.color.text_primary));
                tv.setTextSize(14);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                ImageButton del = new ImageButton(this);
                del.setImageResource(R.drawable.ic_close);
                del.setBackground(null);
                del.setOnClickListener(v -> {
                    repo.deleteCredential(c.id);
                    rootL.removeView(item);
                    toast("Deleted saved password");
                });

                row.addView(tv);
                row.addView(del);
                item.addView(row);
                item.addView(makeDivider());
                rootL.addView(item);
            }
        }
        sv.addView(rootL);
        new AlertDialog.Builder(this)
                .setTitle("Enclave Password Manager")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();
    }

    // --------------------------- TRANSLATION ---------------------------

    private void showTranslateLangPicker() {
        String current = core.getTranslateTarget();
        int sel = 0;
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equalsIgnoreCase(current)) { sel = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("Auto-translate target")
                .setSingleChoiceItems(LANG_NAMES, sel, (dlg, which) -> {
                    core.setTranslateTarget(LANG_CODES[which]);
                    toast("Target set to " + LANG_NAMES[which]);
                    rebuildSettingsPage();
                    dlg.dismiss();
                })
                .show();
    }

    private void rebuildSettingsPage() {
        if (sheetPageViews == null) return;
        ScrollView sv = wrapScroll(buildSettingsPage());
        boolean wasVisible = sheetPageViews[2].getVisibility() == View.VISIBLE;
        sheetPages.removeView(sheetPageViews[2]);
        sheetPages.addView(sv, 2);
        sheetPageViews[2] = sv;
        sv.setVisibility(wasVisible ? View.VISIBLE : View.GONE);
        applySheetHeightCap();
    }

    // --------------------------- SEARCH ENGINE ---------------------------

    private void chooseSearchEngine() {
        String current = getPreferences(Context.MODE_PRIVATE).getString("search_engine", ENGINE_TEMPLATES[0]);
        int sel = 0;
        for (int i = 0; i < ENGINE_TEMPLATES.length; i++) {
            if (ENGINE_TEMPLATES[i].equals(current)) { sel = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("Default search engine")
                .setSingleChoiceItems(ENGINE_NAMES, sel, (dlg, which) -> {
                    core.setSearchEngine(ENGINE_TEMPLATES[which]);
                    toast("Search set to " + ENGINE_NAMES[which]);
                    dlg.dismiss();
                })
                .show();
    }

    // --------------------------- MEDIA & DOWNLOADS ---------------------------

    private void downloadMedia() {
        core.scanForMedia(urls -> {
            if (urls.isEmpty()) { toast("No media sniffed on this page"); return; }
            String[] arr = urls.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle("Download media")
                    .setItems(arr, (d, which) -> {
                        String url = arr[which];
                        com.minibrowser.download.DownloadManager.get().enqueue(url, core.currentUrl(), null);
                        toast("Enqueued: " + url.substring(Math.max(0, url.length() - 25)));
                    }).show();
        });
    }

    private void playMedia() {
        core.scanForMedia(urls -> {
            if (urls.isEmpty()) { toast("No playable media found"); return; }
            String[] arr = urls.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle("Play media in ExoPlayer")
                    .setItems(arr, (d, which) -> {
                        String url = arr[which];
                        Intent intent = new Intent(this, com.minibrowser.media.MediaPlayerActivity.class);
                        intent.setData(Uri.parse(url));
                        startActivity(intent);
                    }).show();
        });
    }

    private void playSnack() {
        core.scanForMedia(urls -> {
            if (urls.isEmpty()) { toast("No media to play in Snack"); return; }
            String[] arr = urls.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle("Play in floating snack")
                    .setItems(arr, (d, which) -> {
                        String url = arr[which];
                        if (snackPlayer == null) snackPlayer = new SnackPlayer(this, root);
                        snackPlayer.play(url, "Snack: " + currentTitle);
                    }).show();
        });
    }

    private void showOfflineList() {
        List<File> files = core.listOfflinePages();
        if (files.isEmpty()) { toast("No offline pages. Save some via Tools Menu."); return; }
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) names[i] = files.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle("Offline Pages")
                .setItems(names, (d, which) -> {
                    hideSheet();
                    core.loadOffline(files.get(which));
                }).show();
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle("Clear all private data?")
                .setMessage("This will wipe cache, history, cookies, and web storage.")
                .setPositiveButton("Clear", (d, w) -> {
                    core.clearAllPrivateData();
                    toast("All private data wiped");
                    hideSheet();
                    goHome();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------- SOCIAL & AUX ---------------------------

    private void shareCurrent() {
        String url = core.currentUrl();
        if (url == null || url.startsWith("about:")) { toast("Nothing to share"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(i, "Share URL"));
    }

    private void copyCurrentUrl() {
        String url = core.currentUrl();
        if (url == null || url.startsWith("about:")) { toast("No URL to copy"); return; }
        android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(android.content.ClipData.newPlainText("URL", url));
            toast("URL copied to clipboard");
        }
    }

    private void updateBookmarkIcon(String url) {
        if (url == null || url.startsWith("about:")) {
            btnBookmark.setVisibility(View.GONE);
            return;
        }
        btnBookmark.setVisibility(View.VISIBLE);
        boolean has = bookmarks.contains(url);
        btnBookmark.setImageResource(has ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark_outline);
    }

    private void toggleBookmark() {
        String url = core.currentUrl();
        if (url == null || url.startsWith("about:")) return;
        if (bookmarks.contains(url)) {
            bookmarks.remove(url);
            toast("Removed bookmark");
        } else {
            bookmarks.add(currentTitle, url);
            toast("Bookmarked page");
        }
        updateBookmarkIcon(url);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("About MiniBrowser")
                .setMessage("MiniBrowser is a lightweight, privacy-focused Android Web Browser.\n\n"
                        + "Key features:\n"
                        + "• Dynamic Multi-Tab Support\n"
                        + "• Dual Mode Split-Screen (Normal Surf vs Privacy Top-Bottom Stack)\n"
                        + "• Dynamic Panel Minimizing / Collapsing with Quick-Restore Bars\n"
                        + "• Native Force-Dark Algorithmic Darkening\n"
                        + "• Customized Native Error Screens\n"
                        + "• In-App Draggable Floating Snack Player\n"
                        + "• Tor SOCKS5 / HTTP Proxy overrides\n"
                        + "• Enclave Password Autofill & Manager\n"
                        + "• Thread-safe ad/tracker blocking with toggle shields\n"
                        + "• Keystore-encrypted AI API key storage\n"
                        + "• Multi-provider AI Chat integration\n"
                        + "• Cloud Sync backup/restores\n"
                        + "• Full gesture navigation\n"
                        + "• In-house HLS/DASH media downloading\n\n"
                        + "• Built with Gradle + AndroidX.")
                .setPositiveButton("OK", null).show();
    }

    void onElementPicked(final String selector) {
        if (selector == null || selector.trim().isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Block this element?")
                .setMessage("Selector:\n" + selector + "\n\nIt will be hidden on every page via custom.css.")
                .setPositiveButton("Block", (d, w) -> { core.appendBlockSelector(selector); toast("Element blocked"); })
                .setNegativeButton("Cancel", null).show();
    }

    // --------------------------- AI CHAT PANEL ---------------------------

    private void buildAiPanel() {
        aiPanel = findViewById(R.id.ai_panel);
        aiSpinner = findViewById(R.id.ai_spinner);
        aiScroll = findViewById(R.id.ai_scroll);
        aiMessages = findViewById(R.id.ai_messages);
        aiInput = findViewById(R.id.ai_input);
        ImageButton settings = findViewById(R.id.btn_ai_settings);
        ImageButton close = findViewById(R.id.btn_ai_close);
        ImageButton send = findViewById(R.id.btn_ai_send);

        // Populate providers
        List<String> names = new ArrayList<>();
        List<AiClient.Provider> list = aiClient.getProviders();
        for (AiClient.Provider p : list) names.add(p.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aiSpinner.setAdapter(adapter);

        // Active selection
        String activeId = aiClient.getActiveProviderId();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(activeId)) { aiSpinner.setSelection(i); break; }
        }

        aiSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p1, View p2, int pos, long p4) {
                if (!aiSpinnerReady) { aiSpinnerReady = true; return; }
                String id = aiClient.getProviders().get(pos).id;
                aiClient.setActiveProvider(id);
                toast("AI Provider set to " + aiClient.getProviders().get(pos).name);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p1) { }
        });

        settings.setOnClickListener(v -> showAiSettings());
        close.setOnClickListener(v -> hideAiPanel());

        final Runnable doSend = () -> {
            String text = aiInput.getText().toString().trim();
            if (!text.isEmpty()) { aiInput.setText(""); sendMessage(text); }
        };
        send.setOnClickListener(v -> doSend.run());
        aiInput.setOnEditorActionListener((v, actionId, e) -> { if (actionId == EditorInfo.IME_ACTION_SEND) { doSend.run(); return true; } return false; });
    }

    private void showAiPanel() {
        aiPanelVisible = true;
        vm.setAiPanelVisible(true);
        aiPanel.setVisibility(View.VISIBLE);
        aiPanel.setTranslationY(aiPanel.getHeight() == 0 ? dp(600) : aiPanel.getHeight());
        aiPanel.animate().translationY(0f).setDuration(220).start();
        
        // Restore messages
        aiMessages.removeAllViews();
        for (AiClient.Msg m : vm.getAiHistory()) appendMsg(m.user, m.text);
        aiScroll.post(() -> aiScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void hideAiPanel() {
        aiPanelVisible = false;
        vm.setAiPanelVisible(false);
        aiPanel.animate().translationY(aiPanel.getHeight()).setDuration(200)
                .withEndAction(() -> aiPanel.setVisibility(View.GONE)).start();
        hideKeyboard(aiInput);
    }

    private void sendMessage(String text) {
        appendMsg(true, text);
        vm.addAiMessage(true, text);
        aiScroll.post(() -> aiScroll.fullScroll(View.FOCUS_DOWN));

        aiClient.send(new ArrayList<>(vm.getAiHistory()), new AiClient.Callback() {
            @Override
            public void onReply(String reply) {
                vm.addAiMessage(false, reply);
                appendMsg(false, reply);
                aiScroll.post(() -> aiScroll.fullScroll(View.FOCUS_DOWN));
            }
            @Override
            public void onError(String err) {
                toast(err);
            }
        });
    }

    private void appendMsg(boolean user, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(8);
        lp.bottomMargin = (int) dp(8);

        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(12));
        if (user) {
            lp.gravity = Gravity.END;
            lp.leftMargin = (int) dp(60);
            tv.setTextColor(0xFFFFFFFF);
            gd.setColor(getColor(R.color.accent));
        } else {
            lp.gravity = Gravity.START;
            lp.rightMargin = (int) dp(60);
            tv.setTextColor(getColor(R.color.text_primary));
            gd.setColor(getColor(R.color.surface_elevated));
        }
        tv.setBackground(gd);
        tv.setLayoutParams(lp);
        aiMessages.addView(tv);
    }

    private void showAiSettings() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        layout.setPadding(pad, pad, pad, pad);

        TextView provT = new TextView(this);
        provT.setText("AI Provider settings: " + aiClient.getActiveProvider().name);
        provT.setTextSize(16);
        provT.setTextColor(getColor(R.color.text_primary));
        layout.addView(provT);

        final EditText ep = new EditText(this);
        ep.setHint("API Endpoint URL");
        ep.setText(aiClient.getEndpoint());
        ep.setSingleLine(true);
        ep.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        layout.addView(ep);

        final EditText model = new EditText(this);
        model.setHint("Model (e.g. gpt-4o-mini)");
        model.setText(aiClient.getModel());
        model.setSingleLine(true);
        model.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        layout.addView(model);

        final EditText key = new EditText(this);
        key.setHint("API Key (Stored in secure Keystore)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(aiClient.getKey());
        key.setSingleLine(true);
        key.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        layout.addView(key);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("AI API Configuration")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    aiClient.setEndpoint(ep.getText().toString());
                    aiClient.setModel(model.getText().toString());
                    aiClient.setKey(key.getText().toString());
                    toast("AI configuration saved");
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Delete Custom", null)
                .create();

        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b -> {
            String activeId = aiClient.getActiveProviderId();
            if (activeId.startsWith("custom_")) {
                new AlertDialog.Builder(this)
                        .setTitle("Delete custom provider?")
                        .setMessage(aiClient.getActiveProvider().name)
                        .setPositiveButton("Delete", (d2, w2) -> {
                            aiClient.removeCustomProvider(activeId);
                            toast("Provider deleted");
                            dlg.dismiss();
                            buildAiPanel(); // rebuild panel spinner
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                toast("Built-in providers cannot be deleted");
            }
        });
    }

    // --------------------------- BOOKMARKS DIALOG ---------------------------

    private void showBookmarks() {
        ScrollView sv = new ScrollView(this);
        final LinearLayout rootL = new LinearLayout(this);
        rootL.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(16);
        rootL.setPadding(pad, pad, pad, pad);

        List<Bookmarks.Entry> list = bookmarks.snapshot();
        if (list.isEmpty()) { toast("No bookmarks saved"); return; }

        for (Bookmarks.Entry e : list) {
            final String url = e.url;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, (int) dp(6), 0, (int) dp(6));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(this);
            tv.setText(e.title + "\n" + url);
            tv.setTextColor(getColor(R.color.text_primary));
            tv.setTextSize(14);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setClickable(true);
            tv.setBackgroundResource(getSelectableBackground());
            tv.setOnClickListener(v -> { core.loadUrl(url); hideSheet(); });

            ImageButton del = new ImageButton(this);
            del.setImageResource(R.drawable.ic_close);
            del.setBackground(null);
            del.setContentDescription("Remove bookmark");
            del.setOnClickListener(v -> { bookmarks.remove(url); rootL.removeView(item); toast("Removed"); });
            row.addView(tv);
            row.addView(del);
            item.addView(row);
            item.addView(makeDivider());
            rootL.addView(item);
        }
        sv.addView(rootL);
        new AlertDialog.Builder(this).setTitle("Bookmarks").setView(sv).setNegativeButton("Close", null).show();
    }

    // --------------------------- PERMISSIONS & SYS CALLBACKS ---------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        core.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) core.onFileChooserResult(resultCode, data);
    }

    // ============================ BROWSER CORE CALLBACKS ============================

    @Override
    public void onUrlChanged(BrowserCore sender, String url) {
        Tab tab = findTabForCore(sender);
        if (tab != null) {
            tab.url = url;
            if (url != null && !url.startsWith("about:")) {
                tab.isHome = false;
            }
        }
        
        // Only update screen UI if the callback comes from the active tab's core
        if (sender != core) return;

        updateBookmarkIcon(url);
        if (url == null || url.startsWith("about:")) return;
        isHome = false;
        vm.setHome(false);
        homeOverlay.setVisibility(View.GONE);
        try { Uri uri = Uri.parse(url); String host = uri.getHost(); urlBar.setText(host != null ? host : url); }
        catch (Exception e) { urlBar.setText(url); }
        updateNavButtons();
    }

    @Override 
    public void onProgress(BrowserCore sender, int p) {
        // Only update screen UI if the callback comes from the active tab's core
        if (sender != core) return;

        if (p >= 100) progress.setVisibility(View.GONE);
        else { progress.setVisibility(View.VISIBLE); progress.setProgress(p); }
    }

    @Override 
    public void onPageFinished(BrowserCore sender, String url) {
        Tab tab = findTabForCore(sender);
        if (tab != null) {
            tab.url = url;
        }

        // Only update screen UI if the callback comes from the active tab's core
        if (sender != core) return;

        progress.setVisibility(View.GONE); 
        updateNavButtons(); 
        updateBookmarkIcon(url); 
    }

    @Override 
    public void onTitleChanged(BrowserCore sender, String title) {
        Tab tab = findTabForCore(sender);
        if (tab != null) {
            tab.title = title == null ? "New Tab" : title;
        }

        // Only update screen UI if the callback comes from the active tab's core
        if (sender != core) return;

        currentTitle = title == null ? "" : title; 
    }

    @Override 
    public void onHome(BrowserCore sender) {
        Tab tab = findTabForCore(sender);
        if (tab != null) {
            tab.isHome = true;
            tab.url = "";
            tab.title = "New Tab";
        }

        // Only update screen UI if the callback comes from the active tab's core
        if (sender != core) return;

        isHome = true; 
        vm.setHome(true); 
        homeOverlay.setVisibility(View.VISIBLE); 
    }

    @Override 
    public void showToast(String msg) { 
        toast(msg); 
    }

    // ============================ UI UTILITIES ============================

    private void updateNavButtons() {
        btnBack.setAlpha(core.canGoBack() ? 1f : 0.35f);
        btnBack.setEnabled(core.canGoBack());
        btnForward.setAlpha(core.canGoForward() ? 1f : 0.35f);
        btnForward.setEnabled(core.canGoForward());
    }

    private android.content.SharedPreferences getPref() { return getPreferences(Context.MODE_PRIVATE); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
    private float dp(int v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    
    // Auxiliary fullscreen control
    private void hideChrome() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctrl = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (ctrl != null) {
            ctrl.hide(WindowInsetsCompat.Type.systemBars());
            ctrl.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        toolbar.setVisibility(View.GONE);
    }

    private void showChrome() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        WindowInsetsControllerCompat ctrl = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (ctrl != null) {
            ctrl.show(WindowInsetsCompat.Type.systemBars());
        }
        toolbar.setVisibility(View.VISIBLE);
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        vm.setFullscreen(on);
        if (on) hideChrome(); else showChrome();
    }
}
