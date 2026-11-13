package com.gathin.tvhome;

import android.graphics.Bitmap;

/** Pure math and pre-softening helpers for the Glass Dock ({@code dock_blur}); no RenderEffect per frame. */
final class DockBlur {

    static final int FROSTED_TOP_COLOR = 0x18FFFFFF;
    static final int FROSTED_BOTTOM_COLOR = 0x0AFFFFFF;
    static final int SPECULAR_RIM_COLOR = 0x48FFFFFF;
    static final float SPECULAR_RIM_DP = 1.5f;

    private DockBlur() {}

    static int frostedTopColor() { return FROSTED_TOP_COLOR; }

    static int frostedBottomColor() { return FROSTED_BOTTOM_COLOR; }

    static int[] frostedGradientColors(boolean dockBlur, boolean lightGlass) {
        if (dockBlur) return new int[]{FROSTED_TOP_COLOR, FROSTED_BOTTOM_COLOR};
        return lightGlass ? new int[]{0x24FFFFFF, 0x0DFFFFFF} : null;
    }

    static float rimStrokeWidthPx(float density, boolean dockBlur) {
        return density <= 0f ? 0f : (dockBlur ? SPECULAR_RIM_DP : 1f) * density;
    }

    static int rimColor(boolean dockBlur, boolean lightGlass, boolean translucent) {
        return dockBlur ? SPECULAR_RIM_COLOR : (lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    static int[] cropRectPx(int bitmapW, int bitmapH, int screenW, int screenH,
                            float bandLeft, float bandTop, float bandRight, float bandBottom) {
        if (bitmapW <= 0 || bitmapH <= 0 || screenW <= 0 || screenH <= 0
                || bandRight <= bandLeft || bandBottom <= bandTop) return null;
        float sx = bitmapW / (float) screenW, sy = bitmapH / (float) screenH;
        int l = clamp(Math.round(bandLeft * sx), bitmapW);
        int t = clamp(Math.round(bandTop * sy), bitmapH);
        int r = clamp(Math.round(bandRight * sx), bitmapW);
        int b = clamp(Math.round(bandBottom * sy), bitmapH);
        return (r > l && b > t) ? new int[]{l, t, r, b} : null;
    }

    static void softenPixels(int[] pixels, int w, int h, int radius, float saturationBoost) {
        if (pixels == null || w <= 0 || h <= 0 || pixels.length < w * h) return;
        int total = w * h;
        if (radius > 0) {
            int[] scratch = new int[total];
            blurPass(pixels, scratch, w, h, radius, true);
            blurPass(scratch, pixels, w, h, radius, false);
        }
        if (saturationBoost != 1f) {
            for (int i = 0; i < total; i++) {
                int c = pixels[i];
                int r = (c >>> 16) & 0xFF, g = (c >>> 8) & 0xFF, b = c & 0xFF;
                float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                pixels[i] = (c & 0xFF000000)
                        | (clamp(Math.round(luma + (r - luma) * saturationBoost), 255) << 16)
                        | (clamp(Math.round(luma + (g - luma) * saturationBoost), 255) << 8)
                        | clamp(Math.round(luma + (b - luma) * saturationBoost), 255);
            }
        }
    }

    static Bitmap softenBackdropCrop(Bitmap source) {
        if (source == null || source.isRecycled()) return null;
        int srcW = source.getWidth(), srcH = source.getHeight();
        if (srcW <= 0 || srcH <= 0) return null;
        int dstW = Math.min(srcW, 96);
        int dstH = Math.max(1, Math.round(srcH * (dstW / (float) srcW)));
        Bitmap scaled = (dstW == srcW && dstH == srcH) ? source : Bitmap.createScaledBitmap(source, dstW, dstH, true);
        int[] pixels = new int[dstW * dstH];
        scaled.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH);
        if (scaled != source) scaled.recycle();
        softenPixels(pixels, dstW, dstH, 3, 1.15f);
        return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888);
    }

    private static void blurPass(int[] src, int[] dst, int w, int h, int radius, boolean horiz) {
        int outer = horiz ? h : w, inner = horiz ? w : h;
        for (int o = 0; o < outer; o++) {
            for (int i = 0; i < inner; i++) {
                int s = Math.max(0, i - radius), e = Math.min(inner - 1, i + radius), n = e - s + 1;
                int a = 0, r = 0, g = 0, b = 0;
                for (int k = s; k <= e; k++) {
                    int c = src[horiz ? (o * w + k) : (k * w + o)];
                    a += (c >>> 24) & 0xFF;
                    r += (c >>> 16) & 0xFF;
                    g += (c >>> 8) & 0xFF;
                    b += c & 0xFF;
                }
                dst[horiz ? (o * w + i) : (i * w + o)] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }
}
