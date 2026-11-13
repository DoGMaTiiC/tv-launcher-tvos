package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import java.util.HashMap;

/**
 * Atmospheric blur buffers, 9-slice cached Gaussian drop shadows, and AGSL Liquid Glass shader
 * (API 33+, with graceful frosted fallback on API 26-32) adapted from MIT-licensed free-my-tv.
 */
final class DockBlur {

    static final int FROSTED_TOP_COLOR = 0x18FFFFFF;
    static final int FROSTED_BOTTOM_COLOR = 0x0AFFFFFF;
    static final int SPECULAR_RIM_COLOR = 0x48FFFFFF;
    static final float SPECULAR_RIM_DP = 1.5f;

    static final int FW = 192, FH = 108, GW = 480, GH = 270;
    static final float SHADOW_BLUR = 26f, DEEP_BLUR = 30f;

    private static final String LIQUID_AGSL =
            "uniform shader imageA,imageB;uniform float mixB;uniform float2 imageScale,offset,center,halfSize;"
                    + "uniform float radius;uniform half4 tint;uniform float lift,band,refraction,specular;"
                    + "float hash(float2 p){p=fract(p*float2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}"
                    + "half3 bgAt(float2 p){half3 a=imageA.eval(p*imageScale).rgb;return mixB<=0.0?a:mix(a,imageB.eval(p*imageScale).rgb,half(mixB));}"
                    + "half4 main(float2 xy){"
                    + "float2 d=xy-center,q=abs(d)-(halfSize-radius);"
                    + "float sd=length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;"
                    + "float aa=clamp(0.5-sd,0.0,1.0);if(aa<=0.0)return half4(0.0);"
                    + "float2 sg=float2(d.x<0.0?-1.0:1.0,d.y<0.0?-1.0:1.0);"
                    + "float2 n=(q.x>0.0&&q.y>0.0)?normalize(q)*sg:(q.x>q.y?float2(sg.x,0.0):float2(0.0,sg.y));"
                    + "float inner=-sd,e=clamp(inner/band,0.0,1.0),bend=(1.0-e)*(1.0-e);"
                    + "float2 p=xy+offset-n*(bend*refraction);"
                    + "half3 bg;if(bend>0.01){float2 ca=n*(bend*refraction*0.22);bg=half3(bgAt(p+ca).r,bgAt(p).g,bgAt(p-ca).b);}else{bg=bgAt(p);}"
                    + "half l=dot(bg,half3(0.2126,0.7152,0.0722));bg=mix(half3(l),bg,1.18)*1.06;"
                    + "half3 col=mix(bg,tint.rgb,tint.a)+half3(lift);"
                    + "float ny=d.y/halfSize.y;col*=half(1.0-0.12*smoothstep(0.1,1.0,ny));col+=half3(0.045*(1.0-smoothstep(-1.0,-0.15,ny)));"
                    + "float2 L=float2(-0.4,-0.917);float facing=clamp(dot(n,L),0.0,1.0),back=clamp(-dot(n,L),0.0,1.0);"
                    + "float rim=1.0-smoothstep(0.0,2.4,inner),halo=1.0-smoothstep(0.0,11.0,inner);"
                    + "col+=half3((rim*(0.22+0.78*facing+0.4*back)+halo*(0.12*facing+0.05*back))*specular);"
                    + "col+=half3((hash(xy)-0.5)*0.0039);return half4(col*half(aa),half(aa));}";

    static final class Atmosphere {
        final Bitmap backdropBitmap;
        final int[] glassPixels;

        Atmosphere(Bitmap backdropBitmap, int[] glassPixels) {
            this.backdropBitmap = backdropBitmap;
            this.glassPixels = glassPixels;
        }
    }

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
            box(pixels, scratch, w, h, radius, true);
            box(scratch, pixels, w, h, radius, false);
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

