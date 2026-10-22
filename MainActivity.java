package com.minimal.blacklauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    static class AppItem {
        final String label, pkg, cls;
        AppItem(String l, String p, String c) { label = l; pkg = p; cls = c; }
        String key() { return pkg + "/" + cls; }
        @Override public String toString() { return label; }
    }

    interface NameCallback { void onName(String name); }
    interface IntCallback { void on(int value); }

    static final String[] STYLES = { "Digital", "Stacked digits", "Round classic", "Round minimal", "Round dots", "Round numbers" };
    static final String[] THICK = { "Thin", "Light", "Regular", "Medium", "Bold" };
    static final String[] THICK_FONT = { "sans-serif-thin", "sans-serif-light", "sans-serif", "sans-serif-medium", "sans-serif-black" };
    static final float[] THICK_DP = { 1f, 1.6f, 2.4f, 3.4f, 4.8f };
    static final String[] DATE_FMT = { "EEE, d MMM", "EEEE, d MMMM", "d MMMM yyyy", "dd/MM/yyyy", "MMM d", "Hide date" };
    static final String[] ALIGN = { "Left", "Center", "Right" };
    static final String[] BLOCKS = { "clock", "note", "pinned", "folders", "recents" };
    static final String[] BLOCK_NAMES = { "Clock", "Quick note", "Pinned apps", "Folders", "Recent apps" };
    static final int[] BURN_DX = { -4, 0, 4, 0, -3, 3 };
    static final int[] BURN_DY = { 0, 4, 0, -4, 3, -3 };

    private SharedPreferences prefs;
    private List<AppItem> allApps = new ArrayList<AppItem>();
    private final ArrayList<AppItem> shown = new ArrayList<AppItem>();

    private FrameLayout home;
    private DragBlock clockBlock, pinnedBox, foldersBox, searchBlock, noteBox, recentsBox;
    private TextView noteText;
    private final HashMap<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private LinearLayout drawer, editBar;
    private EditText search;
    private ArrayAdapter<AppItem> adapter;
    private GestureDetector detector;
    private boolean editMode = false;
    private boolean arrangeQueued = false;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private float dpf(float v) { return v * getResources().getDisplayMetrics().density; }

    // =====================================================================
    // storage
    // =====================================================================
    private Set<String> hidden() { return new HashSet<String>(prefs.getStringSet("hidden", new HashSet<String>())); }
    private List<String> pinned() {
        List<String> out = new ArrayList<String>();
        for (String s : prefs.getString("pinned", "").split("\n")) if (s.length() > 0) out.add(s);
        return out;
    }
    private void savePinned(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append("\n");
        prefs.edit().putString("pinned", sb.toString()).apply();
    }
    private void saveHidden(Set<String> s) { prefs.edit().putStringSet("hidden", s).apply(); }

    // =====================================================================
    // lifecycle
    // =====================================================================
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                @Override public void onBackInvoked() { handleBack(); }
            });
        }
        detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { lockScreen(); return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 != null && e1.getY() - e2.getY() > dp(120) && Math.abs(vy) > 800) { openDrawer(); return true; }
                return false;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (editMode || home.getVisibility() != View.VISIBLE) return;
                if (hitsBlock(e.getRawX(), e.getRawY())) return;
                enterEdit();
            }
        });
    }

    @Override protected void onStart() { super.onStart(); loadApps(); renderPinned(); filter(); applyBurnIn(); }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (editMode) exitEdit();
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    private void handleBack() {
        if (editMode) exitEdit();
        else if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    @Override public void onBackPressed() { handleBack(); }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!editMode && home.getVisibility() == View.VISIBLE) detector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // =====================================================================
    // draggable block
    // =====================================================================
    class DragBlock extends LinearLayout {
        final String id;
        float startX, startY, downX, downY;

        DragBlock(Context c, String id) {
            super(c);
            this.id = id;
            setOrientation(LinearLayout.VERTICAL);
            setPadding(dp(8), dp(8), dp(8), dp(8));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) { return editMode; }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (!editMode) return super.onTouchEvent(ev);
            int W = home.getWidth(), H = home.getHeight();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = getX(); startY = getY(); downX = ev.getRawX(); downY = ev.getRawY();
                    bringToFront();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float nx = startX + ev.getRawX() - downX;
                    float ny = startY + ev.getRawY() - downY;
                    nx = Math.max(0, Math.min(nx, W - getWidth()));
                    ny = Math.max(0, Math.min(ny, H - getHeight()));
                    setX(nx); setY(ny);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (W > 0 && H > 0) prefs.edit().putString("pos_" + id, (getX() / W) + "," + (getY() / H)).apply();
                    return true;
            }
            return true;
        }
    }

    // =====================================================================
    // layout engine: stacked by order, unless the user dragged a block
    // =====================================================================
    private List<String> order() {
        List<String> out = new ArrayList<String>();
        for (String s : prefs.getString("order", "clock,note,pinned,folders,recents").split(",")) {
            for (String b : BLOCKS) if (b.equals(s) && !out.contains(s)) out.add(s);
        }
        for (String b : BLOCKS) if (!out.contains(b)) out.add(b);
        return out;
    }

    private DragBlock blockFor(String id) {
        if (id.equals("clock")) return clockBlock;
        if (id.equals("pinned")) return pinnedBox;
        if (id.equals("folders")) return foldersBox;
        if (id.equals("note")) return noteBox;
        if (id.equals("recents")) return recentsBox;
        return searchBlock;
    }

    private DragBlock[] allBlocks() {
        return new DragBlock[] { clockBlock, noteBox, pinnedBox, foldersBox, recentsBox, searchBlock };
    }

    private void setGravities() {
        int hg = hgravity();
        for (DragBlock b : allBlocks()) b.setGravity(hg);
    }

    private int hgravity() {
        int a = prefs.getInt("align", 0);
        return a == 1 ? Gravity.CENTER_HORIZONTAL : (a == 2 ? Gravity.END : Gravity.START);
    }

    private float alignX(View b) {
        int W = home.getWidth(), a = prefs.getInt("align", 0), m = dp(12);
        if (a == 1) return (W - b.getWidth()) / 2f;
        if (a == 2) return W - b.getWidth() - m;
        return m;
    }

    private boolean applySaved(DragBlock b) {
        String s = prefs.getString("pos_" + b.id, null);
        if (s == null) return false;
        try {
            String[] p = s.split(",");
            int W = home.getWidth(), H = home.getHeight();
            float x = Float.parseFloat(p[0]) * W, y = Float.parseFloat(p[1]) * H;
            b.setX(Math.max(0, Math.min(x, W - b.getWidth())));
            b.setY(Math.max(0, Math.min(y, H - b.getHeight())));
            return true;
        } catch (Exception e) { return false; }
    }

    private final Runnable arrangeRunnable = new Runnable() {
        @Override public void run() { arrangeQueued = false; arrange(); }
    };

    private void requestArrange() {
        if (!arrangeQueued) { arrangeQueued = true; home.post(arrangeRunnable); }
    }

    private void arrange() {
        int W = home.getWidth(), H = home.getHeight();
        if (W == 0 || H == 0) return;
        int y = dp(8), gap = dp(8);
        for (String id : order()) {
            DragBlock b = blockFor(id);
            if (b.getVisibility() == View.GONE) continue;
            if (!applySaved(b)) {
                b.setX(alignX(b));
                b.setY(y);
                y += b.getHeight() + gap;
            }
        }
        if (!applySaved(searchBlock)) {
            searchBlock.setX(alignX(searchBlock));
            searchBlock.setY(H - searchBlock.getHeight() - dp(8));
        }
    }

    private void resetPositions() {
        SharedPreferences.Editor e = prefs.edit();
        for (String id : BLOCKS) e.remove("pos_" + id);
        e.remove("pos_search");
        e.apply();
        requestArrange();
    }

    private boolean hitsBlock(float rx, float ry) {
        int[] loc = new int[2];
        for (DragBlock b : allBlocks()) {
            if (b.getVisibility() != View.VISIBLE) continue;
            b.getLocationOnScreen(loc);
            if (rx >= loc[0] && rx <= loc[0] + b.getWidth() && ry >= loc[1] && ry <= loc[1] + b.getHeight()) return true;
        }
        return false;
    }

    // =====================================================================
    // edit mode
    // =====================================================================
    private void enterEdit() {
        if (editMode) return;
        editMode = true;
        editBar.setVisibility(View.VISIBLE);
        GradientDrawable g;
        for (DragBlock b : allBlocks()) {
            if (b.getVisibility() != View.VISIBLE) continue;
            g = new GradientDrawable();
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1), 0xFF666666);
            g.setCornerRadius(dp(10));
            b.setBackground(g);
        }
    }

    private void exitEdit() {
        editMode = false;
        editBar.setVisibility(View.GONE);
        for (DragBlock b : allBlocks()) b.setBackground(null);
    }

    private TextView barButton(String label, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(14), dp(8), dp(14));
        t.setOnClickListener(c);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return t;
    }

    // =====================================================================
    // UI (code only: no layout XML, no libraries)
    // =====================================================================
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int l, t, r, b;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                    l = i.left; t = i.top; r = i.right; b = i.bottom;
                } else {
                    l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                    r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
                }
                v.setPadding(l, t, r, b);
                return in;
            }
        });

        home = new FrameLayout(this);
        home.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int orr, int ob) {
                if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) requestArrange();
            }
        });

        View.OnLayoutChangeListener blockListener = new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int orr, int ob) {
                if ((r - l) != (orr - ol) || (b - t) != (ob - ot)) requestArrange();
            }
        };
        clockBlock = new DragBlock(this, "clock");
        pinnedBox = new DragBlock(this, "pinned");
        foldersBox = new DragBlock(this, "folders");
        searchBlock = new DragBlock(this, "search");
        noteBox = new DragBlock(this, "note");
        recentsBox = new DragBlock(this, "recents");
        for (DragBlock b : allBlocks()) {
            home.addView(b, new FrameLayout.LayoutParams(-2, -2));
            b.addOnLayoutChangeListener(blockListener);
        }
        clockBlock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { enterEdit(); return true; }
        });

        TextView searchHint = new TextView(this);
        searchHint.setText("Search");
        searchHint.setTextSize(18f);
        searchHint.setTextColor(Color.GRAY);
        searchHint.setPadding(dp(4), dp(8), dp(4), dp(8));
        searchHint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(); }
        });
        searchBlock.addView(searchHint);

        noteText = new TextView(this);
        noteText.setTextSize(17f);
        noteText.setPadding(dp(4), dp(6), dp(4), dp(6));
        noteText.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(56));
        noteText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editNote(); }
        });
        noteBox.addView(noteText);
        renderNote();

        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setVisibility(View.GONE);
        drawer.setPadding(dp(20), dp(16), dp(20), dp(8));

        search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(Color.DKGRAY);
        search.setTextColor(Color.WHITE);
        search.setTextSize(20f);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(8), dp(12), dp(8), dp(12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) { filter(); }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (!shown.isEmpty()) launch(shown.get(0));
                return true;
            }
        });

        adapter = new ArrayAdapter<AppItem>(this, android.R.layout.simple_list_item_1, shown) {
            @Override public View getView(int p, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(p, v, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(20f);
                t.setPadding(dp(8), dp(12), 0, dp(12));
                if (prefs.getBoolean("icons", false)) {
                    Drawable ic = icon(getItem(p), 32);
                    t.setCompoundDrawables(ic, null, null, null);
                    t.setCompoundDrawablePadding(dp(14));
                } else {
                    t.setCompoundDrawables(null, null, null, null);
                }
                return t;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setBackgroundColor(Color.BLACK);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int i, long id) { launch(shown.get(i)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int i, long id) { showMenu(shown.get(i)); return true; }
        });
        drawer.addView(search, new LinearLayout.LayoutParams(-1, -2));
        drawer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.VERTICAL);
        editBar.setBackgroundColor(0xFF161616);
        editBar.setVisibility(View.GONE);
        TextView hint = new TextView(this);
        hint.setText("Drag any item to move it");
        hint.setTextColor(Color.GRAY);
        hint.setTextSize(13f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(barButton("Customize", new View.OnClickListener() {
            @Override public void onClick(View v) { showCustomize(); }
        }));
        row.addView(barButton("Auto arrange", new View.OnClickListener() {
            @Override public void onClick(View v) { resetPositions(); }
        }));
        row.addView(barButton("Done", new View.OnClickListener() {
            @Override public void onClick(View v) { exitEdit(); }
        }));
        editBar.addView(hint);
        editBar.addView(row);

        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        root.addView(editBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        buildClock();
    }

    // =====================================================================
    // clock
    // =====================================================================
    private TextClock textClock(String fmt, float size, Typeface tf, int color) {
        TextClock t = new TextClock(this);
        t.setFormat24Hour(fmt);
        t.setFormat12Hour(fmt);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void buildClock() {
        clockBlock.removeAllViews();
        int hg = hgravity();
        clockBlock.setGravity(hg);
        int style = prefs.getInt("style", 2);
        int scale = prefs.getInt("csize", 100);
        int thick = prefs.getInt("cthick", 1);
        boolean h24 = prefs.getBoolean("h24", true);
        Typeface tf = Typeface.create(THICK_FONT[thick], Typeface.NORMAL);
        View.OnClickListener alarm = new View.OnClickListener() {
            @Override public void onClick(View v) { openAlarm(); }
        };
        View.OnLongClickListener editLong = new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { enterEdit(); return true; }
        };

        if (style == 0) {
            TextClock t = textClock(h24 ? "HH:mm" : "h:mm", 64f * scale / 100f, tf, Color.WHITE);
            t.setOnClickListener(alarm); t.setOnLongClickListener(editLong);
            clockBlock.addView(t);
        } else if (style == 1) {
            TextClock t1 = textClock(h24 ? "HH" : "h", 76f * scale / 100f, tf, Color.WHITE);
            TextClock t2 = textClock("mm", 76f * scale / 100f, tf, Color.WHITE);
            t1.setOnClickListener(alarm); t1.setOnLongClickListener(editLong);
            t2.setOnClickListener(alarm); t2.setOnLongClickListener(editLong);
            clockBlock.addView(t1);
            clockBlock.addView(t2);
        } else {
            int size = dp(170) * scale / 100;
            AnalogView av = new AnalogView(this, style, thick);
            av.setOnClickListener(alarm); av.setOnLongClickListener(editLong);
            clockBlock.addView(av, new LinearLayout.LayoutParams(size, size));
        }
        int df = prefs.getInt("datefmt", 0);
        if (df < DATE_FMT.length - 1) {
            float ds = Math.max(12f, Math.min(24f, 16f * scale / 100f));
            TextClock d = textClock(DATE_FMT[df], ds, Typeface.create("sans-serif", Typeface.NORMAL), Color.GRAY);
            d.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openCalendar(); }
            });
            d.setOnLongClickListener(editLong);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(8);
            clockBlock.addView(d, lp);
        }
        requestArrange();
    }

    /** Round clock faces, drawn by hand. Redraws once a minute only while visible. */
    class AnalogView extends View {
        final int style, thick;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean registered = false;
        final BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { invalidate(); }
        };

        AnalogView(Context c, int style, int thick) {
            super(c);
            this.style = style;
            this.thick = thick;
        }

        private void reg() {
            if (registered) return;
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
            else getContext().registerReceiver(rcv, f);
            registered = true;
        }

        private void unreg() {
            if (!registered) return;
            try { getContext().unregisterReceiver(rcv); } catch (Exception e) { }
            registered = false;
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (getWindowVisibility() == View.VISIBLE) reg();
        }
        @Override protected void onDetachedFromWindow() { unreg(); super.onDetachedFromWindow(); }
        @Override protected void onWindowVisibilityChanged(int v) {
            super.onWindowVisibilityChanged(v);
            if (v == View.VISIBLE) { reg(); invalidate(); } else unreg();
        }

        private void line(Canvas c, float cx, float cy, float deg, float from, float to) {
            double r = Math.toRadians(deg);
            float s = (float) Math.sin(r), co = (float) Math.cos(r);
            c.drawLine(cx + from * s, cy - from * co, cx + to * s, cy - to * co, p);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2f, cy = w / 2f;
            float sw = dpf(THICK_DP[thick]);
            float R = w / 2f - sw * 2f;
            Calendar cal = Calendar.getInstance();
            int m = cal.get(Calendar.MINUTE), h = cal.get(Calendar.HOUR);
            float minA = m * 6f, hourA = h * 30f + m * 0.5f;

            p.setColor(Color.WHITE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setTypeface(Typeface.DEFAULT);

            if (style == 2 || style == 3) c.drawCircle(cx, cy, R, p);
            if (style == 2) {
                for (int i = 0; i < 12; i++) {
                    float len = (i % 3 == 0) ? R * 0.16f : R * 0.08f;
                    line(c, cx, cy, i * 30f, R - sw - len, R - sw);
                }
            }
            if (style == 4) {
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 12; i++) {
                    double r = Math.toRadians(i * 30f);
                    float rad = (i % 3 == 0) ? sw * 1.9f : sw * 1.1f;
                    c.drawCircle(cx + (float) Math.sin(r) * R, cy - (float) Math.cos(r) * R, rad, p);
                }
            }
            if (style == 5) {
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(R * 0.21f);
                p.setTypeface(Typeface.create(THICK_FONT[thick], Typeface.NORMAL));
                for (int n = 1; n <= 12; n++) {
                    double r = Math.toRadians(n * 30f);
                    float tx = cx + (float) Math.sin(r) * R * 0.84f;
                    float ty = cy - (float) Math.cos(r) * R * 0.84f + R * 0.075f;
                    c.drawText(String.valueOf(n), tx, ty, p);
                }
                p.setTypeface(Typeface.DEFAULT);
            }

            float hl = (style == 5) ? 0.42f : 0.5f, ml = (style == 5) ? 0.62f : 0.78f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw * 1.9f);
            line(c, cx, cy, hourA, 0, R * hl);
            p.setStrokeWidth(sw * 1.2f);
            line(c, cx, cy, minA, 0, R * ml);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, sw * 1.7f, p);
        }
    }

    // =====================================================================
    // customize menus
    // =====================================================================
    private void pickList(String title, String[] items, int current, final IntCallback cb) {
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(items, current, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cb.on(w); d.dismiss(); }
                }).show();
    }

    private void applyAll() {
        setGravities();
        buildClock();
        renderPinned();
    }

    private void toggleDialog(String title, final String key, boolean def, final Runnable after) {
        pickList(title, new String[] { "On", "Off" }, prefs.getBoolean(key, def) ? 0 : 1, new IntCallback() {
            @Override public void on(int v) { prefs.edit().putBoolean(key, v == 0).apply(); after.run(); }
        });
    }

    private void showCustomize() {
        String[] items = { "Clock style", "Clock size", "Clock thickness", "Alignment",
                "12 / 24-hour time", "Date format", "App icons", "Recent apps row", "Burn-in shift",
                "Reorder sections", "Hidden apps", "Reset layout" };
        new AlertDialog.Builder(this).setTitle("Customize").setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                switch (w) {
                    case 0:
                        pickList("Clock style", STYLES, prefs.getInt("style", 2), new IntCallback() {
                            @Override public void on(int v) { prefs.edit().putInt("style", v).apply(); buildClock(); }
                        });
                        break;
                    case 1: showSizeDialog(); break;
                    case 2:
                        pickList("Clock thickness", THICK, prefs.getInt("cthick", 1), new IntCallback() {
                            @Override public void on(int v) { prefs.edit().putInt("cthick", v).apply(); buildClock(); }
                        });
                        break;
                    case 3:
                        pickList("Alignment", ALIGN, prefs.getInt("align", 0), new IntCallback() {
                            @Override public void on(int v) {
                                prefs.edit().putInt("align", v).apply();
                                applyAll();
                                resetPositions();
                            }
                        });
                        break;
                    case 4:
                        pickList("Time format", new String[] { "24-hour", "12-hour" }, prefs.getBoolean("h24", true) ? 0 : 1, new IntCallback() {
                            @Override public void on(int v) { prefs.edit().putBoolean("h24", v == 0).apply(); buildClock(); }
                        });
                        break;
                    case 5: {
                        String[] ex = new String[DATE_FMT.length];
                        for (int i = 0; i < ex.length - 1; i++) ex[i] = new SimpleDateFormat(DATE_FMT[i], Locale.getDefault()).format(new Date());
                        ex[ex.length - 1] = "Hide date";
                        pickList("Date format", ex, prefs.getInt("datefmt", 0), new IntCallback() {
                            @Override public void on(int v) { prefs.edit().putInt("datefmt", v).apply(); buildClock(); }
                        });
                        break;
                    }
                    case 6:
                        toggleDialog("App icons (uses a little more memory)", "icons", false, new Runnable() {
                            @Override public void run() {
                                iconCache.clear();
                                renderPinned();
                                adapter.notifyDataSetChanged();
                            }
                        });
                        break;
                    case 7:
                        toggleDialog("Recent apps row", "recents", true, new Runnable() {
                            @Override public void run() { renderPinned(); }
                        });
                        break;
                    case 8:
                        toggleDialog("Burn-in shift", "burnin", true, new Runnable() {
                            @Override public void run() { applyBurnIn(); }
                        });
                        break;
                    case 9: showReorder(); break;
                    case 10: showHiddenDialog(); break;
                    case 11:
                        prefs.edit().remove("order").apply();
                        resetPositions();
                        Toast.makeText(MainActivity.this, "Layout reset", Toast.LENGTH_SHORT).show();
                        break;
                }
            }
        }).show();
    }

    private void showSizeDialog() {
        final int old = prefs.getInt("csize", 100);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView label = new TextView(this);
        label.setText("Size: " + old + "%");
        label.setTextColor(Color.WHITE);
        label.setTextSize(16f);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(old - 50);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                label.setText("Size: " + (p + 50) + "%");
                if (user) { prefs.edit().putInt("csize", p + 50).apply(); buildClock(); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(label);
        box.addView(sb);
        new AlertDialog.Builder(this).setTitle("Clock size").setView(box)
                .setPositiveButton("OK", null)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { prefs.edit().putInt("csize", old).apply(); buildClock(); }
                }).show();
    }

    private void showReorder() {
        final List<String> ord = order();
        String[] names = new String[ord.size()];
        for (int i = 0; i < names.length; i++) {
            String nm = ord.get(i);
            for (int k = 0; k < BLOCKS.length; k++) if (BLOCKS[k].equals(nm)) nm = BLOCK_NAMES[k];
            names[i] = (i + 1) + ".  " + nm;
        }
        new AlertDialog.Builder(this).setTitle("Tap a section to move it")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, final int idx) {
                        new AlertDialog.Builder(MainActivity.this).setItems(new String[] { "Move up", "Move down" },
                                new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w) {
                                        int to = (w == 0) ? idx - 1 : idx + 1;
                                        if (to >= 0 && to < ord.size()) {
                                            Collections.swap(ord, idx, to);
                                            StringBuilder sb = new StringBuilder();
                                            for (String s : ord) sb.append(sb.length() > 0 ? "," : "").append(s);
                                            prefs.edit().putString("order", sb.toString()).apply();
                                            resetPositions();
                                        }
                                        showReorder();
                                    }
                                }).show();
                    }
                }).setPositiveButton("Done", null).show();
    }

    // =====================================================================
    // home rows
    // =====================================================================
    private TextView homeRow(String text, int color, View.OnClickListener c, View.OnLongClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(24f);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        t.setOnClickListener(c);
        t.setOnLongClickListener(l);
        return t;
    }

    /** Pinned apps and folders (two separate movable blocks). */
    private void renderPinned() {
        setGravities();
        boolean icons = prefs.getBoolean("icons", false);
        pinnedBox.removeAllViews();
        foldersBox.removeAllViews();
        for (String key : pinned()) {
            for (final AppItem app : allApps) {
                if (!app.key().equals(key)) continue;
                TextView row = homeRow(app.label, Color.WHITE, new View.OnClickListener() {
                    @Override public void onClick(View v) { launch(app); }
                }, new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { showMenu(app); return true; }
                });
                if (icons) {
                    Drawable ic = icon(app, 32);
                    if (ic != null) { row.setCompoundDrawables(ic, null, null, null); row.setCompoundDrawablePadding(dp(12)); }
                }
                pinnedBox.addView(row);
            }
        }
        for (final String name : folders().keySet()) {
            foldersBox.addView(homeRow(name + "  \u203A", Color.LTGRAY, new View.OnClickListener() {
                @Override public void onClick(View v) { showFolder(name); }
            }, new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { folderMenu(name); return true; }
            }));
        }
        pinnedBox.setVisibility(pinnedBox.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        foldersBox.setVisibility(foldersBox.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        renderRecents();
        requestArrange();
    }

    // ---------- icons ----------
    private Drawable icon(AppItem a, int sizeDp) {
        String k = a.key() + "#" + sizeDp;
        Drawable d = iconCache.get(k);
        if (d == null) {
            try { d = getPackageManager().getActivityIcon(new ComponentName(a.pkg, a.cls)); }
            catch (Exception e) { return null; }
            d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
            iconCache.put(k, d);
        }
        return d;
    }

    // ---------- recent apps (last 5 opened through this launcher) ----------
    private List<String> recents() {
        List<String> out = new ArrayList<String>();
        for (String s : prefs.getString("recent", "").split("\n")) if (s.length() > 0) out.add(s);
        return out;
    }

    private void pushRecent(String key) {
        List<String> l = recents();
        l.remove(key);
        l.add(0, key);
        while (l.size() > 5) l.remove(l.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append("\n");
        prefs.edit().putString("recent", sb.toString()).apply();
    }

    private void renderRecents() {
        recentsBox.removeAllViews();
        if (!prefs.getBoolean("recents", true)) { recentsBox.setVisibility(View.GONE); return; }
        boolean icons = prefs.getBoolean("icons", false);
        Set<String> hid = hidden();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String key : recents()) {
            for (final AppItem app : allApps) {
                if (!app.key().equals(key) || hid.contains(key)) continue;
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setPadding(dp(6), dp(4), dp(6), dp(4));
                if (icons) {
                    ImageView iv = new ImageView(this);
                    Drawable ic = icon(app, 36);
                    if (ic != null) iv.setImageDrawable(ic);
                    item.addView(iv, new LinearLayout.LayoutParams(dp(36), dp(36)));
                }
                TextView t = new TextView(this);
                t.setText(app.label);
                t.setTextSize(12f);
                t.setTextColor(Color.LTGRAY);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setMaxWidth(dp(70));
                t.setGravity(Gravity.CENTER_HORIZONTAL);
                item.addView(t);
                item.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { launch(app); }
                });
                item.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { showMenu(app); return true; }
                });
                row.addView(item);
            }
        }
        if (row.getChildCount() == 0) { recentsBox.setVisibility(View.GONE); return; }
        recentsBox.addView(row);
        recentsBox.setVisibility(View.VISIBLE);
    }

    // ---------- quick note ----------
    private void renderNote() {
        String n = prefs.getString("note", "");
        if (n.length() == 0) { noteText.setText("+ Add note"); noteText.setTextColor(0xFF555555); }
        else { noteText.setText(n); noteText.setTextColor(0xFFDDDDDD); }
    }

    private void editNote() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setFilters(new InputFilter[] { new InputFilter.LengthFilter(80) });
        et.setHint("e.g. Buy milk at 6 PM");
        et.setText(prefs.getString("note", ""));
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this).setTitle("Quick note").setView(et)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString("note", et.getText().toString().trim()).apply();
                        renderNote();
                    }
                })
                .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString("note", "").apply();
                        renderNote();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------- tap clock / date ----------
    private void openAlarm() {
        try {
            startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "No alarm app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCalendar() {
        try {
            Uri.Builder b = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time");
            ContentUris.appendId(b, System.currentTimeMillis());
            startActivity(new Intent(Intent.ACTION_VIEW).setData(b.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------- burn-in protection: shifts the layout a few pixels, changes every hour ----------
    private void applyBurnIn() {
        if (!prefs.getBoolean("burnin", true)) { home.setTranslationX(0); home.setTranslationY(0); return; }
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) % 6;
        home.setTranslationX(dp(BURN_DX[h]));
        home.setTranslationY(dp(BURN_DY[h]));
    }

    // =====================================================================
    // drawer
    // =====================================================================
    private void openDrawer() {
        search.setText("");
        filter();
        home.setVisibility(View.GONE);
        drawer.setVisibility(View.VISIBLE);
        search.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search, 0);
    }

    private void closeDrawer() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        drawer.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        requestArrange();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase();
        Set<String> hid = hidden();
        shown.clear();
        for (AppItem a : allApps) {
            if (hid.contains(a.key())) continue;
            if (q.length() == 0 || a.label.toLowerCase().contains(q)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
    }

    // =====================================================================
    // apps
    // =====================================================================
    private void loadApps() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppItem> out = new ArrayList<AppItem>();
        for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0)) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            out.add(new AppItem(r.loadLabel(getPackageManager()).toString(), r.activityInfo.packageName, r.activityInfo.name));
        }
        Collections.sort(out, new Comparator<AppItem>() {
            @Override public int compare(AppItem a, AppItem b) { return a.label.compareToIgnoreCase(b.label); }
        });
        allApps = out;
    }

    private void launch(AppItem app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(app.pkg, app.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            pushRecent(app.key());
            if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        } catch (Exception e) {
            Toast.makeText(this, "Can't open", Toast.LENGTH_SHORT).show();
        }
    }

    private void showMenu(final AppItem app) {
        final boolean isPinned = pinned().contains(app.key());
        String[] items = { isPinned ? "Unpin" : "Pin to home", "Add to folder", "Hide", "App info", "Uninstall" };
        new AlertDialog.Builder(this).setTitle(app.label).setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    List<String> l = pinned();
                    if (isPinned) l.remove(app.key());
                    else if (l.size() >= 6) { Toast.makeText(MainActivity.this, "Max 6 pinned", Toast.LENGTH_SHORT).show(); return; }
                    else l.add(app.key());
                    savePinned(l); renderPinned();
                } else if (w == 1) {
                    pickFolder(app);
                } else if (w == 2) {
                    Set<String> h = hidden(); h.add(app.key()); saveHidden(h);
                    List<String> l = pinned(); l.remove(app.key()); savePinned(l);
                    renderPinned(); filter();
                } else if (w == 3) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)));
                } else if (w == 4) {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)));
                }
            }
        }).show();
    }

    // =====================================================================
    // folders
    // =====================================================================
    private LinkedHashMap<String, List<String>> folders() {
        LinkedHashMap<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        for (String line : prefs.getString("folders", "").split("\n")) {
            if (line.length() == 0) continue;
            String[] p = line.split("\t");
            List<String> keys = new ArrayList<String>();
            for (int i = 1; i < p.length; i++) if (p[i].length() > 0) keys.add(p[i]);
            m.put(p[0], keys);
        }
        return m;
    }

    private void saveFolders(LinkedHashMap<String, List<String>> m) {
        StringBuilder sb = new StringBuilder();
        for (String name : m.keySet()) {
            sb.append(name);
            for (String k : m.get(name)) sb.append("\t").append(k);
            sb.append("\n");
        }
        prefs.edit().putString("folders", sb.toString()).apply();
    }

    private void askName(String title, String initial, final NameCallback cb) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this).setTitle(title).setView(et)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim().replace("\t", " ").replace("\n", " ");
                        if (n.length() > 0) cb.onName(n);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void addToFolder(String name, AppItem app) {
        LinkedHashMap<String, List<String>> f = folders();
        List<String> keys = f.get(name);
        if (keys == null) {
            if (f.size() >= 8) { Toast.makeText(this, "Max 8 folders", Toast.LENGTH_SHORT).show(); return; }
            keys = new ArrayList<String>();
            f.put(name, keys);
        }
        if (!keys.contains(app.key())) keys.add(app.key());
        saveFolders(f);
        renderPinned();
        Toast.makeText(this, "Added to " + name, Toast.LENGTH_SHORT).show();
    }

    private void pickFolder(final AppItem app) {
        final List<String> names = new ArrayList<String>(folders().keySet());
        String[] items = new String[names.size() + 1];
        for (int i = 0; i < names.size(); i++) items[i] = names.get(i);
        items[names.size()] = "+ New folder";
        new AlertDialog.Builder(this).setTitle("Add " + app.label + " to").setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w < names.size()) addToFolder(names.get(w), app);
                else askName("New folder", "", new NameCallback() {
                    @Override public void onName(String n) { addToFolder(n, app); }
                });
            }
        }).show();
    }

    private void showFolder(final String name) {
        List<String> keys = folders().get(name);
        Set<String> hid = hidden();
        final List<AppItem> items = new ArrayList<AppItem>();
        if (keys != null) {
            for (String k : keys) for (AppItem a : allApps) if (a.key().equals(k) && !hid.contains(k)) items.add(a);
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "Empty folder. Long-press an app > Add to folder", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[items.size()];
        for (int i = 0; i < names.length; i++) names[i] = items.get(i).label;
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(name)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { launch(items.get(w)); }
                }).create();
        dlg.show();
        dlg.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, final int i, long id) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Remove " + items.get(i).label + " from " + name + "?")
                        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                LinkedHashMap<String, List<String>> f = folders();
                                if (f.get(name) != null) f.get(name).remove(items.get(i).key());
                                saveFolders(f); renderPinned(); dlg.dismiss();
                            }
                        }).setNegativeButton("Cancel", null).show();
                return true;
            }
        });
    }

    private void folderMenu(final String name) {
        String[] items = { "Rename folder", "Delete folder" };
        new AlertDialog.Builder(this).setTitle(name).setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    askName("Rename folder", name, new NameCallback() {
                        @Override public void onName(String n) {
                            LinkedHashMap<String, List<String>> old = folders();
                            LinkedHashMap<String, List<String>> nf = new LinkedHashMap<String, List<String>>();
                            for (String k : old.keySet()) nf.put(k.equals(name) ? n : k, old.get(k));
                            saveFolders(nf); renderPinned();
                        }
                    });
                } else {
                    LinkedHashMap<String, List<String>> f = folders();
                    f.remove(name);
                    saveFolders(f); renderPinned();
                    Toast.makeText(MainActivity.this, "Folder deleted (apps are not uninstalled)", Toast.LENGTH_SHORT).show();
                }
            }
        }).show();
    }

    private void showHiddenDialog() {
        Set<String> hid = hidden();
        final List<AppItem> apps = new ArrayList<AppItem>();
        for (AppItem a : allApps) if (hid.contains(a.key())) apps.add(a);
        if (apps.isEmpty()) { Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i).label;
        new AlertDialog.Builder(this).setTitle("Tap to unhide").setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                Set<String> h = hidden(); h.remove(apps.get(w).key()); saveHidden(h); filter();
            }
        }).show();
    }

    // =====================================================================
    // double tap to lock
    // =====================================================================
    private void lockScreen() {
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, LockAdmin.class);
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
        } else {
            Intent i = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Only used to lock the screen when you double-tap the home screen.");
            startActivity(i);
        }
    }
}
