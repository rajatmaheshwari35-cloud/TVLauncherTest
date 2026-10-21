package com.example.tvlaunchertest;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apple TV style home screen, version 2.
 *
 * Rows used for D-pad navigation:
 *   ROW_TOP  = the three pills (Inputs, Wi-Fi, Settings)
 *   ROW_DOCK = the four dock apps
 *   1, 2, 3… = rows of the 4-column app grid below the dock
 */
public class MainActivity extends Activity {

    // ---- Dock: exactly these four, in this order ----
    private static final String[] DOCK_PACKAGES = {
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.android.tv.settings"
    };
    private static final String[] DOCK_NAMES = { "YouTube", "Netflix", "Prime Video", "Settings" };

    private static final String LIVE_TV_PACKAGE = "com.android.tv";
    private static final int COLUMNS = 4;
    private static final int ROW_TOP = -1;
    private static final int ROW_DOCK = 0;

    // ---- Colours ----
    private static final int BG = Color.BLACK;
    private static final int CARD = 0xFF1A1A1F;
    private static final int DOCK_BAND = 0xFF111115;
    private static final int TEXT = 0xFFF5F5F7;
    private static final int MUTED = 0xFF8E8E93;
    private static final int PILL = 0x24FFFFFF;
    private static final int PILL_STROKE = 0x1AFFFFFF;
    private static final int DEFAULT_GLOW = 0xFF5E6AD2;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ScrollView scroll;
    private LinearLayout topSection;
    private LinearLayout gridSection;
    private GridLayout grid;
    private GlassView glass;
    private TextView greeting, clock, date, dockTitle, wifiPill;
    private View glow;

    private final List<View> pills = new ArrayList<>();
    private final List<View> dockTiles = new ArrayList<>();
    private final List<View> gridTiles = new ArrayList<>();
    private final Map<View, TextView> gridLabels = new HashMap<>();
    private final Map<String, Integer> glowCache = new HashMap<>();

