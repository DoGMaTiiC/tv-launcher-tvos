package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;

import java.util.Date;
import java.util.Locale;

/**
 * Apple tvOS 27.2 HeadBoard Top Shelf + TVSSMenuBar top-right status pill.
 * State 1 (scrollProgress = 0): full-bleed per-app Top Shelf showcase + top-right status pill.
 * State 2 (scrollProgress -> 1): translates upward (-220*k) and fades out into Atmosphere.
 */
final class TopStageView extends View {

    static final class CardData {
        final String pkg;
        final String title;
        final Bitmap art;
        final BitmapShader shader;

        CardData(String pkg, String title, String meta, Bitmap art, int accent) {
            this.pkg = pkg;
            this.title = title;
            this.art = art;
            this.shader = (art != null && !art.isRecycled())
                    ? new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        }
    }

    interface Listener {
        void onReturnToDock();
        void onLaunchCard(String pkg);
        void onLiveTv();
        void onInputs();
        void onControlCenter();
    }

    static final int ZONE_NONE = 0, ZONE_CARDS = 1, ZONE_TOP = 2;
    private static final String[] CW_T = {"YOUR FRIENDS &", "SHRINKING", "SEVERANCE"};
    private static final String[] CW_S = {"Your Friends & Neighbours", "Shrinking", "Severance"};
    private static final String[] CW_M = {"Continue \u00B7 S2 E5", "Continue \u00B7 S2 E8", "Continue \u00B7 S2 E10"};
    private static final float[] CW_P = {0.62f, 0.40f, 0.85f};
    private static final int[] CW_B0 = {0xFF4D2A1C, 0xFF1B6A70, 0xFF0B2F3A};
    private static final int[] CW_B1 = {0xFF0E1624, 0xFF3E2628, 0xFF07161B};

    private static final float[] YT_C = {
            640, 40, 200,  625, 90, 90,  1790, 110, 340,  1860, 40, 210,  460, 730, 195
    };
    private static final int[] YT_CC = {
            0xFFFF6B81, 0xFF00E5FF, 0xFF00B853, 0xFF009640, 0xFF00C853
    };
    private static final float[] YT_Q = {
            800, 0, 1460, 0, 940, 155, 835, 55,
            0, 250, 410, 640, 0, 690, 0, 690,
            0, 470, 320, 640, 0, 750, 0, 750,
            1140, 890, 1610, 520, 1680, 890, 1680, 890,
            1510, 490, 1750, 430, 1870, 890, 1620, 890,
            1750, 430, 1920, 290, 1920, 630, 1920, 630
    };
    private static final int[] YT_QC = {
            0xFFFFD600, 0xFFFF5252, 0xFF00E5FF, 0xFF00D4FF, 0xFFFFD600, 0xFFFF4081
    };

