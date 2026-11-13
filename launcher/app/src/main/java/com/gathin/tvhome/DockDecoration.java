package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix backdropMatrix = new Matrix();
    private final RectF backdropSrc = new RectF();
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** Frosted Glass Dock (config.xml dock_blur): softened backdrop crop + 0x18FFFFFF->0x0AFFFFFF fill + 1.5dp rim. */
    private final boolean dockBlur;
    /** Padding all round the tiles equal to the gap between them (config.xml dock_even_padding). */
    private final boolean evenPadding;
    /** The band's top and bottom the glass gradient was made for; it is remade when they move. */
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;
    private float backdropLeft = Float.NaN, backdropTop = Float.NaN, backdropRight = Float.NaN, backdropBottom = Float.NaN;
    private Bitmap backdropBitmap;
    private BitmapShader backdropShader;
    /** Draw the band and its edge (config.xml dock_band); false leaves the tiles floating on the wallpaper. */
    private final boolean bandVisible;
    /** single_row: the tiles after the dock share its row, so they take the same offsets to stay level with it. */
    private final boolean singleRow;
    /** dock_band_all: in a single row, the band runs behind every app instead of the dock apps only. */
    private final boolean bandAll;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param opacity     the plain band's opacity in percent (dock_opacity), so a wallpaper can show through it.
     * @param lightGlass  a light frosted band like the pills, for a sky or wallpaper behind it.
     * @param evenPadding the band's padding is the gap between the tiles on all four sides (dock_even_padding).
     * @param bandVisible false skips the band and its edge, so the tiles float on the wallpaper (dock_band).
     * @param singleRow   every app sits in the dock's row (single_row); the band covers the dock apps only, unless bandAll.
     * @param bandAll     with singleRow, the band runs behind every app in the row (dock_band_all).
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean bandVisible, boolean singleRow, boolean bandAll) {
        this(dockSize, density, translucent, names, opacity, lightGlass, evenPadding, bandVisible, singleRow, bandAll, false);
    }

    /**
     * @param dockBlur Glass Dock mode: frosted translucent fill, optional softened backdrop crop, and 1.5dp specular rim.
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean bandVisible, boolean singleRow, boolean bandAll, boolean dockBlur) {
        this.dockSize = dockSize;
        this.singleRow = singleRow;
        this.bandAll = singleRow && bandAll;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        this.dockBlur = dockBlur;
        this.evenPadding = evenPadding;
        this.bandVisible = bandVisible;
        band.setColor(translucent ? 0x8C121014 : (Math.round(opacity * 2.55f) << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(DockBlur.rimStrokeWidthPx(density, dockBlur));
        edge.setColor(DockBlur.rimColor(dockBlur, lightGlass, translucent));
    }

    /** Sets an optional wallpaper crop behind the Glass Dock; softened once off-frame and drawn via BitmapShader. */
    public void setBackdropCrop(Bitmap crop) {
        Bitmap softened = DockBlur.softenBackdropCrop(crop);
        if (this.backdropBitmap != null && this.backdropBitmap != softened) {
            this.backdropBitmap.recycle();
        }
        this.backdropBitmap = softened;
        if (softened != null) {
            this.backdropShader = new BitmapShader(softened, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            int w = softened.getWidth(), h = softened.getHeight();
            this.backdropSrc.set(0f, h * 0.65f, w, h * 0.92f);
            this.backdropPaint.setShader(this.backdropShader);
        } else {
            this.backdropShader = null;
            this.backdropPaint.setShader(null);
        }
        this.backdropLeft = Float.NaN;
    }

    /** Concentric corners: the band's radius is the tiles' radius plus the band's padding around them. */
    static float radiusPx(float tileRadiusPx, float paddingPx) {
        return tileRadiusPx + paddingPx;
    }

    /**
     * The band's padding on every side for dock_even_padding: the gap between two dock tiles (a cell minus its tile, as
     * AppAdapter.tileWidthPx lays them out), capped by the room the grid leaves at its edges.
     */
    static float evenPaddingPx(int innerWidthPx, int columns, int tileWidthPx, float roomPx) {
        float gap = columns > 0 ? innerWidthPx / (float) columns - tileWidthPx : 0f;
        return Math.max(0f, Math.min(gap, roomPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && (singleRow || pos < dockSize)) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!bandVisible) return;
        boolean any = false;
        int cardWidth = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || (!bandAll && pos >= dockSize)) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            cardWidth = card.getWidth();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat (#454). Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        // 22dp at the sides (the grid's 10dp padding makes room): a focused first or last tile grows about 7dp
        // outwards and still keeps about the band's top margin from its rounded end.
        float sidePad = 22 * density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        float radius = AppAdapter.cornerRadius(cardWidth, density);
        if (evenPadding) {
            // The same padding on all four sides, so the band's corners are concentric with the tiles' everywhere.
            int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            // A single row's tiles can run off the screen, so only its top limits the padding; its ends are clamped below.
            float room = (singleRow ? rect.top : Math.min(rect.top, Math.min(rect.left, parent.getWidth() - rect.right))) - density;
            float even = evenPaddingPx(inner, dockSize, cardWidth, room);
            rect.inset(-even, -even);
            radius = radiusPx(radius, even);
        } else {
            // dock_even_padding off: the band as before, with its fixed 26 dp corners.
            rect.inset(singleRow ? -sidePad : -side, -pad);
            radius = 26 * density;
        }
        if (singleRow) {
            // Tiles past the screen's edge: the band stops at the grid's edge with its rounded end instead of being
            // sliced square there.
            rect.left = Math.max(rect.left, density);
            rect.right = Math.min(rect.right, parent.getWidth() - density);
        }
        rect.bottom += namesRoom;
        if (dockBlur) {
            Shader savedShader = band.getShader();
            int savedColor = band.getColor();
            band.setShader(null);
            band.setColor(0x50000000);
            float shadowDy = 4f * density;
            rect.offset(0f, shadowDy);
            c.drawRoundRect(rect, radius, radius, band);
            rect.offset(0f, -shadowDy);
            band.setColor(savedColor);
            band.setShader(savedShader);
        }
        if (dockBlur && backdropShader != null) {
            if (rect.left != backdropLeft || rect.top != backdropTop
                    || rect.right != backdropRight || rect.bottom != backdropBottom) {
                backdropMatrix.setRectToRect(backdropSrc, rect, Matrix.ScaleToFit.FILL);
                backdropShader.setLocalMatrix(backdropMatrix);
                backdropLeft = rect.left;
                backdropTop = rect.top;
                backdropRight = rect.right;
                backdropBottom = rect.bottom;
            }
            c.drawRoundRect(rect, radius, radius, backdropPaint);
        }
        int[] gradientColors = DockBlur.frostedGradientColors(dockBlur, lightGlass);
        if (gradientColors != null && (rect.top != shaderTop || rect.bottom != shaderBottom)) {
            band.setShader(dockBlur
                    ? new LinearGradient(0, rect.top, 0, rect.bottom,
                            new int[]{0x38FFFFFF, 0x18141824, 0x24FFFFFF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, rect.top, 0, rect.bottom, gradientColors[0], gradientColors[1], Shader.TileMode.CLAMP));
            if (dockBlur) {
                edge.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[]{0x90FFFFFF, 0x24FFFFFF, 0x48FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            }
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