    /**
     * Produces the ultra-smooth atmospheric wash (192x108) for the full-screen background and the
     * lightly blurred 480x270 buffer sampled by the Liquid Glass shader, for Night or Day mode.
     */
    static Atmosphere makeAtmosphere(Bitmap src, boolean nightMode) {
        if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
        int[] frost = reduce(src, FW, FH, 3, 3);
        int[] glass = reduce(src, GW, GH, 2, 2);
        if (nightMode) {
            tint(frost, 0.76f, 11f, 11f, 13f);
            tint(glass, 0.56f, 11f, 11f, 13f);
        } else {
            tint(frost, 0.82f, 255f, 255f, 255f);
            tint(glass, 0.74f, 255f, 255f, 255f);
        }
        Bitmap bg = Bitmap.createBitmap(frost, FW, FH, Bitmap.Config.ARGB_8888);
        return new Atmosphere(bg, glass);
    }

    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = (b.getWidth() == w && b.getHeight() == h) ? b : Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != small) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != full) small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w;
        int step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int span = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + clamp(i, n - 1) * step];
                sa += (p >>> 24) & 255;
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = ((sa / span) << 24)
                        | ((sr / span) << 16) | ((sg / span) << 8) | (sb / span);
                int add = in[base + Math.min(n - 1, i + r + 1) * step];
                int sub = in[base + Math.max(0, i - r) * step];
                sa += ((add >>> 24) & 255) - ((sub >>> 24) & 255);
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    private static void tint(int[] px, float amount, float tr, float tg, float tb) {
        float keep = 1f - amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + tr * amount);
            int g = Math.round(((p >> 8) & 255) * keep + tg * amount);
            int b = Math.round((p & 255) * keep + tb * amount);
            px[i] = 0xFF000000 | (clamp(r, 255) << 16) | (clamp(g, 255) << 8) | clamp(b, 255);
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : Math.min(v, max);
    }

    /** 9-slice cached Gaussian drop shadow renderer adapted from free-my-tv's Shadows.java. */
    static final class ShadowCache {
        private final HashMap<Long, Bitmap> cache = new HashMap<>();
        private final Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final float[] xs = new float[4], ys = new float[4];
        private final int[] sx = new int[4], sy = new int[4];

        void draw(Canvas c, RectF shape, float r, float blur, int argb) {
            if (Color.alpha(argb) == 0 || shape.width() <= 0 || shape.height() <= 0) return;
            int radius = Math.max(0, Math.round(r));
            int b = Math.max(1, Math.round(blur));
            int m = (int) Math.ceil(b * 1.8f) + 1;
            int corner = radius + 2 * m;
            int w = slices(shape.left - m, shape.right + m, corner, xs, sx);
            int h = slices(shape.top - m, shape.bottom + m, corner, ys, sy);
            Bitmap mask = mask(radius, b, m, w, h);
            tint.setColor(argb);
            for (int i = 0; i < 3; i++) {
                if (sy[i + 1] == sy[i]) continue;
                for (int j = 0; j < 3; j++) {
                    if (sx[j + 1] == sx[j]) continue;
                    src.set(sx[j], sy[i], sx[j + 1], sy[i + 1]);
                    dst.set(xs[j], ys[i], xs[j + 1], ys[i + 1]);
                    c.drawBitmap(mask, src, dst, tint);
                }
            }
        }

        private static int slices(float from, float to, int corner, float[] out, int[] src) {
            if (to - from >= 2 * corner + 1) {
                int size = 2 * corner + 1;
                out[0] = from; out[1] = from + corner; out[2] = to - corner; out[3] = to;
                src[0] = 0; src[1] = corner; src[2] = corner + 1; src[3] = size;
                return size;
            }
            int size = Math.max(8, Math.round((to - from) / 8f) * 8);
            out[0] = from; out[1] = from; out[2] = to; out[3] = to;
            src[0] = 0; src[1] = 0; src[2] = size; src[3] = size;
            return size;
        }

        private Bitmap mask(int radius, int b, int m, int w, int h) {
            long key = ((long) radius << 48) | ((long) b << 32) | ((long) w << 16) | h;
            Bitmap mask = cache.get(key);
            if (mask != null) return mask;
            Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF000000);
            p.setMaskFilter(new BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL));
            new Canvas(full).drawRoundRect(m, m, w - m, h - m, radius, radius, p);
            mask = full.extractAlpha();
            full.recycle();
            if (cache.size() > 48) cache.clear();
            cache.put(key, mask);
            return mask;
        }
    }

    /** True AGSL Liquid Glass drawer on Android 13+ (API 33+) with frosted fallback for API 26-32. */
    static final class LiquidRenderer {
        private RuntimeShader liquid;
        private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap glassBitmap;
        private BitmapShader glassShader;
        private boolean hasImage;

        LiquidRenderer() {
            flatRim.setStyle(Paint.Style.STROKE);
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    liquid = new RuntimeShader(LIQUID_AGSL);
                    shaderPaint.setShader(liquid);
                } catch (RuntimeException | LinkageError ignored) {
                    liquid = null;
                }
            }
        }

        void setGlassPixels(int[] pixels) {
            if (pixels == null || pixels.length < GW * GH) return;
            if (glassBitmap == null) glassBitmap = Bitmap.createBitmap(GW, GH, Bitmap.Config.ARGB_8888);
            glassBitmap.setPixels(pixels, 0, GW, 0, 0, GW, GH);
            glassShader = new BitmapShader(glassBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= 33) {
                glassShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            }
            hasImage = true;
            if (Build.VERSION.SDK_INT >= 33 && liquid != null) {
                liquid.setInputShader("imageA", glassShader);
                liquid.setInputShader("imageB", glassShader);
                liquid.setFloatUniform("mixB", 0f);
            }
        }

        void draw(Canvas c, RectF r, float radius, int screenW, int screenH,
                  float offX, float offY, boolean nightMode, float lift, float specular) {
            if (r.width() <= 1f || r.height() <= 1f) return;
            float k = screenW > 0 ? screenW / 1920f : 1f;
            float rr = Math.min(radius, Math.min(r.width(), r.height()) / 2f);
            if (Build.VERSION.SDK_INT >= 33 && liquid != null && hasImage) {
                int tint = nightMode ? 0x0B0B0D : 0xF2F2F7;
                float tintAlpha = nightMode ? 0.36f : 0.24f;
                liquid.setFloatUniform("imageScale", (float) GW / Math.max(1, screenW),
                        (float) GH / Math.max(1, screenH));
                liquid.setFloatUniform("offset", offX, offY);
                liquid.setFloatUniform("center", r.centerX(), r.centerY());
                liquid.setFloatUniform("halfSize", r.width() / 2f, r.height() / 2f);
                liquid.setFloatUniform("radius", rr);
                liquid.setFloatUniform("tint", Color.red(tint) / 255f,
                        Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
                liquid.setFloatUniform("lift", lift);
                liquid.setFloatUniform("band", Math.max(1f, 28f * k));
                liquid.setFloatUniform("refraction", 38f * k);
                liquid.setFloatUniform("specular", specular);
                c.drawRect(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + 1f, shaderPaint);
                return;
            }
            flatFill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    nightMode ? new int[]{0x7A242429, 0x99121216} : new int[]{0xCCFFFFFF, 0xAAE8E8EE},
                    null, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rr, rr, flatFill);
            flatFill.setShader(null);
            flatRim.setStrokeWidth(Math.max(1f, 1.5f * k));
            flatRim.setColor(nightMode ? 0x4DFFFFFF : 0xCCFFFFFF);
            c.drawRoundRect(r, rr, rr, flatRim);
        }
    }
}