    private static final int[] ED_B0 = {0xFF0A0C14, 0xFF021030, 0xFF090507, 0xFF03101C};
    private static final int[] ED_B1 = {0xFF2B0B16, 0xFF0A3A94, 0xFF090507, 0xFF072B3E};
    private static final int[] ED_RC = {0x99FA233B, 0x00000000, 0xCCE50914, 0x8814B8C4};
    private static final float[] ED_RX = {1520f, 0f, 1380f, 1480f};
    private static final float[] ED_RY = {320f, 0f, 360f, 420f};
    private static final float[] ED_RR = {520f, 0f, 620f, 520f};
    private static final String[] ED_EYE = {"FAMILY POP VIDEOS", "PARAMOUNT+", "NETFLIX SERIES", "LUCASFILM"};
    private static final String[] ED_TIT = {"", "Blue Mountain Peak", "STRANGER THINGS", "A N D O R"};
    private static final String[] ED_SUB = {
            "The Middle \u2013 Zedd, Grey & Maren Morris",
            "Original Movie \u00B7 4K Dolby Vision",
            "Watch Season 5 Now \u00B7 4K Ultra HD",
            "Season 2 \u00B7 Streaming Now on Disney+"
    };
    private static final int[] ED_TC = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFF2626, 0xFFFFFFFF};

    private final DockBlur.ShadowCache shadows = new DockBlur.ShadowCache();
    private final DockBlur.LiquidRenderer liquid;
    private final Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF pill = new RectF();
    private final Path path = new Path();
    private final Matrix mtx = new Matrix();

    private final CardData[] cards = new CardData[3];
    private boolean nightMode = true;
    private float scrollProgress = 0f;
    private int focusedDockSlot = -1;
    private String focusedDockPkg, focusedDockLabel;
    private int zone = ZONE_NONE, focusedCard = 0;
    private String nowPlayingText = "";
    private boolean nowPlayingActive;
    private long cachedMin = -1L;
    private String cachedTime = "11:08 AM";

    TopStageView(Context ctx, DockBlur.LiquidRenderer liquid, Listener listener) {
        super(ctx);
        this.liquid = liquid;
        this.listener = listener;
        setFocusable(false);
        setWillNotDraw(false);
        rim.setStyle(Paint.Style.STROKE);
    }

    void setNightMode(boolean nightMode) {
        if (this.nightMode != nightMode) {
            this.nightMode = nightMode;
            invalidate();
        }
    }

    void setScrollProgress(float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(this.scrollProgress - clamped) > 0.001f) {
            this.scrollProgress = clamped;
            invalidate();
        }
    }

    void setFocusedDock(int slot, String pkg, String label) {
        this.focusedDockSlot = slot;
        this.focusedDockPkg = pkg;
        this.focusedDockLabel = label;
        invalidate();
    }

    void setCards(CardData c0, CardData c1, CardData c2) {
        cards[0] = c0;
        cards[1] = c1;
        cards[2] = c2;
        invalidate();
    }

    void setNowPlaying(String line, boolean active) {
        String s = line == null ? "" : line;
        if (!s.equals(nowPlayingText) || active != nowPlayingActive) {
            nowPlayingText = s;
            nowPlayingActive = active;
            invalidate();
        }
    }

    void enterFromDock(boolean toButtons) {
        setFocusable(true);
        zone = toButtons ? ZONE_TOP : ZONE_CARDS;
        focusedCard = 0;
        requestFocus();
        invalidate();
    }

    void clearStageFocus() {
        zone = ZONE_NONE;
        setFocusable(false);
        invalidate();
    }

    boolean isStageFocused() {
        return zone != ZONE_NONE && hasFocus();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus && zone != ZONE_NONE) clearStageFocus();
        else invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (zone == ZONE_NONE) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (zone == ZONE_TOP) {
                zone = ZONE_CARDS;
                invalidate();
                return true;
            }
            clearStageFocus();
            if (listener != null) listener.onReturnToDock();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (zone == ZONE_CARDS) {
                zone = ZONE_TOP;
                invalidate();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && zone == ZONE_CARDS && resolveSlot() == 0 && focusedCard > 0) {
            focusedCard--;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && zone == ZONE_CARDS && resolveSlot() == 0 && focusedCard < 2) {
            focusedCard++;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (listener != null) {
                if (zone == ZONE_TOP) listener.onControlCenter();
                else listener.onLaunchCard(focusedDockPkg != null ? focusedDockPkg : (cards[0] != null ? cards[0].pkg : null));
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            clearStageFocus();
            if (listener != null) listener.onReturnToDock();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int resolveSlot() {
        String pkg = focusedDockPkg != null ? focusedDockPkg : (cards[0] != null ? cards[0].pkg : null);
        String lbl = focusedDockLabel != null ? focusedDockLabel : (cards[0] != null ? cards[0].title : null);
        String s = ((pkg != null ? pkg : "") + " " + (lbl != null ? lbl : "")).toLowerCase(Locale.ROOT);
        if (s.contains("apple") || s.contains("tv.apple")) return 0;
        if (s.contains("youtube") && !s.contains("tvunplugged")) return 1;
        if (s.contains("music") || s.contains("spotify")) return 2;
        if (s.contains("paramount") || s.contains("cbs")) return 3;
        if (s.contains("netflix")) return 4;
        if (s.contains("disney")) return 5;
        return focusedDockSlot >= 0 ? (focusedDockSlot % 6) : 0;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float topAlpha = 1f - scrollProgress;
        if (topAlpha <= 0.01f) return;

        float k = w / 1920f;
        int save = topAlpha < 0.999f
                ? c.saveLayerAlpha(0, 0, w, h, Math.round(topAlpha * 255f))
                : c.save();
        if (scrollProgress > 0f) c.translate(0f, -220f * k * scrollProgress);

        int slot = resolveSlot();
        if (slot == 0) drawAppleTvShelf(c, w, k);
        else if (slot == 1) drawYouTubeShelf(c, w, k);
        else drawEditorialShelf(c, w, slot, k);

        int fadeBase = (nightMode ? 0x0A0A0E : 0xEBEBF0);
        fill.setShader(new LinearGradient(0, 660f * k, 0, 980f * k,
                new int[]{0x00000000, fadeBase | 0x66000000, fadeBase | 0xE6000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 660f * k, w, Math.max(h, 980f * k), fill);
        fill.setShader(null);

        drawStatusPill(c, w, h, k);
        c.restoreToCount(save);
    }

    private void drawAppleTvShelf(Canvas c, int w, float k) {
        fill.setShader(new LinearGradient(0, 0, 0, 860f * k,
                new int[]{0xFF162235, 0xFF0D1522, 0x00080B10}, new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, 860f * k, fill);
        fill.setShader(null);

        drawText(c, "Continue Watching", 90f * k, 104f * k, 28f * k, 0xD8FFFFFF, true);

        float cw = 548f * k, ch = 308f * k, gap = 42f * k, top = 132f * k, r = 24f * k;
        for (int i = 0; i < 3; i++) {
            float left = 90f * k + i * (cw + gap);
            boolean focused = zone == ZONE_CARDS && focusedCard == i;
            float s = focused ? 1.04f : 1f;
            float cx = left + cw / 2f, cy = top + ch / 2f - (focused ? 6f * k : 0f);
            tmp.set(cx - cw * s / 2f, cy - ch * s / 2f, cx + cw * s / 2f, cy + ch * s / 2f);
            float rr = r * s;

            pill.set(tmp);
            pill.offset(0, (focused ? 20f : 14f) * k);
            shadows.draw(c, pill, rr, DockBlur.DEEP_BLUR * k, focused ? 0x99000000 : 0x70000000);

            fill.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                    CW_B0[i], CW_B1[i], Shader.TileMode.CLAMP));
            c.drawRoundRect(tmp, rr, rr, fill);
            fill.setShader(null);
            fill.setColor(i == 0 ? 0x28FFB347 : i == 1 ? 0x26FF8A65 : 0x284DD0E1);
            c.drawCircle(tmp.right - 90f * k, tmp.top + 85f * k, 110f * k, fill);

            fill.setShader(new LinearGradient(0, tmp.centerY(), 0, tmp.bottom,
                    0x00000000, 0xCC06080C, Shader.TileMode.CLAMP));
            c.drawRoundRect(tmp, rr, rr, fill);
            fill.setShader(null);

            drawText(c, "tv+", tmp.right - 52f * k, tmp.top + 32f * k, 17f * k, 0xE6FFFFFF, true);
            if (i == 0) {
                drawText(c, CW_T[0], tmp.left + 26f * k, tmp.bottom - 102f * k, 27f * k, 0xFFFFFFFF, true);
                drawText(c, "NEIGHBOURS", tmp.left + 26f * k, tmp.bottom - 68f * k, 27f * k, 0xFFFFFFFF, true);
            } else {
                drawText(c, CW_T[i], tmp.left + 26f * k, tmp.bottom - 72f * k, 31f * k, 0xFFFFFFFF, true);
            }
            drawText(c, CW_M[i], tmp.left + 26f * k, tmp.bottom - 38f * k, 17f * k, 0xCCFFFFFF, false);

            float bx = tmp.left + 26f * k, by = tmp.bottom - 24f * k, bw = 180f * k, bh = 5f * k;
            fill.setColor(0x4DFFFFFF);
            c.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2f, bh / 2f, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(bx, by, bx + bw * CW_P[i], by + bh, bh / 2f, bh / 2f, fill);

            rim.setStrokeWidth((focused ? 3f : 1.4f) * k);
            rim.setColor(focused ? 0xF2FFFFFF : 0x48FFFFFF);
            c.drawRoundRect(tmp, rr, rr, rim);

            if ((zone == ZONE_CARDS && focusedCard == i) || (zone == ZONE_NONE && i == 0)) {
                tp.setTypeface(Typeface.DEFAULT);
                tp.setTextSize(20f * k);
                drawText(c, CW_S[i], cx - tp.measureText(CW_S[i]) / 2f, top + ch + 38f * k, 20f * k, 0xCCFFFFFF, false);
            }
        }
    }

    private void drawYouTubeShelf(Canvas c, int w, float k) {
        int save = c.save();
        c.clipRect(0, 0, w, 920f * k);
        fill.setColor(0xFF0F0F0F);
        c.drawRect(0, 0, w, 920f * k, fill);

        for (int i = 0; i < YT_CC.length; i++) {
            fill.setColor(YT_CC[i]);
            c.drawCircle(YT_C[i * 3] * k, YT_C[i * 3 + 1] * k, YT_C[i * 3 + 2] * k, fill);
        }
        for (int i = 0; i < YT_QC.length; i++) {
            fill.setColor(YT_QC[i]);
            int b = i * 8;
            drawQuad(c, YT_Q[b] * k, YT_Q[b + 1] * k, YT_Q[b + 2] * k, YT_Q[b + 3] * k,
                    YT_Q[b + 4] * k, YT_Q[b + 5] * k, YT_Q[b + 6] * k, YT_Q[b + 7] * k);
        }
        fill.setShader(new RadialGradient(1030f * k, 750f * k, 220f * k,
                0xFFFF2E55, 0x00FF2E55, Shader.TileMode.CLAMP));
        c.drawCircle(1030f * k, 750f * k, 220f * k, fill);
        fill.setShader(null);

        float cy = 390f * k, iconW = 118f * k, iconH = 84f * k, gap = 24f * k;
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(76f * k);
        tp.setLetterSpacing(-0.03f);
        float ix = 960f * k - (iconW + gap + tp.measureText("YouTube")) / 2f;

        tmp.set(ix, cy - iconH / 2f, ix + iconW, cy + iconH / 2f);
        fill.setColor(0xFFFF0000);
        c.drawRoundRect(tmp, 20f * k, 20f * k, fill);

        float tx = tmp.centerX() + 3f * k;
        fill.setColor(0xFFFFFFFF);
        drawQuad(c, tx - 14f * k, cy - 17f * k, tx + 19f * k, cy, tx - 14f * k, cy + 17f * k, tx - 14f * k, cy + 17f * k);

        tp.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = tp.getFontMetrics();
        c.drawText("YouTube", ix + iconW + gap, cy - (fm.ascent + fm.descent) / 2f, tp);
        tp.setLetterSpacing(0f);

        if (zone == ZONE_CARDS) drawOpenPill(c, 960f * k, 488f * k, "Open YouTube", k);
        c.restoreToCount(save);
    }

    private void drawEditorialShelf(Canvas c, int w, int slot, float k) {
        int idx = slot - 2;
        if (slot == 2 && cards[0] != null && cards[0].art != null && cards[0].art.getWidth() > 100 && cards[0].shader != null) {
            tmp.set(0, 0, w, 900f * k);
            float iw = cards[0].art.getWidth(), ih = cards[0].art.getHeight();
            float sc = Math.max(tmp.width() / iw, tmp.height() / ih);
            mtx.setScale(sc, sc);
            mtx.postTranslate(tmp.left + (tmp.width() - iw * sc) / 2f, tmp.top + (tmp.height() - ih * sc) / 2f);
            cards[0].shader.setLocalMatrix(mtx);
            imgPaint.setShader(cards[0].shader);
            c.drawRect(tmp, imgPaint);
            imgPaint.setShader(null);
            fill.setColor(0x6608080D);
            c.drawRect(tmp, fill);
        } else {
            fill.setShader(new LinearGradient(0, 0, w, 900f * k, ED_B0[idx], ED_B1[idx], Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, 900f * k, fill);
            fill.setShader(null);
            if (ED_RR[idx] > 0f) {
                fill.setShader(new RadialGradient(ED_RX[idx] * k, ED_RY[idx] * k, ED_RR[idx] * k,
                        ED_RC[idx], ED_RC[idx] & 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(ED_RX[idx] * k, ED_RY[idx] * k, ED_RR[idx] * k, fill);
                fill.setShader(null);
            }
        }

        if (slot == 2) {
            fill.setColor(0x16FFFFFF);
            drawQuad(c, 360f * k, 0, 520f * k, 0, 880f * k, 680f * k, 560f * k, 680f * k);
            drawQuad(c, 1280f * k, 0, 1460f * k, 0, 1740f * k, 680f * k, 1340f * k, 680f * k);
            drawText(c, ED_EYE[0], 90f * k, 100f * k, 28f * k, 0xFFFFFFFF, true);
            String sub = (nowPlayingActive && !nowPlayingText.isEmpty()) ? nowPlayingText : ED_SUB[0];
            drawText(c, sub, 90f * k, 134f * k, 20f * k, 0xCCFFFFFF, false);
            String hint = "Press \u2303 for Full Screen";
            tp.setTextSize(18f * k);
            drawText(c, hint, 1830f * k - tp.measureText(hint), 620f * k, 18f * k, 0x99FFFFFF, false);
            if (zone == ZONE_CARDS) drawOpenPill(c, 190f * k, 200f * k, "Open Music", k);
            return;
        }

        if (slot == 3) {
            fill.setColor(0xB3FFFFFF);
            for (int i = -4; i <= 4; i++) {
                float a = (float) Math.toRadians(i * 15 - 90);
                c.drawCircle(1360f * k + (float) Math.cos(a) * 320f * k,
                        380f * k + (float) Math.sin(a) * 260f * k, 4.5f * k, fill);
            }
            fill.setColor(0xFF1A4B8E);
            drawQuad(c, 920f * k, 720f * k, 1360f * k, 185f * k, 1800f * k, 720f * k, 1800f * k, 720f * k);
            fill.setColor(0xFFE6F0FF);
            drawQuad(c, 1360f * k, 185f * k, 1800f * k, 720f * k, 1410f * k, 720f * k, 1410f * k, 720f * k);
        } else if (slot == 4) {
            tmp.set(90f * k, 372f * k, 142f * k, 424f * k);
            fill.setColor(0xFFE50914);
            c.drawRoundRect(tmp, 8f * k, 8f * k, fill);
            drawText(c, "TOP", 99f * k, 394f * k, 15f * k, 0xFFFFFFFF, true);
            drawText(c, "10", 103f * k, 416f * k, 20f * k, 0xFFFFFFFF, true);
            drawText(c, "#1 in TV Shows Today", 158f * k, 406f * k, 22f * k, 0xE6FFFFFF, true);
        }

        drawText(c, ED_EYE[idx], 90f * k, 255f * k, 18f * k, 0xB3FFFFFF, true);
        drawText(c, ED_TIT[idx], 90f * k, 326f * k, 58f * k, ED_TC[idx], true);
        drawText(c, ED_SUB[idx], 90f * k, 466f * k, 20f * k, 0xCCFFFFFF, false);
        if (zone == ZONE_CARDS) drawOpenPill(c, 190f * k, 530f * k, "Open App", k);
    }

    private void drawOpenPill(Canvas c, float cx, float cy, String label, float k) {
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(20f * k);
        float pw = tp.measureText(label) + 44f * k, ph = 44f * k;
        pill.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f);
        tmp.set(pill);
        tmp.offset(0, 8f * k);
        shadows.draw(c, tmp, ph / 2f, DockBlur.SHADOW_BLUR * k, 0x80000000);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(pill, ph / 2f, ph / 2f, fill);
        drawText(c, label, cx - tp.measureText(label) / 2f, cy - (tp.ascent() + tp.descent()) / 2f, 20f * k, 0xFF111114, true);
    }

    private void drawStatusPill(Canvas c, int w, int h, float k) {
        long min = System.currentTimeMillis() / 60_000L;
        if (min != cachedMin) {
            cachedMin = min;
            cachedTime = DateFormat.getTimeFormat(getContext()).format(new Date());
        }
        float cy = 66f * k, right = 1830f * k, avR = 20f * k, avCx = right - avR;
        float ccW = 36f * k, ccH = 26f * k, ccR = avCx - avR - 12f * k, ccL = ccR - ccW;

        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(22f * k);
        float timeX = ccL - 14f * k - tp.measureText(cachedTime);

        float pillH = 48f * k;
        pill.set(timeX - 16f * k, cy - pillH / 2f, right + 8f * k, cy + pillH / 2f);
        if (liquid != null) {
            liquid.draw(c, pill, pillH / 2f, w, h, 0, 0, nightMode, 0.04f, 0.5f);
        } else {
            fill.setColor(nightMode ? 0x55141418 : 0xAAFFFFFF);
            c.drawRoundRect(pill, pillH / 2f, pillH / 2f, fill);
        }

        int ink = nightMode ? 0xE6FFFFFF : 0xFF1D1D1F;
        Paint.FontMetrics fm = tp.getFontMetrics();
        drawText(c, cachedTime, timeX, cy - (fm.ascent + fm.descent) / 2f, 22f * k, ink, true);

        boolean topFoc = zone == ZONE_TOP;
        float s = topFoc ? 1.12f : 1f, ccCx = (ccL + ccR) / 2f;
        tmp.set(ccCx - ccW * s / 2f, cy - ccH * s / 2f, ccCx + ccW * s / 2f, cy + ccH * s / 2f);
        if (topFoc) {
            pill.set(tmp);
            pill.offset(0, 6f * k);
            shadows.draw(c, pill, 8f * k, DockBlur.SHADOW_BLUR * k, 0x80000000);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, 8f * k, 8f * k, fill);
        } else {
            fill.setColor(nightMode ? 0x441E1E24 : 0x33000000);
            c.drawRoundRect(tmp, 8f * k, 8f * k, fill);
            rim.setStrokeWidth(1.2f * k);
            rim.setColor(nightMode ? 0x55FFFFFF : 0x44000000);
            c.drawRoundRect(tmp, 8f * k, 8f * k, rim);
        }
        rim.setStrokeWidth(1.8f * k * s);
        rim.setColor(topFoc ? 0xFF111114 : ink);
        pill.set(ccCx - 8f * k * s, cy - 5.5f * k * s, ccCx + 8f * k * s, cy + 5.5f * k * s);
        c.drawRoundRect(pill, 2.5f * k, 2.5f * k, rim);

        fill.setShader(new LinearGradient(avCx, cy - avR, avCx, cy + avR, 0xFFE09F67, 0xFF6B4226, Shader.TileMode.CLAMP));
        c.drawCircle(avCx, cy, avR, fill);
        fill.setShader(null);
        fill.setColor(0xFF2B1B12);
        c.drawCircle(avCx, cy - 4f * k, 7f * k, fill);
        c.drawArc(avCx - 12f * k, cy + 3f * k, avCx + 12f * k, cy + 23f * k, 180f, 180f, true, fill);
        rim.setStrokeWidth(1.5f * k);
        rim.setColor(0x88FFFFFF);
        c.drawCircle(avCx, cy, avR, rim);
        fill.setColor(0xFF30D158);
        c.drawCircle(avCx + 14f * k, cy + 14f * k, 4.5f * k, fill);
    }

    private void drawText(Canvas c, String s, float x, float y, float sz, int col, boolean bold) {
        tp.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tp.setTextSize(sz);
        tp.setColor(col);
        c.drawText(s, x, y, tp);
    }

    private void drawQuad(Canvas c, float x0, float y0, float x1, float y1,
                          float x2, float y2, float x3, float y3) {
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
        if (x3 != x2 || y3 != y2) path.lineTo(x3, y3);
        path.close();
        c.drawPath(path, fill);
    }
}
