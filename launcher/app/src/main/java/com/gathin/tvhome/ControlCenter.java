package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Right-side smoked Liquid Glass Control Center overlay with Now Playing card and quick actions. */
final class ControlCenter {

    static final int PANEL_WIDTH_DP = 360;
    static final float DIM_AMOUNT = 0.45f;
    static final int SLIDE_DURATION_MS = 240;
    static final int GLASS_FILL = 0xD914141E;
    static final int GLASS_STROKE = 0x38FFFFFF;
    static final int GLASS_RADIUS_DP = 28;
    static final float GLASS_STROKE_DP = 1.5f;

    private static final int CARD_FILL = 0x16FFFFFF;
    private static final int CARD_STROKE = 0x26FFFFFF;
    private static final int ROW_IDLE_FILL = 0x0FFFFFFF;
    private static final int ROW_FOCUS_FILL = 0x30FFFFFF;
    private static final int ROW_FOCUS_STROKE = 0xCCFFFFFF;
    private static final int ACCENT_BLUE = 0xFF0A84FF;
    private static final int TEXT_PRIMARY = 0xFFF5F5F7;
    private static final int TEXT_SECONDARY = 0xFFA1A1A6;

    enum Action {
        INPUTS, WIFI, SCREENSAVER, LIBRARY, REORDER, SETTINGS,
        MEDIA_PLAY_PAUSE, MEDIA_NEXT, TOGGLE_MUSIC_PILL
    }

    static final class Item {
        final Action action;
        final String label;
        final int labelRes;

        Item(Action action, String label, int labelRes) {
            this.action = action;
            this.label = label;
            this.labelRes = labelRes;
        }
    }

    interface Host {
        void onInputs();
        void onWifi();
        void onScreensaver();
        void onLibrary();
        void onReorder();
        void onSettings();
        void onMediaPlayPause();
        void onMediaNext();
        void onToggleMusicPill();
        boolean isMusicPillVisible();
        String nowPlayingTitle();
        String nowPlayingArtist();
        boolean isMediaPlaying();
    }

    private ControlCenter() {}

    static List<Item> buildItems(boolean showInputs, boolean showLibrary, boolean reorderAllowed) {
        List<Item> items = new ArrayList<>(6);
        if (showInputs) items.add(new Item(Action.INPUTS, "Inputs", R.string.inputs));
        items.add(new Item(Action.WIFI, "Wi-Fi", R.string.wifi));
        items.add(new Item(Action.SCREENSAVER, "Screensaver", 0));
        if (showLibrary) items.add(new Item(Action.LIBRARY, "App library", R.string.app_library));
        if (reorderAllowed) items.add(new Item(Action.REORDER, "Reorder apps", R.string.reorder));
        items.add(new Item(Action.SETTINGS, "Settings", R.string.settings));
        return Collections.unmodifiableList(items);
    }

    static String formatNowPlaying(String title, String artist, boolean playing) {
        String t = title == null ? "" : title.trim();
        String a = artist == null ? "" : artist.trim();
        if (t.isEmpty() && a.isEmpty()) return "Nothing playing";
        String track = (!t.isEmpty() && !a.isEmpty()) ? (t + " · " + a) : (!t.isEmpty() ? t : a);
        return (playing ? "Playing · " : "Paused · ") + track;
    }

    static Dialog show(Activity activity, Host host) {
        Config cfg = Config.load(activity);
        return show(
                activity,
                host,
                cfg == null || cfg.showInputsPill,
                cfg != null && (cfg.appLibrary || cfg.libraryButton || cfg.showLibraryPill),
                cfg != null && cfg.reorderButton,
                cfg != null && cfg.reducedMotion
        );
    }

    static Dialog show(Activity activity, Host host, boolean showInputs, boolean showLibrary,
                       boolean reorderAllowed, boolean reducedMotion) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        float d = activity.getResources().getDisplayMetrics().density;
        Typeface mediumFont = UiFont.get(activity, R.string.font_medium);
        Typeface regularFont = UiFont.get(activity, R.string.font_regular);