    private View lastFocused;
    private int lastDockCol = 0;
    private int lastPill = 0;
    private boolean appsDirty = false;
    private PopupWindow inputsPopup;
    private ConnectivityManager.NetworkCallback netCallback;

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        buildUi();
        loadAppsAsync();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appsDirty) {
            appsDirty = false;
            loadAppsAsync();
        }
        updateClock();
        startNetworkWatch();
        restoreFocus();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(clockTick);
        stopNetworkWatch();
        if (inputsPopup != null) inputsPopup.dismiss();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: go back to the dock.
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && !dockTiles.isEmpty()) {
            focus(dockTiles.get(lastDockCol));
        }
    }

    @Override
    public void onBackPressed() {
        // A home screen never closes itself. Back returns focus to the dock.
        if (!dockTiles.isEmpty()) focus(dockTiles.get(lastDockCol));
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (hasWindowFocus()) {
                appsDirty = false;
                loadAppsAsync();
            }
        }
    };

    // =====================================================================
    // Layout
    // =====================================================================

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        scroll = new ScrollView(this) {
            // We scroll ourselves; stop ScrollView jumping on every focus change.
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);

        topSection = new LinearLayout(this);
        topSection.setOrientation(LinearLayout.VERTICAL);
        topSection.setPadding(dp(48), dp(28), dp(48), dp(26));
        topSection.setClipChildren(false);
        topSection.setClipToPadding(false);
        content.addView(topSection, new LinearLayout.LayoutParams(-1, dp(540)));

        buildTopBar();
        topSection.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        buildDockTitle();
        buildDock();

        gridSection = new LinearLayout(this);
        gridSection.setOrientation(LinearLayout.VERTICAL);
        gridSection.setPadding(dp(48), dp(8), dp(48), dp(260));
        gridSection.setClipChildren(false);
        gridSection.setClipToPadding(false);
        TextView allApps = text("All apps", 15, MUTED, "sans-serif-medium");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.bottomMargin = dp(14);
        ap.leftMargin = dp(4);
        gridSection.addView(allApps, ap);

        grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        gridSection.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        content.addView(gridSection, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        glass = new GlassView(this, root);
        root.addView(glass, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        // The top section fills exactly one screen, so the grid starts hidden below the dock.
        scroll.post(() -> {
            int h = scroll.getHeight();
            if (h > 0) {
                topSection.getLayoutParams().height = h;
                topSection.requestLayout();
            }
        });
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> glass.invalidate());
    }

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.TOP);
        bar.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text("", 15, MUTED, "sans-serif-light");
        clock = text("", 54, TEXT, "sans-serif-thin");
        clock.setIncludeFontPadding(false);
        date = text("", 15, MUTED, "sans-serif-light");
        left.addView(greeting);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(2);
        cp.bottomMargin = dp(2);
        left.addView(clock, cp);
        left.addView(date);
        bar.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setClipChildren(false);
        right.setPadding(0, dp(6), 0, 0);

        TextView inputs = pill("Inputs");
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi");
        wifiPill.setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        TextView settings = pill("Settings");
        settings.setOnClickListener(v -> openSettings(Settings.ACTION_SETTINGS));

        TextView[] all = { inputs, wifiPill, settings };
        for (int i = 0; i < all.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(34));
            if (i > 0) p.leftMargin = dp(10);
            right.addView(all[i], p);
            registerFocusable(all[i], ROW_TOP, i);
            pills.add(all[i]);
        }
        bar.addView(right, new LinearLayout.LayoutParams(-2, -2));
        topSection.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildDockTitle() {
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);

        glow = new View(this);
        // A soft round glow, stretched sideways into an ellipse, so it has no hard edges.
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(280), dp(280));
        gp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        gp.leftMargin = dp(10);
        gp.topMargin = dp(10);
        glow.setScaleX(1.6f);
        holder.addView(glow, gp);
        setGlow(DEFAULT_GLOW);

        dockTitle = text("", 34, TEXT, "sans-serif-light");
        dockTitle.setSingleLine(true);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.START | Gravity.BOTTOM;
        tp.leftMargin = dp(16);
        tp.bottomMargin = dp(10);
        holder.addView(dockTitle, tp);

        topSection.addView(holder, new LinearLayout.LayoutParams(-1, dp(72)));
    }

    private void buildDock() {
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setPadding(dp(14), dp(14), dp(14), dp(14));
        band.setBackground(rounded(DOCK_BAND, dp(28), 0, 0));
        band.setClipChildren(false);
        band.setClipToPadding(false);
        for (int i = 0; i < DOCK_PACKAGES.length; i++) {
            FrameLayout tile = card();
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(108), 1f);
            if (i > 0) p.leftMargin = dp(14);
            band.addView(tile, p);
            registerFocusable(tile, ROW_DOCK, i);
            dockTiles.add(tile);
        }
        topSection.addView(band, new LinearLayout.LayoutParams(-1, -2));
    }

    // =====================================================================
    // Apps
    // =====================================================================

    /** Everything needed to draw one app tile, prepared off the main thread. */
    private static class AppEntry {
        ResolveInfo ri;
        String label;
        Drawable banner;
        Drawable icon;
        int glow = DEFAULT_GLOW;
    }

    private int loadGeneration = 0;

    private List<ResolveInfo> leanbackApps() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, 0);
        return list == null ? new ArrayList<>() : list;
    }

    /**
     * Reads the app list, names, banners and glow colours on a background thread,
     * then builds the tiles on the main thread. The empty layout (clock, pills, dock band)
     * appears immediately, so the home screen shows up faster after boot.
     */
    private void loadAppsAsync() {
        final int gen = ++loadGeneration;
        final PackageManager pm = getPackageManager();
        final String self = getPackageName();
        new Thread(() -> {
            List<ResolveInfo> apps = leanbackApps();
            AppEntry[] dock = new AppEntry[DOCK_PACKAGES.length];
            List<AppEntry> rest = new ArrayList<>();
            for (ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self)) continue;
                int dockIndex = -1;
                for (int d = 0; d < DOCK_PACKAGES.length; d++) if (DOCK_PACKAGES[d].equals(pkg)) dockIndex = d;
                AppEntry e = new AppEntry();
                e.ri = ri;
                try { e.label = String.valueOf(ri.loadLabel(pm)); } catch (Exception ex) { e.label = pkg; }
                e.banner = banner(ri, pm);
                if (e.banner == null) {
                    try { e.icon = ri.loadIcon(pm); } catch (Exception ignored) { }
                }
                if (dockIndex >= 0) {
                    if (e.banner != null) e.glow = sampleColor(pkg, e.banner);
                    dock[dockIndex] = e;
                } else {
                    rest.add(e);
                }
            }
            Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
            handler.post(() -> {
                if (gen != loadGeneration || isFinishing()) return;
                applyDock(dock);
                applyGrid(rest);
                restoreFocus();
            });
        }, "launcher-apps").start();
    }

    private void applyDock(AppEntry[] dock) {
        for (int i = 0; i < DOCK_PACKAGES.length; i++) {
            FrameLayout tile = (FrameLayout) dockTiles.get(i);
            tile.removeAllViews();
            AppEntry e = dock[i];
            String name = DOCK_NAMES[i];
            fillCard(tile, e == null ? null : e.banner, e == null ? null : e.icon, name, e == null);
            info(tile).name = name;
            info(tile).glow = e == null ? DEFAULT_GLOW : e.glow;
            final ResolveInfo target = e == null ? null : e.ri;
            final int index = i;
            tile.setOnClickListener(v -> {
                if (target != null) launch(target);
                else if (index == 3) openSettings(Settings.ACTION_SETTINGS);
                else toast(name + " is not installed");
            });
        }
        View f = getCurrentFocus();
        int col = f != null && dockTiles.contains(f) ? dockTiles.indexOf(f) : lastDockCol;
        showDockTitle(dockTiles.get(col));
    }

    private void applyGrid(List<AppEntry> rest) {
        if (gridTiles.contains(lastFocused)) lastFocused = null;
        grid.removeAllViews();
        gridTiles.clear();
        gridLabels.clear();

        for (int i = 0; i < rest.size(); i++) {
            final AppEntry e = rest.get(i);
            String name = e.label;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);

            FrameLayout tile = card();
            fillCard(tile, e.banner, e.icon, name, false);
            tile.setOnClickListener(v -> launch(e.ri));
            cell.addView(tile, new LinearLayout.LayoutParams(-1, dp(108)));

            TextView label = text(name, 13, TEXT, "sans-serif");
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setAlpha(0f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(24));
            lp.topMargin = dp(6);
            cell.addView(label, lp);

            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0;
            p.height = GridLayout.LayoutParams.WRAP_CONTENT;
            p.columnSpec = GridLayout.spec(i % COLUMNS, 1f);
            p.rowSpec = GridLayout.spec(i / COLUMNS);
            int m = dp(7);
            p.setMargins(m, 0, m, dp(12));
            grid.addView(cell, p);

            registerFocusable(tile, 1 + i / COLUMNS, i % COLUMNS);
            gridTiles.add(tile);
            gridLabels.put(tile, label);
        }
    }

    private static ResolveInfo find(List<ResolveInfo> apps, String pkg) {
        for (ResolveInfo ri : apps) if (ri.activityInfo.packageName.equals(pkg)) return ri;
        return null;
    }

    private Drawable banner(ResolveInfo ri, PackageManager pm) {
        try {
            Drawable b = ri.activityInfo.loadBanner(pm);
            if (b == null) b = ri.activityInfo.applicationInfo.loadBanner(pm);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private void launch(ResolveInfo ri) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("Could not open " + ri.loadLabel(getPackageManager()));
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                toast("Settings could not be opened");
            }
        }
    }

    // =====================================================================
    // Inputs dropdown (TV input framework)
    // =====================================================================

    private void showInputs(View anchor) {
        if (inputsPopup != null && inputsPopup.isShowing()) {
            inputsPopup.dismiss();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackground(rounded(0xF01C1C21, dp(18), dp(1), 0x26FFFFFF));

        List<TextView> items = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            try { inputs.addAll(tim.getTvInputList()); } catch (Exception ignored) { }
        }

        // Hardware HDMI ports first (skip CEC child entries, but use their device name).
        List<TvInputInfo> hdmi = new ArrayList<>();
        List<TvInputInfo> other = new ArrayList<>();
        for (TvInputInfo in : inputs) {
            if (in.getParentId() != null) continue;
            int t = in.getType();
            if (t == TvInputInfo.TYPE_HDMI) hdmi.add(in);
            else if (t == TvInputInfo.TYPE_COMPOSITE || t == TvInputInfo.TYPE_COMPONENT
                || t == TvInputInfo.TYPE_SVIDEO || t == TvInputInfo.TYPE_VGA
                || t == TvInputInfo.TYPE_SCART || t == TvInputInfo.TYPE_DVI) other.add(in);
        }
        Collections.sort(hdmi, (a, b) -> label(a).compareToIgnoreCase(label(b)));

        for (int k = 0; k < hdmi.size(); k++) {
            TvInputInfo in = hdmi.get(k);
            String name = label(in);
            if (name.contains("/")) name = "HDMI " + (k + 1);
            for (TvInputInfo child : inputs) {
                if (in.getId().equals(child.getParentId())) {
                    String c = label(child);
                    if (!c.isEmpty() && !c.contains("/") && !c.equals(name)) name = name + "  ·  " + c;
                    break;
                }
            }
            items.add(inputItem(name, in.getId()));
        }
        for (TvInputInfo in : other) {
            String name = label(in);
            if (name.contains("/")) name = in.getType() == TvInputInfo.TYPE_COMPOSITE ? "AV" : "Input";
            items.add(inputItem(name, in.getId()));
        }

        TextView live = popupItem("Live TV");
        live.setOnClickListener(v -> {
            dismissInputs();
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(LIVE_TV_PACKAGE);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(LIVE_TV_PACKAGE);
            if (i == null) { toast("Live TV app not found"); return; }
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { toast("Live TV could not be opened"); }
        });
        items.add(live);

        for (TextView t : items) box.addView(t, new LinearLayout.LayoutParams(dp(240), dp(40)));

        inputsPopup = new PopupWindow(box, -2, -2, true);
        inputsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        inputsPopup.setOutsideTouchable(true);
        inputsPopup.setElevation(dp(12));
        int xoff = anchor.getWidth() - dp(256);
        inputsPopup.showAsDropDown(anchor, xoff, dp(10));
        items.get(0).requestFocus();
    }

    private void dismissInputs() {
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    private String label(TvInputInfo in) {
        try {
            CharSequence c = in.loadLabel(this);
            return c == null ? in.getId() : c.toString();
        } catch (Exception e) {
            return in.getId();
        }
    }

    private TextView inputItem(String name, String inputId) {
        TextView t = popupItem(name);
        t.setOnClickListener(v -> {
            dismissInputs();
            Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent withPkg = new Intent(i).setPackage(LIVE_TV_PACKAGE);
            try {
                if (withPkg.resolveActivity(getPackageManager()) != null) startActivity(withPkg);
                else startActivity(i);
            } catch (Exception e) {
                toast("Could not switch to " + name);
            }
        });
        return t;
    }

    private TextView popupItem(String name) {
        TextView t = text(name, 15, TEXT, "sans-serif");
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setSingleLine(true);
        t.setFocusable(true);
        t.setBackground(rounded(Color.TRANSPARENT, dp(12), 0, 0));
        t.setOnFocusChangeListener((v, has) ->
            v.setBackground(rounded(has ? 0x33FFFFFF : Color.TRANSPARENT, dp(12), 0, 0)));
        noDefaultHighlight(t);
        return t;
    }

    // =====================================================================
    // Focus and D-pad
    // =====================================================================

    private void registerFocusable(View v, int row, int col) {
        v.setFocusable(true);
        TileInfo ti = info(v);
        ti.row = row;
        ti.col = col;
        noDefaultHighlight(v);
        v.setOnFocusChangeListener((view, has) -> onTileFocus(view, has));
    }

    private void onTileFocus(View v, boolean has) {
        int row = info(v).row;
        boolean isPill = row == ROW_TOP;
        float scale = has ? (isPill ? 1.05f : 1.08f) : 1f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(160)
            .setInterpolator(new DecelerateInterpolator())
            .setUpdateListener(a -> glass.invalidate()).start();

        View lift = row >= 1 ? (View) v.getParent() : v;
        lift.setZ(has ? dp(4) : 0);

        TextView label = gridLabels.get(v);
        if (label != null) label.animate().alpha(has ? 1f : 0f).setDuration(150).start();

        if (!has) return;
        lastFocused = v;
        glass.moveTo(v, isPill ? -1 : dp(16));

        if (row == ROW_TOP) {
            lastPill = info(v).col;
            scroll.smoothScrollTo(0, 0);
            dockTitle.animate().alpha(0.35f).setDuration(200).start();
        } else if (row == ROW_DOCK) {
            lastDockCol = info(v).col;
            scroll.smoothScrollTo(0, 0);
            showDockTitle(v);
        } else {
            View cell = (View) v.getParent();
            int y = gridSection.getTop() + grid.getTop() + cell.getTop() - dp(120);
            scroll.smoothScrollTo(0, Math.max(0, y));
        }
    }

    private void showDockTitle(View tile) {
        TileInfo ti = info(tile);
        dockTitle.setText(ti.name == null ? "" : ti.name);
        dockTitle.animate().alpha(1f).setDuration(200).start();
        int c = ti.glow;
        setGlow(c);
        dockTitle.setShadowLayer(dp(18), 0, 0, (c & 0x00FFFFFF) | 0x66000000);
    }

    private void restoreFocus() {
        View target = lastFocused;
        if (target == null || !target.isAttachedToWindow()) {
            target = dockTiles.isEmpty() ? null : dockTiles.get(lastDockCol);
        }
        final View t = target;
        if (t != null) scroll.post(() -> focus(t));
    }

    private void focus(View v) {
        if (v == null) return;
        if (v.isFocused()) onTileFocus(v, true);
        else v.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View cur = getCurrentFocus();
                if (cur == null || !(cur.getTag() instanceof TileInfo)) {
                    restoreFocus();
                    return true;
                }
                move(cur, code);
                return true; // always consume: focus never wanders on its own
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void move(View cur, int code) {
        int row = info(cur).row;
        int col = info(cur).col;
        View next = null;

        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int nc = col + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            if (row == ROW_TOP) next = at(pills, nc);
            else if (row == ROW_DOCK) next = at(dockTiles, nc);
            else {
                int idx = (row - 1) * COLUMNS + nc;
                if (nc >= 0 && nc < COLUMNS) next = at(gridTiles, idx);
            }
        } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (row == ROW_DOCK) next = at(pills, lastPill);
            else if (row == 1) next = at(dockTiles, col);
            else if (row > 1) next = at(gridTiles, (row - 2) * COLUMNS + col);
        } else { // DOWN
            if (row == ROW_TOP) next = at(dockTiles, lastDockCol);
            else if (row == ROW_DOCK) {
                if (!gridTiles.isEmpty()) next = gridTiles.get(Math.min(col, gridTiles.size() - 1));
            } else {
                int start = row * COLUMNS;
                if (start < gridTiles.size()) next = gridTiles.get(Math.min(start + col, gridTiles.size() - 1));
            }
        }
        if (next != null) next.requestFocus();
    }

    /** Position and dock data kept on each focusable tile. */
    private static class TileInfo {
        int row, col;
        String name;
        int glow = DEFAULT_GLOW;
    }

    private static TileInfo info(View v) {
        Object t = v.getTag();
        if (t instanceof TileInfo) return (TileInfo) t;
        TileInfo ti = new TileInfo();
        v.setTag(ti);
        return ti;
    }

    private static View at(List<View> list, int i) {
        return i >= 0 && i < list.size() ? list.get(i) : null;
    }

    // =====================================================================
    // Clock and network
    // =====================================================================

    private final Runnable clockTick = this::updateClock;

    private void updateClock() {
        Date now = new Date();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon"
            : h < 22 ? "Good evening" : "Good night";
        greeting.setText(g);
        clock.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        handler.removeCallbacks(clockTick);
        long delay = 60000 - (System.currentTimeMillis() % 60000) + 50;
        handler.postDelayed(clockTick, delay);
    }

    private void startNetworkWatch() {
        updateWifiPill();
        if (Build.VERSION.SDK_INT < 24) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || netCallback != null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { handler.post(MainActivity.this::updateWifiPill); }
            @Override public void onLost(Network n) { handler.post(MainActivity.this::updateWifiPill); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                handler.post(MainActivity.this::updateWifiPill);
            }
        };
        try { cm.registerDefaultNetworkCallback(netCallback); } catch (Exception e) { netCallback = null; }
    }

    private void stopNetworkWatch() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
        }
        netCallback = null;
    }

    private void updateWifiPill() {
        String text = "Wi-Fi";
        if (Build.VERSION.SDK_INT < 23) { wifiPill.setText(text); return; }
        text = "Offline";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = "Ethernet";
            } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                text = "Wi-Fi";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                String ssid = info == null ? null : info.getSSID();
                if (ssid != null) {
                    ssid = ssid.replace("\"", "");
                    if (!ssid.isEmpty() && !ssid.contains("unknown ssid")) text = ssid;
                }
            }
        } catch (Exception ignored) { }
        wifiPill.setText(text);
    }

    // =====================================================================
    // Small view helpers
    // =====================================================================

    private FrameLayout card() {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(rounded(CARD, dp(16), 0, 0));
        f.setClipToOutline(true);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        return f;
    }

    private void fillCard(FrameLayout tile, Drawable bannerArt, Drawable icon, String name, boolean missing) {
        if (bannerArt != null) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(bannerArt);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        if (icon != null) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(img, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        TextView t = text(missing ? name + "\nnot installed" : name, 13, missing ? MUTED : TEXT, "sans-serif");
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(6);
        box.addView(t, lp);
        tile.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView pill(String label) {
        TextView p = text(label, 13, TEXT, "sans-serif-medium");
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(18), 0, dp(18), 0);
        p.setSingleLine(true);
        p.setBackground(rounded(PILL, dp(17), dp(1), PILL_STROKE));
        return p;
    }

    private TextView text(String value, int sp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setFocusable(false);
        return t;
    }

    private GradientDrawable rounded(int fill, int radius, int strokeW, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeW > 0) d.setStroke(strokeW, strokeColor);
        return d;
    }

    private void setGlow(int color) {
        int rgb = color & 0x00FFFFFF;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(140));
        g.setColors(new int[] { rgb | 0x50000000, rgb | 0x1E000000, rgb });
        glow.setBackground(g);
    }

    /** Average the saturated pixels of a banner and brighten the result for the glow. */
    private synchronized int sampleColor(String key, Drawable d) {
        Integer cached = glowCache.get(key);
        if (cached != null) return cached;
        int result = DEFAULT_GLOW;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float r = 0, g = 0, b = 0, w = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 14; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = bm.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    float weight = hsv[1] * hsv[2] + 0.02f;
                    r += Color.red(p) * weight;
                    g += Color.green(p) * weight;
                    b += Color.blue(p) * weight;
                    w += weight;
                }
            }
            bm.recycle();
            if (w > 0.5f) {
                int avg = Color.rgb((int) (r / w), (int) (g / w), (int) (b / w));
                Color.colorToHSV(avg, hsv);
                if (hsv[1] > 0.12f) {
                    hsv[1] = Math.max(hsv[1], 0.55f);
                    hsv[2] = 1f;
                    result = Color.HSVToColor(hsv);
                }
            }
        } catch (Exception ignored) { }
        glowCache.put(key, result);
        return result;
    }

    private void noDefaultHighlight(View v) {
        if (Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
            getResources().getDisplayMetrics()));
    }

    // =====================================================================
    // One glass highlight that springs from tile to tile
    // =====================================================================

    private static class GlassView extends View {
        private final View root;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF from = new RectF();
        private final RectF now = new RectF();
        private final RectF tmp = new RectF();
        private View target;
        private float radius;   // -1 = fully rounded pill
        private float fraction = 1f;
        private boolean hasFrom = false;
        private ValueAnimator anim;

        GlassView(Context c, View root) {
            super(c);
            this.root = root;
            float d = c.getResources().getDisplayMetrics().density;
            fill.setColor(0x26FFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f * d);
            stroke.setColor(0x59FFFFFF);
            setFocusable(false);
            setClickable(false);
            setWillNotDraw(false);
        }

        void moveTo(View v, float cornerRadius) {
            if (hasFrom) from.set(now);
            target = v;
            radius = cornerRadius;
            if (anim != null) anim.cancel();
            if (!hasFrom) {
                fraction = 1f;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(280);
            anim.setInterpolator(new OvershootInterpolator(1.15f));
            anim.addUpdateListener(a -> {
                fraction = (Float) a.getAnimatedValue();
                invalidate();
            });
            fraction = 0f;
            anim.start();
        }

        private boolean targetRect(RectF out) {
            if (target == null || !target.isAttachedToWindow()) return false;
            float x = 0, y = 0;
            View v = target;
            while (v != root && v != null) {
                x += v.getLeft() + v.getTranslationX();
                y += v.getTop() + v.getTranslationY();
                ViewParent p = v.getParent();
                if (!(p instanceof View)) return false;
                View pv = (View) p;
                x -= pv.getScrollX();
                y -= pv.getScrollY();
                v = pv;
            }
            float w = target.getWidth(), h = target.getHeight();
            float sx = target.getScaleX(), sy = target.getScaleY();
            float cx = x + target.getPivotX(), cy = y + target.getPivotY();
            out.set(cx - target.getPivotX() * sx, cy - target.getPivotY() * sy,
                cx + (w - target.getPivotX()) * sx, cy + (h - target.getPivotY()) * sy);
            return w > 0 && h > 0;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!targetRect(tmp)) return;
            if (!hasFrom || fraction >= 1f) {
                now.set(tmp);
            } else {
                float f = fraction;
                now.set(from.left + (tmp.left - from.left) * f,
                    from.top + (tmp.top - from.top) * f,
                    from.right + (tmp.right - from.right) * f,
                    from.bottom + (tmp.bottom - from.bottom) * f);
            }
            hasFrom = true;
            float r = radius < 0 ? now.height() / 2f : radius * target.getScaleX();
            c.drawRoundRect(now, r, r, fill);
            c.drawRoundRect(now, r, r, stroke);
        }
    }
}
