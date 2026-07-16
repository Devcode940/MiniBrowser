package com.minibrowser;

import android.app.Activity;
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

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.ViewModelProvider;

import com.minibrowser.media.AiClient;
import com.minibrowser.media.SnackPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements BrowserCore.Callback {

    public static final int REQ_FILE = 0xA001;
    public static final int REQ_GEO = 0xA002;
    public static final int REQ_WEB_PERMS = 0xA003;

    private MainViewModel vm;
    private BrowserCore core;
    private GestureWebView webview;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vm = new ViewModelProvider(this).get(MainViewModel.class);

        root = findViewById(R.id.root);
        webview = findViewById(R.id.webview);
        toolbar = findViewById(R.id.toolbar);
        bindViews();

        core = new BrowserCore(this, webview, this);
        bookmarks = new Bookmarks(this);
        aiClient = new AiClient(this);

        webview.setGestureListener(new GestureWebView.GestureListener() {
            @Override public void onEdgeBack()    { core.goBack(); }
            @Override public void onEdgeForward() { core.goForward(); }
        });
        webview.setScrollListener((dx, dy, atTop) -> {
            if (fullscreen) {
                if (dy > 8) hideChrome();
                else if (dy < -8 || atTop) showChrome();
            }
        });

        setupToolbar();
        setupHome();
        setupBottomSheet();
        buildAiPanel();

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
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); webview.saveState(outState); }
    @Override protected void onDestroy() {
        if (snackPlayer != null) snackPlayer.close();
        core.destroy();
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
            icon.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
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
        page.addView(makeToggle("Compatibility Mode", compat, b -> { core.setCompatibilityMode(b); toast(b ? "Compatibility mode on" : "Strict mode on"); }));
        page.addView(makeDivider());
        page.addView(makeToggle("Cellular Guard", guard, b -> { core.setCellGuard(b); toast(b ? "Cellular guard on" : "Cellular guard off"); }));
        page.addView(makeDivider());
        page.addView(makeToggle("Safe Browsing", safe, b -> { core.setSafeBrowsing(b); toast(b ? "Safe browsing on" : "Safe browsing off"); }));
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
            promptAddCustomTool();
        });
        ((LinearLayout) dlg.findViewById(android.R.id.content).getParent()).addView(add);
    }

    private void refreshCustomizeList(LinearLayout list) {
        list.removeAllViews();
        for (ToolRegistry.Tool t : ToolRegistry.load(this)) {
            final String id = t.id;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) dp(4), (int) dp(8), (int) dp(4), (int) dp(8));

            ImageButton up = new ImageButton(this);
            up.setImageResource(R.drawable.ic_back);
            up.setRotation(90f);
            up.setBackground(null);
            up.setOnClickListener(v -> { ToolRegistry.moveUp(this, id); refreshCustomizeList(list); });

            ImageButton down = new ImageButton(this);
            down.setImageResource(R.drawable.ic_forward);
            down.setRotation(-90f);
            down.setBackground(null);
            down.setOnClickListener(v -> { ToolRegistry.moveDown(this, id); refreshCustomizeList(list); });

            CheckBox cb = new CheckBox(this);
            cb.setText(t.label);
            cb.setTextColor(getColor(R.color.text_primary));
            cb.setChecked(t.visible);
            cb.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cb.setOnCheckedChangeListener((b, checked) -> ToolRegistry.setVisible(this, id, checked));

            row.addView(up);
            row.addView(down);
            row.addView(cb);

            if (t.url != null) { // custom tool: allow delete
                ImageButton del = new ImageButton(this);
                del.setImageResource(R.drawable.ic_close);
                del.setBackground(null);
                del.setOnClickListener(v -> {
                    ToolRegistry.removeCustom(this, id);
                    refreshCustomizeList(list);
                });
                row.addView(del);
            }
            list.addView(row);
            list.addView(makeDivider());
        }
    }

    private void promptAddCustomTool() {
        final EditText name = new EditText(this);
        name.setHint("Label (e.g. YouTube)");
        final EditText url = new EditText(this);
        url.setHint("https://…");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
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

    // --------------------------- TRANSLATION ---------------------------

    private void showTranslateLangPicker() {
        String current = core.getTranslateTarget();
        int sel = 0;
        for (int i = 0; i < LANG_CODES.length; i++) if (LANG_CODES[i].equals(current)) sel = i;
        new AlertDialog.Builder(this)
                .setTitle("Translation language")
                .setSingleChoiceItems(LANG_NAMES, sel, (d, which) -> {
                    core.setTranslateTarget(LANG_CODES[which]);
                    toast("Translate to " + LANG_NAMES[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------- AI PANEL ---------------------------

    private void buildAiPanel() {
        aiPanel = new LinearLayout(this);
        int w = (int) Math.min(dp(330), getResources().getDisplayMetrics().widthPixels * 0.86);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END;
        aiPanel.setLayoutParams(lp);
        aiPanel.setOrientation(LinearLayout.VERTICAL);
        aiPanel.setBackgroundColor(getColor(R.color.surface));
        aiPanel.setElevation(dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(getColor(R.color.primary));
        header.setPadding((int) dp(8), (int) dp(8), (int) dp(4), (int) dp(8));

        aiSpinner = new Spinner(this);
        aiSpinner.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        aiSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long idl) {
                if (!aiSpinnerReady) return;
                List<AiClient.Provider> ps = aiClient.getProviders();
                if (pos >= 0 && pos < ps.size()) {
                    aiClient.setActiveProvider(ps.get(pos).id);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        ImageButton settings = new ImageButton(this);
        settings.setImageResource(R.drawable.ic_menu);
        settings.setBackground(null);
        settings.setOnClickListener(v -> showAiSettings());
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setBackground(null);
        close.setOnClickListener(v -> hideAiPanel());
        header.addView(aiSpinner);
        header.addView(settings);
        header.addView(close);
        aiPanel.addView(header);

        aiScroll = new ScrollView(this);
        aiMessages = new LinearLayout(this);
        aiMessages.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(12);
        aiMessages.setPadding(pad, pad, pad, pad);
        aiScroll.addView(aiMessages);
        aiScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        aiPanel.addView(aiScroll);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setBackgroundColor(getColor(R.color.surface_elevated));
        inputRow.setPadding((int) dp(8), (int) dp(8), (int) dp(8), (int) dp(8));
        aiInput = new EditText(this);
        aiInput.setHint("Ask anything…");
        aiInput.setTextColor(getColor(R.color.text_primary));
        aiInput.setHintTextColor(getColor(R.color.text_secondary));
        aiInput.setBackgroundColor(0);
        aiInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        aiInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        aiInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        aiInput.setMaxLines(4);
        ImageButton send = new ImageButton(this);
        send.setImageResource(R.drawable.ic_send);
        send.setBackground(null);
        Runnable doSend = this::sendAi;
        send.setOnClickListener(v -> doSend.run());
        aiInput.setOnEditorActionListener((v, actionId, e) -> { if (actionId == EditorInfo.IME_ACTION_SEND) { doSend.run(); return true; } return false; });
        inputRow.addView(aiInput);
        inputRow.addView(send);
        aiPanel.addView(inputRow);

        root.addView(aiPanel);
        aiPanel.setVisibility(View.GONE);
        rebuildProviderSpinner();
    }

    private void rebuildProviderSpinner() {
        if (aiSpinner == null) return;
        List<AiClient.Provider> ps = aiClient.getProviders();
        String active = aiClient.getActiveProviderId();
        String[] names = new String[ps.size()];
        int sel = 0;
        for (int i = 0; i < ps.size(); i++) {
            names[i] = ps.get(i).name;
            if (ps.get(i).id.equals(active)) sel = i;
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        aiSpinnerReady = false;
        aiSpinner.setAdapter(a);
        if (sel >= 0 && sel < names.length) aiSpinner.setSelection(sel);
        aiSpinnerReady = true;
    }

    private void showAiPanel() {
        aiPanelVisible = true;
        vm.setAiPanelVisible(true);
        aiPanel.setVisibility(View.VISIBLE);
        aiPanel.setTranslationX(aiPanel.getWidth() + dp(8));
        aiPanel.post(() -> aiPanel.animate().translationX(0f).setDuration(220).start());
        if (aiMessages.getChildCount() == 0) {
            addBubble(false, "Hi! I'm your in-browser AI assistant.\n"
                    + (aiClient.isConfigured()
                        ? "Using " + aiClient.getActiveProvider().name + ". Ask away."
                        : "Pick a provider above, then set your key in the ⋮ menu."));
        }
        aiInput.requestFocus();
    }

    private void hideAiPanel() {
        aiPanelVisible = false;
        vm.setAiPanelVisible(false);
        aiPanel.animate().translationX(aiPanel.getWidth() + dp(8)).setDuration(200)
                .withEndAction(() -> aiPanel.setVisibility(View.GONE)).start();
        hideKeyboard(aiInput);
    }

    private TextView addBubble(boolean user, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int px = (int) dp(12);
        tv.setPadding(px, (int) dp(8), px, (int) dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = user ? Gravity.END : Gravity.START;
        lp.bottomMargin = (int) dp(8);
        tv.setLayoutParams(lp);
        if (user) {
            tv.setBackground(makeColorDrawable(getColor(R.color.accent)));
            tv.setTextColor(getColor(R.color.text_inverse));
        } else {
            tv.setBackground(makeColorDrawable(getColor(R.color.surface_elevated)));
            tv.setTextColor(getColor(R.color.text_primary));
        }
        aiMessages.addView(tv);
        aiScroll.post(() -> aiScroll.fullScroll(View.FOCUS_DOWN));
        return tv;
    }

    private Drawable makeColorDrawable(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }

    private void sendAi() {
        String text = aiInput.getText().toString().trim();
        if (text.isEmpty()) return;
        aiInput.setText("");
        addBubble(true, text);
        vm.addAiMessage(true, text);
        if (!aiClient.isConfigured()) {
            addBubble(false, "Set an endpoint/key for " + aiClient.getActiveProvider().name + " via the ⋮ menu.");
            return;
        }
        final TextView thinking = addBubble(false, "Thinking… (" + aiClient.getActiveProvider().name + ")");
        aiClient.send(new ArrayList<>(vm.getAiHistory()), new AiClient.Callback() {
            @Override public void onReply(String reply) {
                aiMessages.removeView(thinking);
                addBubble(false, reply);
                vm.addAiMessage(false, reply);
            }
            @Override public void onError(String message) {
                aiMessages.removeView(thinking);
                addBubble(false, "Error: " + message);
            }
        });
    }

    private void showAiSettings() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) dp(20), (int) dp(12), (int) dp(20), (int) dp(4));

        final AiClient.Provider active = aiClient.getActiveProvider();
        final EditText ep = new EditText(this);
        ep.setHint("Endpoint (chat/completions URL)");
        ep.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        ep.setText(aiClient.getEndpoint());
        final EditText ek = new EditText(this);
        ek.setHint("API key");
        ek.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ek.setText(aiClient.getKey());
        final EditText em = new EditText(this);
        em.setHint("Model");
        em.setText(aiClient.getModel());
        box.addView(makeStatus("Active provider: " + active.name));
        box.addView(ep);
        box.addView(ek);
        box.addView(em);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("AI providers")
                .setMessage("Edit credentials for " + active.name + ". Use the dropdown in the panel to switch providers.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    aiClient.setEndpoint(ep.getText().toString().trim());
                    aiClient.setKey(ek.getText().toString().trim());
                    aiClient.setModel(em.getText().toString().trim());
                    toast("Saved for " + active.name);
                })
                .setNeutralButton("Add custom", null)
                .setNegativeButton("Done", null)
                .create();
        dlg.show();
        // Override neutral so the dialog stays open is not needed; just open sub-dialog.
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b -> {
            dlg.dismiss();
            promptAddCustomProvider();
        });
    }

    private void promptAddCustomProvider() {
        final EditText name = new EditText(this);
        name.setHint("Provider name");
        final EditText ep = new EditText(this);
        ep.setHint("https://…/chat/completions");
        ep.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        final EditText em = new EditText(this);
        em.setHint("Model");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(name);
        box.addView(ep);
        box.addView(em);
        new AlertDialog.Builder(this)
                .setTitle("Add custom provider")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    aiClient.addCustomProvider(name.getText().toString(), ep.getText().toString(), em.getText().toString());
                    rebuildProviderSpinner();
                    toast("Provider added");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------- FULLSCREEN ---------------------------

    private void setFullscreen(boolean on) {
        fullscreen = on;
        vm.setFullscreen(on);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        WindowCompat.setDecorFitsSystemWindows(getWindow(), !on);
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            hideChrome();
            toast("Fullscreen — scroll up to show controls");
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
            showChrome();
        }
    }

    private void hideChrome() {
        if (toolbar == null) return;
        toolbar.animate().translationY(-toolbar.getHeight() - dp(8)).setDuration(180).start();
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void showChrome() {
        if (toolbar == null) return;
        toolbar.animate().translationY(0f).setDuration(180).start();
    }

    // --------------------------- DIALOGS ---------------------------

    private void chooseSearchEngine() {
        String current = getPref().getString("search_engine", "https://duckduckgo.com/?q=%s");
        int selected = 0;
        for (int i = 0; i < ENGINE_TEMPLATES.length; i++) if (ENGINE_TEMPLATES[i].equals(current)) { selected = i; break; }
        new AlertDialog.Builder(this)
                .setTitle("Search engine")
                .setSingleChoiceItems(ENGINE_NAMES, selected, (d, which) -> {
                    core.setSearchEngine(ENGINE_TEMPLATES[which]); toast(ENGINE_NAMES[which]); d.dismiss();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void openAssetEditor(final String title, String initial, final java.util.function.Consumer<String> onSave) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setText(initial != null ? initial : "");
        et.setSelection(et.getText().length());
        et.setMinLines(8);
        et.setPadding((int) dp(16), (int) dp(12), (int) dp(16), (int) dp(12));
        new AlertDialog.Builder(this).setTitle(title).setView(et)
                .setPositiveButton("Save", (d, w) -> { onSave.accept(et.getText().toString()); toast(title + " saved"); })
                .setNegativeButton("Cancel", null).show();
    }

    private void showOfflineList() {
        final List<File> pages = core.listOfflinePages();
        if (pages.isEmpty()) { toast("No offline pages yet"); return; }
        String[] names = new String[pages.size()];
        for (int i = 0; i < pages.size(); i++) names[i] = pages.get(i).getName().replace(".html", "");
        new AlertDialog.Builder(this).setTitle("Offline pages").setItems(names, (d, which) -> { core.loadOffline(pages.get(which)); hideSheet(); }).setNegativeButton("Close", null).show();
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this).setTitle("Clear browsing data").setMessage("Erase cache, cookies, history and storage?")
                .setPositiveButton("Clear", (d, w) -> { core.clearAllPrivateData(); toast("Data cleared"); })
                .setNegativeButton("Cancel", null).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("MiniBrowser")
                .setMessage("MiniBrowser v17\n\nPrivacy-first browser + downloader.\n"
                        + "• Multi-provider AI sidebar (OpenAI-compat)\n"
                        + "• HLS/DASH download + player (PiP) + snack player\n"
                        + "• SurfingKeys vim nav + auto-translate\n"
                        + "• Customizable tools & ad/tracker blocking\n\n"
                        + "Built with Gradle + AndroidX.")
                .setPositiveButton("OK", null).show();
    }

    void onElementPicked(final String selector) {
        if (selector == null || selector.trim().isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Block this element?")
                .setMessage("Selector:\n" + selector + "\n\nIt will be hidden on every page via custom.css.")
                .setPositiveButton("Block", (d, w) -> { core.appendBlockSelector(selector); toast("Element blocked"); })
                .setNegativeButton("Cancel", null).show();
    }

    private void shareCurrent() {
        String url = core.currentUrl();
        if (url == null || url.startsWith("about:")) { toast("Nothing to share"); return; }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(send, "Share via"));
    }

    private void copyCurrentUrl() {
        String url = core.currentUrl();
        if (url == null || url.startsWith("about:")) { toast("Nothing to copy"); return; }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
        toast("URL copied");
    }

    // --------------------------- DOWNLOAD / PLAY / SNACK ---------------------------

    private void downloadMedia() {
        final String pageUrl = core.currentUrl();
        toast("Scanning page for media…");
        core.scanForMedia(urls -> {
            if (urls == null || urls.isEmpty()) { promptManualDownload(pageUrl); return; }
            pickMedia(urls, "Choose media to download", url -> enqueueDownload(url, pageUrl), pageUrl);
        });
    }

    private void playMedia() {
        final String pageUrl = core.currentUrl();
        toast("Scanning page for media…");
        core.scanForMedia(urls -> {
            if (urls == null || urls.isEmpty()) { promptManualPlay(null); return; }
            pickMedia(urls, "Choose media to play", this::launchPlayerUrl, null);
        });
    }

    private void playSnack() {
        final String pageUrl = core.currentUrl();
        toast("Scanning page for media…");
        core.scanForMedia(urls -> {
            if (urls == null || urls.isEmpty()) { promptManualSnack(); return; }
            pickMedia(urls, "Play in snack", url -> {
                ensureSnack().play(url, hostOf(url));
                toast("Playing in snack");
            }, null);
        });
    }

    private SnackPlayer ensureSnack() {
        if (snackPlayer == null) snackPlayer = new SnackPlayer(this, root);
        return snackPlayer;
    }

    private String hostOf(String url) {
        try { return Uri.parse(url).getHost(); } catch (Exception e) { return "Snack player"; }
    }

    private void pickMedia(List<String> urls, String title, java.util.function.Consumer<String> onPick, String pageUrl) {
        String[] items = new String[urls.size() + 1];
        items[0] = "Enter URL manually…";
        for (int i = 0; i < urls.size(); i++) {
            com.minibrowser.download.DownloadTask.Type t = com.minibrowser.download.MediaSniffer.classify(urls.get(i));
            String tag = t != null ? " [" + t.name() + "]" : "";
            String u = urls.get(i);
            items[i + 1] = (u.length() > 60 ? u.substring(0, 60) + "..." : u) + tag;
        }
        new AlertDialog.Builder(this).setTitle(title).setItems(items, (d, which) -> {
            if (which == 0) {
                if (pageUrl != null) promptManualDownload(pageUrl);
                else if (title != null && title.contains("snack")) promptManualSnack();
                else promptManualPlay(null);
            } else {
                onPick.accept(urls.get(which - 1));
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void promptManualDownload(final String pageUrl) {
        final EditText et = new EditText(this);
        et.setHint("https://example.com/stream.m3u8");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Download URL").setView(et)
                .setPositiveButton("Download", (d, w) -> { String u = et.getText().toString().trim(); if (!u.isEmpty()) enqueueDownload(u, pageUrl); })
                .setNegativeButton("Cancel", null).show();
    }

    private void promptManualPlay(String pageUrl) {
        final EditText et = new EditText(this);
        et.setHint("https://example.com/video.mp4");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Play URL").setView(et)
                .setPositiveButton("Play", (d, w) -> { String u = et.getText().toString().trim(); if (!u.isEmpty()) launchPlayerUrl(u); })
                .setNegativeButton("Cancel", null).show();
    }

    private void promptManualSnack() {
        final EditText et = new EditText(this);
        et.setHint("https://example.com/audio.mp3");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Snack URL").setView(et)
                .setPositiveButton("Play", (d, w) -> { String u = et.getText().toString().trim(); if (!u.isEmpty()) { ensureSnack().play(u, hostOf(u)); toast("Playing in snack"); } })
                .setNegativeButton("Cancel", null).show();
    }

    private void enqueueDownload(String url, String pageUrl) {
        com.minibrowser.download.DownloadManager.get().enqueue(url, pageUrl, null);
        toast("Download queued");
        Intent i = new Intent(this, com.minibrowser.download.DownloadActivity.class);
        i.putExtra("url", url);
        i.putExtra("pageUrl", pageUrl);
        startActivity(i);
    }

    private void launchPlayerUrl(String url) {
        Intent i = new Intent(this, com.minibrowser.media.MediaPlayerActivity.class);
        i.setDataAndType(Uri.parse(url), null);
        startActivity(i);
    }

    // --------------------------- BOOKMARKS ---------------------------

    private void toggleBookmark() {
        String url = core.currentUrl();
        if (url == null || url.startsWith("about:")) { toast("Open a page first"); return; }
        String title = (currentTitle != null && !currentTitle.isEmpty()) ? currentTitle : url;
        if (bookmarks.contains(url)) { bookmarks.remove(url); toast("Bookmark removed"); }
        else { bookmarks.add(title, url); toast("Bookmarked"); }
        updateBookmarkIcon(url);
    }

    private void updateBookmarkIcon(String url) {
        boolean on = url != null && !url.startsWith("about:") && bookmarks.contains(url);
        btnBookmark.setImageResource(on ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark_outline);
        btnBookmark.setAlpha(on ? 1f : 0.7f);
    }

    private void showBookmarks() {
        final List<Bookmarks.Entry> list = bookmarks.snapshot();
        if (list.isEmpty()) { toast("No bookmarks yet"); return; }
        ScrollView sv = new ScrollView(this);
        final LinearLayout rootL = new LinearLayout(this);
        rootL.setOrientation(LinearLayout.VERTICAL);
        for (Bookmarks.Entry e : list) {
            final String url = e.url;
            final LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) dp(4), (int) dp(8), (int) dp(4), (int) dp(8));
            TextView tv = new TextView(this);
            String label = (e.title != null && !e.title.isEmpty()) ? e.title : url;
            tv.setText(label);
            tv.setTextColor(getColor(R.color.text_primary));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setSingleLine(true);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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

    @Override
    public void onUrlChanged(String url) {
        updateBookmarkIcon(url);
        if (url == null || url.startsWith("about:")) return;
        isHome = false;
        homeOverlay.setVisibility(View.GONE);
        try { Uri uri = Uri.parse(url); String host = uri.getHost(); urlBar.setText(host != null ? host : url); }
        catch (Exception e) { urlBar.setText(url); }
        updateNavButtons();
    }

    @Override public void onProgress(int p) {
        if (p >= 100) progress.setVisibility(View.GONE);
        else { progress.setVisibility(View.VISIBLE); progress.setProgress(p); }
    }
    @Override public void onPageFinished(String url) { progress.setVisibility(View.GONE); updateNavButtons(); updateBookmarkIcon(url); }
    @Override public void onTitleChanged(String title) { currentTitle = title == null ? "" : title; }
    @Override public void onHome() { isHome = true; homeOverlay.setVisibility(View.VISIBLE); }
    @Override public void showToast(String msg) { toast(msg); }

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
}