        FrameLayout root = new FrameLayout(activity);
        int outerPad = Math.round(16 * d);
        root.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rect(GLASS_FILL, GLASS_STROKE, Math.round(GLASS_RADIUS_DP * d), GLASS_STROKE_DP * d));
        int innerPad = Math.round(20 * d);
        panel.setPadding(innerPad, innerPad, innerPad, innerPad);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView header = new TextView(activity);
        header.setText("Control Center");
        header.setTextColor(TEXT_PRIMARY);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        header.setTypeface(mediumFont);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = Math.round(16 * d);
        panel.addView(header, hLp);

        // Now Playing card
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rect(CARD_FILL, CARD_STROKE, Math.round(20 * d), d));
        int cardPad = Math.round(16 * d);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.bottomMargin = Math.round(16 * d);
        panel.addView(card, cLp);

        TextView secLabel = new TextView(activity);
        secLabel.setText("Now Playing");
        secLabel.setTextColor(TEXT_SECONDARY);
        secLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        secLabel.setTypeface(mediumFont);
        card.addView(secLabel);

        TextView trackText = new TextView(activity);
        trackText.setTextColor(TEXT_PRIMARY);
        trackText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        trackText.setTypeface(regularFont);
        trackText.setMaxLines(2);
        trackText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = Math.round(4 * d);
        tLp.bottomMargin = Math.round(12 * d);
        card.addView(trackText, tLp);

        LinearLayout controlsRow = new LinearLayout(activity);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(controlsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView playBtn = makeBtn(activity, "", d, mediumFont, true, 14);
        TextView nextBtn = makeBtn(activity, "Next", d, mediumFont, true, 14);
        TextView pillBtn = makeBtn(activity, "", d, mediumFont, true, 14);

        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            int radiusDp = v == playBtn || v == nextBtn || v == pillBtn ? 14 : 16;
            boolean pillActive = v == pillBtn && host != null && host.isMusicPillVisible();
            v.setBackground(rect(
                    pillActive ? ACCENT_BLUE : (hasFocus ? ROW_FOCUS_FILL : ROW_IDLE_FILL),
                    hasFocus ? ROW_FOCUS_STROKE : Color.TRANSPARENT,
                    Math.round(radiusDp * d), GLASS_STROKE_DP * d));
            float s = hasFocus ? 1.02f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(120).start();
        };
        playBtn.setOnFocusChangeListener(focusListener);
        nextBtn.setOnFocusChangeListener(focusListener);
        pillBtn.setOnFocusChangeListener(focusListener);

        Runnable refresh = () -> {
            boolean playing = host != null && host.isMediaPlaying();
            trackText.setText(formatNowPlaying(
                    host != null ? host.nowPlayingTitle() : null,
                    host != null ? host.nowPlayingArtist() : null,
                    playing));
            playBtn.setText(playing ? "Pause" : "Play");
            boolean pillOn = host != null && host.isMusicPillVisible();
            pillBtn.setText(pillOn ? "Pill: On" : "Pill: Off");
            pillBtn.setBackground(rect(
                    pillOn ? ACCENT_BLUE : (pillBtn.hasFocus() ? ROW_FOCUS_FILL : ROW_IDLE_FILL),
                    pillBtn.hasFocus() ? ROW_FOCUS_STROKE : Color.TRANSPARENT,
                    Math.round(14 * d), GLASS_STROKE_DP * d));
        };
        refresh.run();

        View.OnClickListener clickListener = v -> {
            Object tag = v.getTag();
            if (tag instanceof Action) {
                Action a = (Action) tag;
                if (a == Action.MEDIA_PLAY_PAUSE) {
                    if (host != null) host.onMediaPlayPause();
                    refresh.run();
                } else if (a == Action.MEDIA_NEXT) {
                    if (host != null) host.onMediaNext();
                    refresh.run();
                } else if (a == Action.TOGGLE_MUSIC_PILL) {
                    if (host != null) host.onToggleMusicPill();
                    refresh.run();
                } else {
                    dialog.dismiss();
                    if (host != null) dispatchAction(host, a);
                }
            }
        };

        playBtn.setTag(Action.MEDIA_PLAY_PAUSE);
        nextBtn.setTag(Action.MEDIA_NEXT);
        pillBtn.setTag(Action.TOGGLE_MUSIC_PILL);
        playBtn.setOnClickListener(clickListener);
        nextBtn.setOnClickListener(clickListener);
        pillBtn.setOnClickListener(clickListener);

        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = Math.round(8 * d);
        controlsRow.addView(playBtn, b1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b2.rightMargin = Math.round(8 * d);
        controlsRow.addView(nextBtn, b2);
        controlsRow.addView(pillBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));

        // Quick actions list
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout actionList = new LinearLayout(activity);
        actionList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(actionList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<Item> items = buildItems(showInputs, showLibrary, reorderAllowed);
        View firstRow = null;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            String label = item.labelRes != 0 ? activity.getString(item.labelRes) : item.label;
            TextView row = makeBtn(activity, label, d, mediumFont, false, 16);
            row.setTag(item.action);
            row.setOnFocusChangeListener(focusListener);
            row.setOnClickListener(clickListener);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < items.size() - 1) rLp.bottomMargin = Math.round(8 * d);
            actionList.addView(row, rLp);
            if (i == 0) firstRow = row;
        }

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.width = Math.round(PANEL_WIDTH_DP * d);
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount = DIM_AMOUNT;
            w.setAttributes(lp);
        }

        panel.setTranslationX(Math.round(PANEL_WIDTH_DP * d));
        panel.setAlpha(0f);
        panel.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : SLIDE_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

        dialog.show();
        if (firstRow != null) firstRow.requestFocus();
        else playBtn.requestFocus();
        return dialog;
    }

    private static TextView makeBtn(Context ctx, String text, float d, Typeface font, boolean compact, int radiusDp) {
        TextView tv = new TextView(ctx);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setText(text);
        tv.setTextColor(TEXT_PRIMARY);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 13 : 16);
        tv.setTypeface(font);
        tv.setGravity(compact ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        if (compact) tv.setSingleLine(true);
        int hPad = Math.round((compact ? 8 : 16) * d);
        int vPad = Math.round((compact ? 10 : 14) * d);
        tv.setPadding(hPad, vPad, hPad, vPad);
        tv.setBackground(rect(ROW_IDLE_FILL, Color.TRANSPARENT, Math.round(radiusDp * d), GLASS_STROKE_DP * d));
        return tv;
    }

    static void dispatchAction(Host host, Action a) {
        if (host == null || a == null) return;
        if (a == Action.INPUTS) host.onInputs();
        else if (a == Action.WIFI) host.onWifi();
        else if (a == Action.SCREENSAVER) host.onScreensaver();
        else if (a == Action.LIBRARY) host.onLibrary();
        else if (a == Action.REORDER) host.onReorder();
        else if (a == Action.SETTINGS) host.onSettings();
        else if (a == Action.MEDIA_PLAY_PAUSE) host.onMediaPlayPause();
        else if (a == Action.MEDIA_NEXT) host.onMediaNext();
        else if (a == Action.TOGGLE_MUSIC_PILL) host.onToggleMusicPill();
    }

    private static GradientDrawable rect(int fill, int stroke, int radiusPx, float strokeWidthPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fill);
        gd.setCornerRadius(radiusPx);
        if (stroke != Color.TRANSPARENT && strokeWidthPx > 0f) {
            gd.setStroke(Math.max(1, Math.round(strokeWidthPx)), stroke);
        }
        return gd;
    }
}
