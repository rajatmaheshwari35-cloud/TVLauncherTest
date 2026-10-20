
package com.example.tvlaunchertest;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private final int BG = Color.BLACK;
    private final int MUTED = 0xFFAAAAAA;
    private final int ACCENT = 0xFF34343A;
    private final Handler handler = new Handler();
    private TextView clock;
    private LinearLayout root;
    private View focusedTile;

    private final String[] names = {
        "YouTube", "Netflix", "Prime Video", "Settings",
        "Apps", "Live TV", "HDMI / Inputs", "Cast",
        "Screen Share", "Gallery", "Browser", "Play Store"
    };

    private final String[] packages = {
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "",
        "", "", "", "", "", "", "", "com.android.vending"
    };

    private final String[] symbols = {
        "▶", "N", "prime", "⚙",
        "▦", "TV", "▣", "⌁",
        "▤", "▧", "◎", "＋"
    };

    private final Runnable clockUpdater = new Runnable() {
        @Override public void run() {
            if (clock != null) {
                clock.setText(new SimpleDateFormat(
                    "EEE, d MMM   •   h:mm a", Locale.getDefault()
                ).format(new Date()));
            }
            handler.postDelayed(this, 30000);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        buildHome();
        handler.post(clockUpdater);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setFocusable(false);
        return t;
    }

    private TextView tile(String label, String symbol, int index, boolean dock) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setPadding(8, 10, 8, 8);
        card.setBackground(shape(0xFF17171B, 20));

        TextView icon = text(symbol, dock ? 26 : 30, Color.WHITE, true);
        if (index == 0) icon.setTextColor(0xFFFF4242);
        if (index == 1) icon.setTextColor(0xFFE50914);
        if (index == 2) icon.setTextColor(0xFF55BFFF);

        card.addView(icon, new LinearLayout.LayoutParams(-1, 48));
        TextView caption = text(label, 12, 0xFFE0E0E0, false);
        caption.setPadding(0, 4, 0, 0);
        card.addView(caption, new LinearLayout.LayoutParams(-1, -2));

        card.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(shape(hasFocus ? 0xFF46464F : 0xFF17171B,
                hasFocus ? 24 : 20));
            v.setScaleX(hasFocus ? 1.08f : 1f);
            v.setScaleY(hasFocus ? 1.08f : 1f);
            v.setElevation(hasFocus ? 10f : 0f);
            if (hasFocus) focusedTile = v;
        });

        card.setOnClickListener(v -> launchTile(index, label));
        returnHolder.put(card, label);
        return card;
    }

    private final java.util.Map<View, String> returnHolder =
        new java.util.HashMap<>();

    private void launchTile(int index, String label) {
        if (index == 3) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Settings could not be opened", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (index == 5 || index == 6) {
            Toast.makeText(this,
                "Use the TV's original Input/Source menu for now",
                Toast.LENGTH_LONG).show();
            return;
        }
        String pkg = packages[index];
        if (pkg != null && !pkg.isEmpty()) {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) startActivity(launch);
                else Toast.makeText(this, label + " is not available", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not open " + label, Toast.LENGTH_SHORT).show();
            }
        } else {
            Intent apps = new Intent(Intent.ACTION_MAIN);
            apps.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            try {
                startActivity(apps);
            } catch (Exception e) {
                Toast.makeText(this, label + " is not configured yet", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void buildHome() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(38, 20, 38, 18);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout welcome = new LinearLayout(this);
        welcome.setOrientation(LinearLayout.VERTICAL);
        TextView greeting = text("Good evening", 25, Color.WHITE, true);
        greeting.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        welcome.addView(greeting);
        clock = text("", 13, MUTED, false);
        clock.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        welcome.addView(clock);
        top.addView(welcome, new LinearLayout.LayoutParams(0, -2, 1));

        top.addView(pill("INPUTS"), new LinearLayout.LayoutParams(-2, 42));
        top.addView(pill("NETWORK"), new LinearLayout.LayoutParams(-2, 42));
        top.addView(pill("⚙"), new LinearLayout.LayoutParams(-2, 42));
        root.addView(top);

        TextView heading = text("Your apps", 17, 0xFFBDBDBD, true);
        heading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, 34);
        hp.topMargin = 12;
        root.addView(heading, hp);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        for (int i = 4; i < names.length; i++) {
            TextView card = tile(names[i], symbols[i], i, false);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = 0;
            p.height = 94;
            p.columnSpec = GridLayout.spec((i - 4) % 4, 1f);
            p.setMargins(7, 4, 7, 5);
            grid.addView(card, p);
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(grid, gp);

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(8, 8, 8, 8);
        dock.setBackground(shape(0xFF111114, 26));
        for (int i = 0; i < 4; i++) {
            final int index = i;
            TextView card = tile(names[i], symbols[i], index, true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 76, 1);
            p.setMargins(5, 0, 5, 0);
            dock.addView(card, p);
        }
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, 92);
        dp.topMargin = 6;
        root.addView(dock, dp);

        setContentView(root);
        root.post(() -> {
            if (root.getChildCount() > 0) {
                View first = ((LinearLayout) root.getChildAt(root.getChildCount() - 1))
                    .getChildAt(0);
                first.requestFocus();
            }
        });
    }

    private TextView pill(String label) {
        TextView p = text(label, 11, Color.WHITE, true);
        p.setPadding(14, 0, 14, 0);
        p.setFocusable(true);
        p.setBackground(shape(0xFF242428, 22));
        p.setOnClickListener(v -> {
            if (label.equals("⚙")) launchTile(3, "Settings");
            else Toast.makeText(this, label + " details will be added next",
                Toast.LENGTH_SHORT).show();
        });
        p.setOnFocusChangeListener((v, focus) ->
            v.setBackground(shape(focus ? 0xFF505058 : 0xFF242428, 22)));
        return p;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(clockUpdater);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
