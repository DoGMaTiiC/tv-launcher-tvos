package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order,
 * then empty slots up to a full row, see {@link DockSlots}), then the module cards the owner chose to show
 * (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        void onLibraryRequested();
        void onLibraryFocused();
        /** Hold OK on a tile: the activity shows the tile menu (move, remove from Home, app info). */
        void onTileLongPress(int position, View card);
        void onLibraryUp();
        /** A tile whose launch_actions entry is "input:<id>": switch to that TV input instead of opening an app. */
        void onOpenInput(String inputId);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, EMPTY = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** uniform_tiles card fill, top and bottom colour; null until sampled from the icon. */
        int[] backdrop;
        /** True for virtual tvOS showcase entries when fewer than 24 apps are installed. */
        final boolean virtual;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0, false);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this(kind, label, component, banner, icon, caption, accent, false);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent, boolean virtual) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
            this.virtual = virtual;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** HeadBoardIconRendering: restingSize 250x150pt (5:3), focusedSize 306x184pt, focusScale 1.2267f. */
    static final float TVOS_W_DP = 125f, TVOS_H_DP = 75f, TVOS_FOCUS_SCALE = 1.2267f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    static int tvosTileWidthPx(int innerWidthPx, int columns, float density) {
        int target = Math.round(TVOS_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return target;
        int cell = innerWidthPx / columns;
        int maxFit = Math.round(cell - 8f * density);
        return Math.max(Math.round(80f * density), Math.min(target, maxFit));
    }

    static int tvosTileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * TVOS_H_DP / TVOS_W_DP);
    }

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return itemHeightPx(tileHeightPx, true, density);
    }

    /** The same for either layout: the plain grid (dock_row false) uses the compact spacing. */
    static int itemHeightPx(int tileHeightPx, boolean dockRow, float density) {
        return tileHeightPx + Math.round((dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density);
    }

    /**
     * dock_row false: 4dp padding above and below, a 4dp gap and a 20dp name label, and 16dp kept under the last
     * row. The plain grid fits more rows on the screen, and with item_app.xml's roomier spacing the tiles came
     * out small with wide gaps between them.
     */
    static final float COMPACT_ITEM_EXTRA_DP = 4 + 4 + 4 + 20;
    static final float COMPACT_BOTTOM_ROOM_DP = 16;

    /** item_app.xml: 8dp padding above and below, a 7dp gap and the 22dp name label under the tile. */
    static final float ITEM_EXTRA_DP = 45;
    /** DockDecoration's offsets above and below the dock row. */
    static final float DOCK_EXTRA_DP = 6 + 12;
    /** Room kept under the last row rows_below_dock asks for (MainActivity places the dock with the same). */
    static final float BOTTOM_ROOM_DP = 30;
    /** Room kept above the dock for the dock title (48sp hero name plus its 40dp gap) when dock_title is on. */
    static final float TITLE_ROOM_DP = 104;
    /** dock_row false: room above the first row for its zoom and glass (MainActivity places the grid with it). */
    static final float TOP_ROOM_DP = 12;

    /**
     * Tile width that fits the columns across and, with rows_below_dock > 0, the dock (with its title above it,
     * if shown) plus that many rows down the grid's height; the smaller wins. With rows_below_dock = 0, or before
     * the grid has a height, only the width counts, as before. Without this, 2 or 3 rows below the dock did not
     * fit: the dock was pushed to the top and its title drew over the clock. With dock_row false there is no band
     * or title to make room for: the first row plus rows_below_dock rows fit under a small top margin.
     */
    static int tileWidthPx(int innerWidthPx, int gridHeightPx, int columns, int rowsBelowDock, boolean dockTitle,
                           boolean dockRow, float density) {
        int w = tileWidthPx(innerWidthPx, columns, density);
        if (gridHeightPx <= 0 || rowsBelowDock <= 0) return w;
        float reserved = (dockRow ? DOCK_EXTRA_DP + BOTTOM_ROOM_DP + (dockTitle ? TITLE_ROOM_DP : 0)
                : TOP_ROOM_DP + COMPACT_BOTTOM_ROOM_DP) * density;
        float perItem = (gridHeightPx - reserved) / (rowsBelowDock + 1);
        float tileH = perItem - (dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density;
        int byHeight = Math.round(tileH * DESIGN_W_DP / DESIGN_H_DP);
        // Shrink at most to MIN_HEIGHT_FIT of the width-only size. Below that the tiles look lost in their wide
        // columns (2 rows under a titled dock came out at 80dp); the rows that still don't fit run off the bottom
        // and scroll, and MainActivity keeps the title's room above the dock.
        return Math.max(Math.max(Math.round(80 * density), Math.round(w * MIN_HEIGHT_FIT)), Math.min(w, byHeight));
    }

    /** The smallest share of the width-only tile size that fitting rows_below_dock into the height may go to. */
    static final float MIN_HEIGHT_FIT = 0.85f;

    private final Context ctx;
    private final int rowsBelowDock;
    private final boolean dockTitle;
    /** The owner's saved order (reorder button, Order): key -> rank. Empty when there is none or it no longer applies. */
    private Map<String, Integer> savedRanks = Collections.emptyMap();
    /** Dock size recorded with the saved order (Order.docked), or -1: then dock_apps says which tiles are docked. */
    private int savedDocked = -1;
    /** Reorder mode: no empty dock slots, so every position holds a tile that can move. */
    private boolean reordering;
    private final TileListener listener;
    private final int dockSize;
    /** config dock_row: false makes the dock apps the first tiles of one plain grid (names shown, no empty slots). */
    private final boolean dockRow;
    /** config single_row: tiles sit in one sideways row, each as wide as a grid column would be. */
    private final boolean singleRow;
    /** config always_show_names: the alpha of an unfocused tile's name (0 hides it). */
    private final float restNameAlpha;
    private final float gridRestNameAlpha;
    private final boolean tileTilt;
    private final boolean dockBlur;
    private final boolean topStage;
    /** cinematic_home: only then does a launch record the last-opened app the shelf uses. */
    private final boolean cinematic;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock). */
    private final boolean fillDock;
    /** Dock apps found at the last reload, and the empty slots after them. */
    private int docked, emptySlots;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Apps pre-seeded into the App library's "added" set (config.xml library_apps); empty by default. */
    private final Set<String> libraryPreload;
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showLibrary;
    /** Packages with a leanback entry at the last reload(): their tiles launch with the leanback category. */
    private Set<String> leanbackPackages = new HashSet<>();
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    /** The focus zoom may overshoot (config.xml, focus_overshoot). */
    private final boolean focusOvershoot;
    /** Package to the action its tile sends instead of the start screen (config.xml, launch_actions). */
    private final Map<String, String> launchActions;
    /** Hold OK opens the activity's tile menu (config.xml, tile_menu); false opens App info, as before. */
    private final boolean tileMenu;
    /** Tile names and bundled banners that replace the app's own (config.xml, app_labels and app_banners). */
    private final Map<String, String> appLabels, appBanners;
    /** Every app tile is its icon on a card tinted from that icon, never its banner (config.xml, uniform_tiles). */
    private final boolean uniformTiles;
    /** Packages drawn from their icon and name even when they have a banner (config.xml icon_tiles). */
    private final Set<String> iconTiles;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.fillDock = cfg.fillDock;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.libraryPreload = new HashSet<>(cfg.libraryApps);
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.tileTilt = cfg.animations && cfg.tileTilt;
        this.dockBlur = cfg.dockBlur;
        this.topStage = cfg.heroArt && cfg.dockBlur;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.showLibrary = LibraryApps.showPill(cfg.appLibrary, cfg.libraryButton, cfg.showLibraryPill);
        this.reducedMotion = cfg.reducedMotion;
        this.focusOvershoot = cfg.focusOvershoot;
        this.rowsBelowDock = cfg.rowsBelowDock;
        this.dockTitle = cfg.dockTitle;
        this.dockRow = cfg.dockRow;
        this.singleRow = cfg.singleRow;
        this.restNameAlpha = cfg.alwaysShowNames ? 0.75f : 0f;
        this.gridRestNameAlpha = cfg.alwaysShowNames ? 0.75f : ((this.dockBlur || this.topStage) ? 0f : (this.tileTilt ? 0.65f : 0f));
        this.cinematic = cfg.cinematicHome;
        this.launchActions = cfg.launchActions;
        this.iconTiles = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.icon_tiles)));
        this.tileMenu = cfg.tileMenu;
        this.appLabels = cfg.appLabels;
        this.appBanners = cfg.appBanners;
        this.uniformTiles = cfg.uniformTiles;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards, List<ResolveInfo> leanback) {
        PackageManager pm = ctx.getPackageManager();
        // The LEANBACK query is shared with the module-card check (MainActivity); copy it before adding
        // library apps so the caller's list stays as it was.
        List<ResolveInfo> found = new ArrayList<>(leanback);
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        leanbackPackages = tvPackages;
        // Copy before merging: LibraryApps.load() may return an immutable empty set (#419).
        Set<String> added = new HashSet<>(LibraryApps.load(ctx));
        added.addAll(libraryPreload);
        Set<String> removed = LibraryApps.loadRemoved(ctx);
        if (!added.isEmpty()) {
            Set<String> seen = new HashSet<>(tvPackages);
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (ri.activityInfo != null && added.contains(ri.activityInfo.packageName)
                        && seen.add(ri.activityInfo.packageName)) found.add(ri);
            }
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !ri.activityInfo.exported
                    || !LibraryApps.visible(tvPackages.contains(pkg), hidden.contains(pkg), added.contains(pkg), removed.contains(pkg))) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
            // redraw the tile name as something else (#117).
            String label = card != null ? card.label : Cards.cleanLabel(
                    appLabels.containsKey(pkg) ? appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg);
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = null;
            // uniform_tiles draws the icon, so an app's banner is never loaded. A module card keeps its banner.
            if (!(uniformTiles && card == null)) {
                banner = bundledBanner(appBanners.get(pkg));
                if (banner == null) banner = iconTiles.contains(pkg) ? iconBanner(ri.loadIcon(pm)) : loadBanner(pm, ri);
            }
            // The icon is only drawn (and sampled for the glow) when there is no banner, so a tile with a
            // banner skips loading and decoding it.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, label, component, banner, icon, card.caption, card.accent));
            } else {
                fresh.add(new Entry(label, component, banner, icon));
            }
        }
        Collections.sort(fresh, (a, b) -> {
            // The owner's own order first; tiles it does not know (new installs) follow by the rule below.
            int saved = Order.compare(savedRanks, a.component.flattenToShortString(), b.component.flattenToShortString());
            if (saved != 0) return saved;
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings && !topStage
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        if (topStage && fresh.size() < SPEC_LABELS.length) {
            Map<String, Entry> realByPkg = new HashMap<>();
            for (Entry entry : fresh) {
                if (!realByPkg.containsKey(entry.pkg)) realByPkg.put(entry.pkg, entry);
            }
            List<Entry> showcase = new ArrayList<>(SPEC_LABELS.length);
            for (int i = 0; i < SPEC_LABELS.length; i++) {
                String sp = SPEC_PKGS[i];
                int bar = sp.indexOf('|');
                String pkg = bar < 0 ? sp : sp.substring(0, bar);
                Entry real = realByPkg.remove(pkg);
                if (real == null && bar >= 0) real = realByPkg.remove(sp.substring(bar + 1));
                Drawable brandBanner = new BrandTileDrawable(i);
                if (real != null) {
                    showcase.add(new Entry(real.kind, SPEC_LABELS[i], real.component, brandBanner, null, null, SPEC_ACCENTS[i], false));
                } else {
                    showcase.add(new Entry(Entry.APP, SPEC_LABELS[i],
                            new ComponentName(pkg, pkg + ".ShowcaseActivity"),
                            brandBanner, null, null, SPEC_ACCENTS[i], true));
                }
            }
            fresh = showcase;
            docked = Math.min(dockSize, fresh.size());
            emptySlots = 0;
        } else {
            if (topStage) {
                for (int i = 0; i < fresh.size(); i++) {
                    Entry e = fresh.get(i);
                    int idx = showcaseIndexForPkg(e.pkg);
                    if (idx >= 0) {
                        fresh.set(i, new Entry(e.kind, e.label, e.component,
                                new BrandTileDrawable(idx), null, null, SPEC_ACCENTS[idx], false));
                    }
                }
            }
            docked = 0;
            while (docked < fresh.size() && isDocked(fresh.get(docked))) docked++;
            emptySlots = dockRow && !reordering ? DockSlots.empty(docked, dockSize, fresh.size(), fillDock) : 0;
            for (int i = 0; i < emptySlots; i++) {
                fresh.add(docked + i, new Entry(Entry.EMPTY, "", new ComponentName(ctx, "empty-dock-slot-" + i),
                        null, null, null, 0));
            }
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** icon_tiles: the app's icon centred on a dark banner-shaped tile, for apps whose own banner crops badly. */
    private Drawable iconBanner(Drawable icon) {
        int w = 320, h = 180, side = 104;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(0xFF15202B);
        icon.setBounds((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        icon.draw(canvas);
        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
    }

    /** A banner_* drawable bundled with the launcher, or null for none or a name that is not one. */
    private Drawable bundledBanner(String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    /** Lets go of every tile and its artwork (Home is in the background and memory is short). */
    void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 1) {
            View row = LayoutInflater.from(ctx).inflate(R.layout.row_app_library, parent, false);
            View button = row.findViewById(R.id.btnLibrary);
            button.setOnClickListener(v -> listener.onLibraryRequested());
            button.setOnFocusChangeListener((v, focused) -> { if (focused) listener.onLibraryFocused(); });
            button.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) listener.onLibraryUp();
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            return new RecyclerView.ViewHolder(row) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = topStage
                ? tvosTileWidthPx(inner, dockSize, density)
                : tileWidthPx(inner, parent.getHeight(), dockSize, rowsBelowDock, dockTitle, dockRow, density);
        int h = topStage ? tvosTileHeightPx(w) : tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round((topStage ? 12 : (dockRow ? 7 : 4)) * density);
        if (topStage) {
            nameLp.height = Math.round(20 * density);
            v.setPadding(v.getPaddingLeft(), Math.round(6 * density), v.getPaddingRight(), Math.round(2 * density));
        } else if (!dockRow) {
            // Compact spacing (COMPACT_ITEM_EXTRA_DP): 4dp item padding and a 20dp name instead of 8dp and 22dp.
            nameLp.height = Math.round(20 * density);
            int pad = Math.round(4 * density);
            v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), pad);
        }
        name.setLayoutParams(nameLp);
        // A sideways row would stretch a match_parent item across the whole screen: give it a grid column's width.
        if (singleRow) v.getLayoutParams().width = inner / dockSize;
        if (uniformTiles) {
            // tvOS-style tile: a large centred mark, no caption inside the card.
            View icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            iconLp.width = iconLp.height = Math.round(h * ICON_SHARE);
            iconLp.bottomMargin = 0;
            icon.setLayoutParams(iconLp);
        } else if (h < Math.round(DESIGN_H_DP * density)) {
            // Shrunk tiles (e.g. 5 or 6 columns): scale icon and bottom margin proportionally with tile height so the label does not overlap the icon.
            View icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            float scale = (float) h / (DESIGN_H_DP * density);
            iconLp.width = iconLp.height = Math.round(48f * density * scale);
            iconLp.bottomMargin = Math.round(14f * density * scale);
            icon.setLayoutParams(iconLp);
            View label = v.findViewById(R.id.label);
            label.setPadding(label.getPaddingLeft(), label.getPaddingTop(), label.getPaddingRight(), Math.round(4f * density));
        }
        Holder holder = new Holder(v, listener, animations, advancedEffects, tileTilt, dockBlur, topStage, reducedMotion, restNameAlpha, focusOvershoot);
        if (tileTilt) {
            holder.card.setCameraDistance(TileTilt.cameraDistancePx(ctx.getResources().getDisplayMetrics().density));
        }
        if (dockBlur && android.os.Build.VERSION.SDK_INT >= 28) {
            holder.card.setOutlineSpotShadowColor(0xE0000000);
            holder.card.setOutlineAmbientShadowColor(0x88000000);
        }
        return holder;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (isLibraryAt(position)) return;
        Holder h = (Holder) holder;
        Entry e = items.get(position);
        if (e.kind == Entry.EMPTY) {
            // A free dock slot keeps its place in the row and the band, but shows nothing and takes no focus.
            h.card.setVisibility(View.INVISIBLE);
            h.card.setFocusable(false);
            h.name.setVisibility(View.INVISIBLE);
            h.banner.setImageDrawable(null);
            h.icon.setImageDrawable(null);
            h.card.setOnClickListener(null);
            h.card.setOnLongClickListener(null);
            return;
        }
        h.card.setVisibility(View.VISIBLE);
        h.card.setFocusable(true);
        h.name.setText(e.name);
        // The dock has no labels, unless always_show_names asks for them (DockDecoration then makes room in the band).
        // In a single row every tile is the dock's kind: the big title above names it.
        boolean dockKind = DockSlots.inDockRow(dockRow, singleRow, position, dockSize);
        float tileRestAlpha = dockKind ? restNameAlpha : gridRestNameAlpha;
        h.name.setVisibility(dockKind && (tileRestAlpha == 0f || dockBlur || topStage) ? View.GONE : View.VISIBLE);
        h.inDock = dockKind;
        h.currentRestNameAlpha = tileRestAlpha;
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(dockKind ? 0f : (focused ? 1f : tileRestAlpha));
        h.card.setScaleX(focused && animations ? focusScale() : 1f);
        h.card.setScaleY(focused && animations ? focusScale() : 1f);
        h.card.setTranslationY(focused && advancedEffects && !dockKind ? -4f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.card.setTranslationZ(focused && (advancedEffects || dockBlur) ? (dockBlur ? 24f : 8f) * ctx.getResources().getDisplayMetrics().density : 0f);
        h.updateFocusForeground(focused);
        if (!focused) {
            h.card.setRotationX(0f);
            h.card.setRotationY(0f);
        }
        boolean parallax = focused && (advancedEffects || tileTilt);
        h.banner.setScaleX(parallax ? TileTilt.BANNER_PARALLAX_SCALE : 1f);
        h.banner.setScaleY(parallax ? TileTilt.BANNER_PARALLAX_SCALE : 1f);
        // A fully opaque banner covers the whole card (centerCrop), so the card's own fill under it is drawn for
        // nothing on every frame; keep it under banners with transparent parts and under icon tiles.
        h.card.setBackground(e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? null : h.cardBackground);
        if (dockBlur && e.banner == null) {
            h.card.setBackground(brandCard(e.pkg, e.icon, ambientColorAt(position),
                    cornerRadius(h.card.getLayoutParams().width, ctx.getResources().getDisplayMetrics().density, topStage || dockBlur)));
        } else if (tileTilt && (uniformTiles || e.banner == null)) {
            h.card.setBackground(smokedGlassCard(ctx.getResources().getDisplayMetrics().density, ambientColorAt(position)));
        } else if (uniformTiles && e.banner == null) {
            if (e.backdrop == null) e.backdrop = backdrop(e.icon, ambientColorAt(position));
            h.card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, e.backdrop));
        }
        if (e.banner != null) {
            h.icon.setImageDrawable(null);
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(uniformTiles ? View.GONE : View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            if (tileMenu) {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onTileLongPress(pos, v);
                return true;
            }
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    @SuppressWarnings("deprecation")
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (e.virtual) {
            PackageManager pm = ctx.getPackageManager();
            Intent fallback = pm.getLeanbackLaunchIntentForPackage(e.pkg);
            if (fallback == null) fallback = pm.getLaunchIntentForPackage(e.pkg);
            if (fallback == null) {
                boolean utility = "tv.homekit".equals(e.pkg) || "tv.search".equals(e.pkg)
                        || "tv.computers".equals(e.pkg) || "tv.photos".equals(e.pkg) || "tv.facetime".equals(e.pkg);
                if (!utility) {
                    fallback = pm.getLeanbackLaunchIntentForPackage("com.google.android.youtube.tv");
                    if (fallback == null) fallback = pm.getLaunchIntentForPackage("com.google.android.youtube.tv");
                }
                if (fallback == null) fallback = new Intent(Settings.ACTION_SETTINGS);
            }
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(fallback);
            } catch (Exception ex) {
                Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // TV apps launch with the same leanback intent as from the stock launcher or the remote, so an app
        // already running resumes instead of restarting; only phone apps added from the library use plain MAIN.
        if (leanbackPackages.contains(e.pkg)) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String action = launchActions.get(e.pkg);
        String inputId = LaunchActions.inputId(action);
        if (inputId != null) {
            listener.onOpenInput(inputId);
            return;
        }
        if (action != null) {
            // NEW_TASK only: with RESET_TASK_IF_NEEDED, Android just brings a running task of the app to the front
            // ("Activity not started"), so the screen never opens while the app is up, e.g. MagentaTV's boot-time task.
            Intent byAction = LaunchActions.intent(e.pkg, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
        }
        try {
            ctx.startActivity(i);
            if (cinematic) LastOpened.record(ctx, e.pkg);
            if (advancedEffects && !reducedMotion && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + (showLibrary ? 1 : 0);
    }

    int appCount() { return items.size(); }

    boolean isLibraryAt(int position) { return showLibrary && position == items.size(); }

    @Override public int getItemViewType(int position) { return isLibraryAt(position) ? 1 : 0; }

    boolean containsPackage(String pkg) {
        for (Entry entry : items) if (entry.pkg.equals(pkg)) return true;
        return false;
    }

    @Override
    public long getItemId(int position) {
        return isLibraryAt(position) ? Long.MIN_VALUE : items.get(position).component.flattenToShortString().hashCode();
    }

    /** Uses this saved order and dock size from the next reload() (Order.ranks, Order.docked). */
    void setSavedOrder(Map<String, Integer> ranks, int docked) {
        savedRanks = ranks;
        savedDocked = docked;
    }

    /**
     * A tile in the dock: one of the first savedDocked tiles of the saved order when one was recorded, so an app
     * the owner moved into the dock stays there (#499); else a dock_apps entry.
     */
    private boolean isDocked(Entry e) {
        if (savedDocked < 0) return favourites.contains(e.pkg);
        Integer rank = savedRanks.get(e.component.flattenToShortString());
        return rank != null && rank < savedDocked;
    }

    /** Reorder mode on or off; takes effect at the next reload() (empty dock slots are left out while on). */
    void setReordering(boolean on) {
        reordering = on;
    }

    boolean hasEmptySlots() {
        return emptySlots > 0;
    }

    /** Tiles that can be moved: everything but the "Default home" tile, which always stays last. */
    int movableCount() {
        int n = items.size();
        return n > 0 && items.get(n - 1).kind == Entry.HOME_SETTINGS ? n - 1 : n;
    }

    /** Keys of the movable tiles in their current order, for Order (empty dock slots are not tiles). */
    List<String> movableKeys() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < movableCount(); i++) {
            Entry e = items.get(i);
            if (e.kind != Entry.EMPTY) keys.add(e.component.flattenToShortString());
        }
        return keys;
    }

    /** Moves one tile; the others shift by one. Stable ids keep the moved tile's view (and its focus). */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= movableCount() || to >= movableCount()) return;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        // The dock row hides names by position: rebind the tiles whose side of the dock edge changed.
        if (from < dockSize || to < dockSize) {
            int lo = Math.min(from, to), hi = Math.max(from, to);
            notifyItemRangeChanged(lo, hi - lo + 1);
        }
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** Dock slots that hold an app: the whole dock unless some slots are empty. */
    int dockFilled() {
        return emptySlots > 0 ? docked : dockSize;
    }

    /** A position focus can land on: an empty dock slot gives the last dock app. */
    int focusablePosition(int position) {
        return DockSlots.focusable(position, docked, emptySlots);
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** The tile's artwork, its banner or else its icon, for the cinematic shelf's backdrop (HeroArt). */
    Drawable artworkAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.banner != null ? e.banner : e.icon;
    }

    float focusScale() {
        return !animations ? 1f : (topStage || dockBlur) ? TVOS_FOCUS_SCALE : tileTilt ? TileTilt.FOCUS_SCALE : advancedEffects ? 1.12f : 1.10f;
    }

    private static final String[] BRAND_KEYS = {
            "netflix", "youtube", "prime", "amazon", "disney", "max", "hbo", "globo", "vending", "settings", "tvhome"
    };
    private static final int[] BRAND_COLS = {
            0xFF14080A, 0xFF000000, 0xFF1C1C1E, 0xFF09090A, 0xFF2AA8FF, 0xFF0A68D4, 0xFF2AA8FF, 0xFF0A68D4,
            0xFF173584, 0xFF08143F, 0xFF5C30E8, 0xFF221684, 0xFF5C30E8, 0xFF221684, 0xFFFF8A1F, 0xFFFF2D5A,
            0xFF1A3A5C, 0xFF0D1D33, 0xFF3A3A3E, 0xFF1C1C1E, 0xFF1F3B68, 0xFF0E1D36
    };

    /** Borderless Apple TV / free-my-tv brand card fill with no wireframe stroke. */
    private static GradientDrawable brandCard(String pkg, Drawable icon, int accent, float cornerPx) {
        String p = pkg == null ? "" : pkg.toLowerCase(java.util.Locale.ROOT);
        int[] cols = null;
        for (int i = 0; i < BRAND_KEYS.length; i++) {
            if (p.contains(BRAND_KEYS[i])) {
                cols = new int[]{BRAND_COLS[i * 2], BRAND_COLS[i * 2 + 1]};
                break;
            }
        }
        if (cols == null) cols = backdrop(icon, accent);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols);
        d.setCornerRadius(cornerPx);
        return d;
    }

    private static GradientDrawable smokedGlassCard(float density, int accent) {
        int top = (mix(0xFF1C1E2A, accent, 0.38f) & 0x00FFFFFF) | 0xD8000000;
        int bot = (mix(0xFF0E1018, accent, 0.16f) & 0x00FFFFFF) | 0xEA000000;
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bot});
        d.setStroke(Math.max(1, Math.round(1.2f * density)), 0x48FFFFFF);
        d.setCornerRadius(16f * density);
        return d;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            e.ambient = uniformTiles && e.banner == null ? dominantColor(e.icon) : vividColor(e.banner != null ? e.banner : e.icon);
        }
        return e.ambient;
    }

    /** Share of the tile height the icon takes in uniform_tiles mode (onCreateViewHolder). */
    private static final float ICON_SHARE = 0.62f;

    /**
     * The colour covering most of an icon, for a card that should read as that app: a small saturated badge
     * (a red "new" corner on a blue icon) must not win, as it can with {@link #vividColor}. Grey if none.
     */
    private static int dominantColor(Drawable live) {
        if (live == null) return 0xFF6E6E73;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 24;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        float[] weight = new float[12];
        float[][] sum = new float[12][3];
        float[] hsv = new float[3];
        float totalWeight = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;   // white, grey and black carry no hue
                int bin = (int) (hsv[0] / 30f) % 12;
                float w = hsv[1] * hsv[2];
                weight[bin] += w;
                totalWeight += w;
                sum[bin][0] += Color.red(p) * w;
                sum[bin][1] += Color.green(p) * w;
                sum[bin][2] += Color.blue(p) * w;
            }
        }
        bmp.recycle();
        if (totalWeight < n * n * 0.03f) return 0xFF6E6E73;   // a monochrome icon, like a grey gear
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < n * n * 0.03f) {
            // Multicolored icon without a single dominant hue: pick its most vivid colour
            return vividColor(live);
        }
        float w = weight[best];
        return Color.rgb((int) (sum[best][0] / w), (int) (sum[best][1] / w), (int) (sum[best][2] / w));
    }

    /**
     * The uniform tile's fill. Where the icon sits on a flat colour of its own (a white or coloured rounded
     * square), the card takes that colour, so the mark reads as filling the tile as on tvOS. Otherwise a
     * gradient of the icon's main colour, deepened so the mark stands out. The card clips the corners.
     */
    private static int[] backdrop(Drawable live, int fallback) {
        int[] plate = iconPlate(live);
        if (plate != null) return plate;
        float[] hsv = new float[3];
        Color.colorToHSV(fallback, hsv);
        // Yellow darkened turns olive: lean it to amber instead.
        if (hsv[0] > 42f && hsv[0] < 70f && hsv[1] > 0.3f) hsv[0] = 36f;
        hsv[2] = Math.min(hsv[2], 0.62f);
        int top = Color.HSVToColor(hsv);
        hsv[2] *= 0.6f;
        return new int[]{top, Color.HSVToColor(hsv)};
    }

    /**
     * The plate an icon's artwork sits on (a flat or vertically graded rounded square), carried out to the
     * card's top and bottom edges so the mark reads as filling the tile; null when the icon has no
     * opaque plate (a bare mark on transparency).
     */
    private static int[] iconPlate(Drawable live) {
        if (live == null) return null;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 48;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        // Just inside a rounded square's corners; one side may go a step further in, past a corner badge.
        // An icon padded on both sides is a small plate in the middle, which no fill would meet.
        int topRow = 7, bottomRow = n - 8;
        int a = plateColor(bmp, topRow, n), b = plateColor(bmp, bottomRow, n);
        if (a == 0) {
            for (int r : new int[]{5, 6, 4, 10}) {
                int c = plateColor(bmp, r, n);
                if (c != 0) { a = c; topRow = r; break; }
            }
        }
        if (b == 0) {
            for (int r : new int[]{n - 6, n - 7, n - 5, n - 11}) {
                int c = plateColor(bmp, r, n);
                if (c != 0) { b = c; bottomRow = r; break; }
            }
        }
        bmp.recycle();
        if (a == 0 || b == 0) return null;
        // The sampled rows sit at these heights of the tile; extend the line through them to its edges. A row
        // taken further in sits on artwork-side plate, so its slope is less certain: cap how far it reaches.
        float margin = (1f - ICON_SHARE) / 2f;
        float yA = margin + ICON_SHARE * topRow / n, yB = margin + ICON_SHARE * bottomRow / n;
        float t0 = -yA / (yB - yA), t1 = (1f - yA) / (yB - yA);
        if (topRow != 7 || bottomRow != n - 8) {
            t0 = Math.max(-0.35f, t0);
            t1 = Math.min(1.35f, t1);
        }
        return new int[]{mix(a, b, t0), mix(a, b, t1)};
    }

    /** {@link #plateColor(int[])} of row y, between the same insets as the row's distance from the edge. */
    private static int plateColor(Bitmap bmp, int y, int n) {
        int inset = Math.min(y, n - 1 - y);
        int[] row = new int[n - 2 * inset];
        bmp.getPixels(row, 0, row.length, inset, y, row.length, 1);
        return plateColor(row);
    }

    /** The colour most of a row shares, ignoring artwork that crosses it; 0 if the row is not mostly plate. */
    private static int plateColor(int[] row) {
        int[] r = new int[row.length], g = new int[row.length], b = new int[row.length];
        int opaque = 0;
        for (int p : row) {
            if (Color.alpha(p) < 240) continue;
            r[opaque] = Color.red(p);
            g[opaque] = Color.green(p);
            b[opaque] = Color.blue(p);
            opaque++;
        }
        if (opaque < row.length * 0.9f) return 0;
        int mr = median(r, opaque), mg = median(g, opaque), mb = median(b, opaque);
        long sr = 0, sg = 0, sb = 0;
        int close = 0;
        for (int i = 0; i < opaque; i++) {
            if (Math.abs(r[i] - mr) + Math.abs(g[i] - mg) + Math.abs(b[i] - mb) >= 48) continue;
            sr += r[i];
            sg += g[i];
            sb += b[i];
            close++;
        }
        if (close < row.length * 0.7f) return 0;
        return Color.rgb((int) (sr / close), (int) (sg / close), (int) (sb / close));
    }

    private static int median(int[] values, int count) {
        int[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return sorted[count / 2];
    }

    /** a + (b - a) * t per channel, clamped; t outside 0..1 extrapolates. */
    private static int mix(int a, int b, float t) {
        return Color.rgb(clamp(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                clamp(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                clamp(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        bmp.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            int max = Math.max(pr, Math.max(pg, pb));
            int min = Math.min(pr, Math.min(pg, pb));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            float val = max / 255f;
            float score = sat * Math.min(1f, val * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static float cornerRadius(int tileWidthPx, float density, boolean tvos) {
        return tvos
                ? 18f * density * Math.min(1f, tileWidthPx / (TVOS_W_DP * density))
                : cornerRadius(tileWidthPx, density);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: the dark card behind an icon tile, or behind a banner that has transparent parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations;
        final boolean advancedEffects;
        final boolean tileTilt;
        final boolean dockBlur;
        final boolean topStage;
        final boolean reducedMotion;
        /** Alpha of the name while the tile is not focused (always_show_names or tile_tilt grid). */
        final float restNameAlpha;
        float currentRestNameAlpha;
        final boolean overshoot;
        /** A dock tile: it grows in place without advanced_effects' lift, which reads as off-centre in the band (#2135). */
        boolean inDock;
        private GradientDrawable focusRim;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects, boolean tileTilt,
               boolean dockBlur, boolean topStage, boolean reducedMotion, float restNameAlpha, boolean overshoot) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.tileTilt = tileTilt;
            this.dockBlur = dockBlur;
            this.topStage = topStage;
            this.reducedMotion = reducedMotion;
            this.restNameAlpha = restNameAlpha;
            this.currentRestNameAlpha = restNameAlpha;
            this.overshoot = overshoot;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            cornerRadius(view.getWidth(), density, topStage || dockBlur));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? 1f : (topStage || dockBlur) ? TVOS_FOCUS_SCALE : tileTilt ? TileTilt.FOCUS_SCALE : advancedEffects ? 1.12f : 1.10f;
                float s = focused ? scale : 1f;
                float zPx = focused && (advancedEffects || dockBlur) ? (dockBlur ? 24f : 8f) * density : 0f;
                updateFocusForeground(focused);
                float nameTargetAlpha = inDock ? 0f : (focused ? 1f : currentRestNameAlpha);
                float nameFocusedTy = (topStage || dockBlur) ? 2f * density : 4f * density;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    view.setRotationX(0f);
                    view.setRotationY(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(nameTargetAlpha);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    // A short zoom with no hardware layer: building a layer for so short an animation costs more
                    // frames than it saves. The name just appears instead of fading.
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects && !inDock ? -4f * density : 0f)
                            .translationZ(zPx)
                            .setDuration(focused ? Motion.REDUCED_MS : 60)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    if (!focused) {
                        view.setRotationX(0f);
                        view.setRotationY(0f);
                    }
                    if (advancedEffects || tileTilt) {
                        banner.setScaleX(focused ? TileTilt.BANNER_PARALLAX_SCALE : 1f);
                        banner.setScaleY(focused ? TileTilt.BANNER_PARALLAX_SCALE : 1f);
                    }
                    name.setAlpha(nameTargetAlpha);
                    if (focused) name.setTranslationY(nameFocusedTy);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects && !inDock ? -4f * density : 0f)
                            .translationZ(zPx)
                            .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                            .setInterpolator(focused && !overshoot ? new DecelerateInterpolator(1.5f)
                                    : new OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                            .withLayer().start();
                    if (!focused) {
                        view.setRotationX(0f);
                        view.setRotationY(0f);
                    }
                    if (advancedEffects || tileTilt) {
                        banner.animate().scaleX(focused ? TileTilt.BANNER_PARALLAX_SCALE : 1f)
                                .scaleY(focused ? TileTilt.BANNER_PARALLAX_SCALE : 1f)
                                .setDuration(280).start();
                    }
                    name.animate().alpha(nameTargetAlpha)
                            .translationY(focused ? nameFocusedTy : -density * 4f)
                            .setDuration(200).start();
                }
                if (dockBlur && view.getParent() instanceof View && view.getParent().getParent() instanceof View) {
                    ((View) view.getParent().getParent()).invalidate();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }

        void updateFocusForeground(boolean focused) {
            if (!dockBlur && !topStage) return;
            if (!focused) {
                card.setForeground(null);
                return;
            }
            if (focusRim == null) {
                float density = card.getResources().getDisplayMetrics().density;
                int w = card.getLayoutParams() != null && card.getLayoutParams().width > 0
                        ? card.getLayoutParams().width : Math.round(TVOS_W_DP * density);
                focusRim = new GradientDrawable();
                focusRim.setColor(Color.TRANSPARENT);
                focusRim.setCornerRadius(cornerRadius(w, density, true));
                focusRim.setStroke(Math.max(1, Math.round(1.5f * density)), 0x66FFFFFF);
            }
            card.setForeground(focusRim);
        }
    }

    // ---- 24-slot tvOS 27.2 Showcase Grid + Vector 5:3 Brand Tiles --------------------------------

    private static final String[] SPEC_LABELS = {
            "Apple TV", "YouTube", "Music", "Paramount+", "Netflix", "Disney+",
            "Sing", "Settings", "Podcasts", "Arcade", "UFC", "Games",
            "FaceTime", "Hulu", "Prime Video", "ABC", "YouTube TV", "Photos",
            "App Store", "HomeKit", "Twitch", "Search", "Computers", "Spotify"
    };
    private static final String[] SPEC_PKGS = {
            "tv.apple|com.apple.atve.androidtv.appletv", "com.google.android.youtube.tv",
            "com.apple.tvmusic|com.apple.android.music", "com.cbs.ott|com.cbs.app",
            "com.netflix.ninja", "com.disney.disneyplus",
            "tv.sing", "com.android.tv.settings|com.android.settings",
            "tv.podcasts", "tv.invaders", "tv.ufc", "tv.games|com.google.android.play.games",
            "tv.facetime", "com.hulu.livingroomplus", "com.amazon.amazonvideo.livingroom",
            "tv.abc", "com.google.android.youtube.tvunplugged", "tv.photos",
            "com.android.vending", "tv.homekit", "tv.twitch.android.app|com.twitch.android.app",
            "tv.search", "tv.computers", "com.spotify.tv.android"
    };
    private static final int[] SPEC_ACCENTS = {
            0xFF3A82F6, 0xFFFF0000, 0xFFFC3C44, 0xFF0064FF, 0xFFE50914, 0xFF0A8C96,
            0xFFD0D4DC, 0xFF9898A0, 0xFFA550E5, 0xFF5E5CE6, 0xFFD20A0A, 0xFFFF2D55,
            0xFF30D158, 0xFF1CE783, 0xFF00A8E1, 0xFFE01A22, 0xFFFF0000, 0xFFFF9F0A,
            0xFF1D8FF2, 0xFF78909C, 0xFF9146FF, 0xFF9898A0, 0xFFD8DCE4, 0xFF1ED760
    };

    private static int showcaseIndexForPkg(String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < SPEC_PKGS.length; i++) {
            String sp = SPEC_PKGS[i];
            int bar = sp.indexOf('|');
            if (bar < 0 ? pkg.equals(sp) : (pkg.equals(sp.substring(0, bar)) || pkg.equals(sp.substring(bar + 1)))) {
                return i;
            }
        }
        return -1;
    }

    /** Crisp 5:3 vector tvOS brand tile renderer (no bitmap memory overhead). */
    private static final class BrandTileDrawable extends Drawable {
        private static final Typeface SANS_BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
        private static final Typeface SANS_BOLD_ITALIC = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
        private static final Typeface CONDENSED_BOLD = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        private static final int[] BG_TOP = {
                0xFF1C1C1F, 0xFFFFFFFF, 0xFF1C1C1F, 0xFF006AFF, 0xFFFFFFFF, 0xFF041C2E,
                0xFF1C1C1F, 0xFF949499, 0xFFB25AF0, 0xFF343438, 0xFFE20A0A, 0xFFFF2D55,
                0xFF34D95E, 0xFF0B0C0F, 0xFF00B4EE, 0xFF13151C, 0xFF26272B, 0xFFFFFFFF,
                0xFF2496FF, 0xFF68686E, 0xFF984FFF, 0xFF98989E, 0xFFFAFAFC, 0xFF161618
        };
        private static final int[] BG_BOT = {
                0xFF08080A, 0xFFF2F2F6, 0xFF08080A, 0xFF0038A8, 0xFFF2F2F6, 0xFF0B747C,
                0xFF08080A, 0xFF5D5D62, 0xFF6616A6, 0xFF202024, 0xFFA20505, 0xFFC6001A,
                0xFF1E9638, 0xFF040507, 0xFF0078BC, 0xFF07080B, 0xFF16171A, 0xFFF2F2F6,
                0xFF0052CE, 0xFF444448, 0xFF7024E4, 0xFF64646A, 0xFFE6E6EC, 0xFF070709
        };
        private static final int[] PHOTO_PETALS = {
                0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE
        };
        private static final short[] ARCADE_ROWS = {
                0b00100000100, 0b00010001000, 0b00111111100, 0b01101110110,
                0b11111111111, 0b10111111101, 0b10100000101, 0b00011011000
        };

        private final int brand;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();
        private int cachedW = -1, cachedH = -1;
        private Shader bgShader;

        BrandTileDrawable(int brand) {
            this.brand = brand;
        }

        private void txt(Canvas c, String t, float x, float y, float sz, int col, Typeface tf, boolean left) {
            textPaint.setTypeface(tf);
            textPaint.setColor(col);
            textPaint.setTextSize(sz);
            textPaint.setTextAlign(left ? Paint.Align.LEFT : Paint.Align.CENTER);
            c.drawText(t, x, y, textPaint);
        }

        private void rr(Canvas c, float l, float t, float r, float b, float rad, int col) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(col);
            rf.set(l, t, r, b);
            c.drawRoundRect(rf, rad, rad, paint);
        }

        private void poly(Canvas c, float cx, float cy, float s, int col, float... xy) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(col);
            path.reset();
            path.moveTo(cx + xy[0] * s, cy + xy[1] * s);
            for (int i = 2; i < xy.length; i += 2) path.lineTo(cx + xy[i] * s, cy + xy[i + 1] * s);
            path.close();
            c.drawPath(path, paint);
        }

        private void arc(Canvas c, float cx, float cy, float s, float l, float t, float r, float b,
                         float st, float sw, float w, int col) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(w * s);
            paint.setColor(col);
            rf.set(cx + l * s, cy + t * s, cx + r * s, cy + b * s);
            c.drawArc(rf, st, sw, false, paint);
        }

        private void playBadge(Canvas c, float cx, float cy, float s, float l, float r, float rad, float tx, float tip) {
            rr(c, cx + l * s, cy - 16f * s, cx + r * s, cy + 16f * s, rad * s, 0xFFFF0000);
            poly(c, cx, cy, s, Color.WHITE, tx, -7f, tx, 7f, tip, 0f);
        }

        @Override
        public void draw(@NonNull Canvas c) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            float s = w / 250f, cx = b.left + w * 0.5f, cy = b.top + h * 0.5f;
            if (cachedW != (int) w || cachedH != (int) h || bgShader == null) {
                cachedW = (int) w;
                cachedH = (int) h;
                bgShader = brand == 5
                        ? new LinearGradient(b.left, b.top, b.right, b.bottom,
                                new int[]{0xFF041C2E, 0xFF084B5A, 0xFF0B747C}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP)
                        : new LinearGradient(0, b.top, 0, b.bottom, BG_TOP[brand], BG_BOT[brand], Shader.TileMode.CLAMP);
            }
            paint.reset();
            paint.setAntiAlias(true);
            paint.setShader(bgShader);
            c.drawRect(b, paint);
            paint.setShader(null);
            textPaint.reset();
            textPaint.setAntiAlias(true);

            switch (brand) {
                case 0:
                    drawAppleGlyph(c, cx - 22f * s, cy + 2f * s, 14f * s, Color.WHITE, 0xFF141416);
                    txt(c, "tv", cx - 4f * s, cy + 13f * s, 38f * s, Color.WHITE, SANS_BOLD, true);
                    break;
                case 1:
                    playBadge(c, cx, cy, s, -82f, -36f, 9f, -64f, -51f);
                    txt(c, "YouTube", cx - 30f * s, cy + 10.5f * s, 31f * s, 0xFF141414, CONDENSED_BOLD, true);
                    break;
                case 2:
                    paint.setColor(Color.WHITE);
                    c.drawOval(cx - 32f * s, cy + 12f * s, cx - 10f * s, cy + 30f * s, paint);
                    c.drawOval(cx + 6f * s, cy + 6f * s, cx + 28f * s, cy + 24f * s, paint);
                    c.drawRect(cx - 15f * s, cy - 26f * s, cx - 10f * s, cy + 21f * s, paint);
                    c.drawRect(cx + 23f * s, cy - 32f * s, cx + 28f * s, cy + 15f * s, paint);
                    poly(c, cx, cy, s, Color.WHITE, -15f, -26f, 28f, -34f, 28f, -22f, -15f, -14f);
                    break;
                case 3:
                    poly(c, cx, cy, s, Color.WHITE, -32f, 10f, 0f, -26f, 32f, 10f);
                    poly(c, cx, cy, s, 0xFF0052D6, -14f, 10f, 3f, -8f, 17f, 10f);
                    paint.setColor(Color.WHITE);
                    for (int i = 0; i < 11; i++) {
                        double ang = Math.toRadians(-195 + i * 21);
                        c.drawCircle(cx + (float) Math.cos(ang) * 34f * s, cy - 4f * s + (float) Math.sin(ang) * 30f * s, 2.2f * s, paint);
                    }
                    txt(c, "Paramount+", cx, cy + 36f * s, 22f * s, Color.WHITE, SANS_BOLD_ITALIC, false);
                    break;
                case 4:
                    textPaint.setLetterSpacing(0.04f);
                    txt(c, "NETFLIX", cx, cy + 14f * s, 42f * s, 0xFFE50914, CONDENSED_BOLD, false);
                    break;
                case 5:
                    arc(c, cx, cy, s, -48f, -34f, 48f, 18f, 195f, 150f, 2.4f, 0x885CE1E6);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.WHITE);
                    c.drawCircle(cx + 39f * s, cy - 22f * s, 2.5f * s, paint);
                    txt(c, "Disney+", cx, cy + 15f * s, 34f * s, Color.WHITE, SANS_BOLD_ITALIC, false);
                    break;
                case 6:
                    paint.setColor(Color.WHITE);
                    c.drawCircle(cx - 24f * s, cy - 10f * s, 3f * s, paint);
                    c.drawCircle(cx + 26f * s, cy + 12f * s, 3.2f * s, paint);
                    c.drawCircle(cx + 24f * s, cy - 22f * s, 2.2f * s, paint);
                    c.save();
                    c.rotate(42f, cx, cy);
                    paint.setColor(0xFFE5E5EA);
                    c.drawCircle(cx, cy - 20f * s, 14f * s, paint);
                    paint.setColor(0xFF98989F);
                    c.drawRect(cx - 14f * s, cy - 18f * s, cx + 14f * s, cy - 14f * s, paint);
                    poly(c, cx, cy, s, 0xFFD1D1D6, -7f, -12f, 7f, -12f, 4.5f, 32f, -4.5f, 32f);
                    c.restore();
                    break;
                case 7:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(0xFFE5E5EA);
                    paint.setStrokeWidth(7f * s);
                    c.drawCircle(cx, cy, 34f * s, paint);
                    paint.setStrokeWidth(3.5f * s);
                    paint.setColor(0xCCFFFFFF);
                    c.drawCircle(cx, cy, 20f * s, paint);
                    for (int i = 0; i < 3; i++) {
                        double a = Math.toRadians(-90 + i * 120);
                        c.drawLine(cx, cy, cx + (float) Math.cos(a) * 32f * s, cy + (float) Math.sin(a) * 32f * s, paint);
                    }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFFF2F2F7);
                    c.drawCircle(cx, cy, 8f * s, paint);
                    break;
                case 8:
                    arc(c, cx, cy, s, -34f, -42f, 34f, 26f, 130f, 280f, 4.5f, 0xB8FFFFFF);
                    arc(c, cx, cy, s, -21f, -29f, 21f, 13f, 135f, 270f, 4.5f, 0xB8FFFFFF);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.WHITE);
                    c.drawCircle(cx, cy - 8f * s, 9f * s, paint);
                    rr(c, cx - 9f * s, cy + 4f * s, cx + 9f * s, cy + 34f * s, 8f * s, Color.WHITE);
                    break;
                case 9: {
                    paint.setColor(Color.WHITE);
                    float px = 6.5f * s, ox = cx - 5.5f * px, oy = cy - 4f * px;
                    for (int r = 0; r < ARCADE_ROWS.length; r++) {
                        for (int col = 0; col < 11; col++) {
                            if ((ARCADE_ROWS[r] & (1 << (10 - col))) != 0) {
                                c.drawRect(ox + col * px, oy + r * px, ox + (col + 1) * px, oy + (r + 1) * px, paint);
                            }
                        }
                    }
                    break;
                }
                case 10:
                    txt(c, "UFC", cx, cy + 17f * s, 48f * s, Color.WHITE, SANS_BOLD_ITALIC, false);
                    break;
                case 11:
                    rr(c, cx - 32f * s, cy + 4f * s, cx + 32f * s, cy + 30f * s, 10f * s, 0xFFFFD6DF);
                    paint.setColor(Color.WHITE);
                    c.drawRect(cx - 4f * s, cy - 22f * s, cx + 4f * s, cy + 10f * s, paint);
                    c.drawCircle(cx, cy - 24f * s, 12f * s, paint);
                    paint.setColor(0xFFFF2D55);
                    c.drawCircle(cx + 18f * s, cy + 14f * s, 4f * s, paint);
                    break;
                case 12:
                    rr(c, cx - 38f * s, cy - 20f * s, cx + 12f * s, cy + 20f * s, 8f * s, Color.WHITE);
                    poly(c, cx, cy, s, Color.WHITE, 15f, -8f, 38f, -20f, 38f, 20f, 15f, 8f);
                    break;
                case 13:
                    txt(c, "hulu", cx, cy + 15f * s, 46f * s, 0xFF1CE783, SANS_BOLD, false);
                    break;
                case 14:
                    txt(c, "prime video", cx, cy + 2f * s, 26f * s, Color.WHITE, SANS_BOLD, false);
                    arc(c, cx, cy, s, -42f, -4f, 42f, 24f, 15f, 150f, 3.5f, Color.WHITE);
                    break;
                case 15:
                    paint.setColor(0xFFD81921);
                    c.drawCircle(cx, cy, 36f * s, paint);
                    txt(c, "abc", cx, cy + 10f * s, 30f * s, Color.WHITE, SANS_BOLD, false);
                    break;
                case 16:
                    playBadge(c, cx, cy, s, -76f, -32f, 7f, -59f, -47f);
                    txt(c, "YouTubeTV", cx - 26f * s, cy + 10f * s, 27f * s, Color.WHITE, CONDENSED_BOLD, true);
                    break;
                case 17:
                    for (int i = 0; i < 8; i++) {
                        c.save();
                        c.rotate(i * 45f, cx, cy);
                        rr(c, cx - 10f * s, cy - 42f * s, cx + 10f * s, cy - 6f * s, 10f * s, PHOTO_PETALS[i]);
                        c.restore();
                    }
                    break;
                case 18:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(9f * s);
                    paint.setColor(Color.WHITE);
                    c.drawLine(cx - 24f * s, cy + 24f * s, cx + 4f * s, cy - 28f * s, paint);
                    c.drawLine(cx - 4f * s, cy - 28f * s, cx + 24f * s, cy + 24f * s, paint);
                    c.drawLine(cx - 32f * s, cy + 10f * s, cx + 32f * s, cy + 10f * s, paint);
                    break;
                case 19:
                    rr(c, b.left + 16f * s, b.top + 14f * s, b.left + 64f * s, b.top + 48f * s, 8f * s, 0x38FFFFFF);
                    rr(c, b.left + 72f * s, b.top + 14f * s, b.left + 120f * s, b.top + 48f * s, 8f * s, 0x38FFFFFF);
                    paint.setColor(Color.WHITE);
                    c.drawCircle(b.left + 40f * s, b.top + 31f * s, 7f * s, paint);
                    poly(c, b.left, b.top, s, Color.WHITE, 86f, 32f, 96f, 22f, 106f, 32f, 106f, 40f, 86f, 40f);
                    break;
                case 20:
                    rr(c, cx - 32f * s, cy - 34f * s, cx + 32f * s, cy + 22f * s, 6f * s, 0xFF141416);
                    poly(c, cx, cy, s, 0xFF141416, -18f, 20f, -18f, 34f, -4f, 20f);
                    rr(c, cx - 24f * s, cy - 26f * s, cx + 24f * s, cy + 14f * s, 4f * s, Color.WHITE);
                    paint.setColor(0xFF141416);
                    c.drawRect(cx - 8f * s, cy - 16f * s, cx - 2f * s, cy + 2f * s, paint);
                    c.drawRect(cx + 6f * s, cy - 16f * s, cx + 12f * s, cy + 2f * s, paint);
                    break;
                case 21:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setColor(Color.WHITE);
                    paint.setStrokeWidth(7.5f * s);
                    c.drawCircle(cx - 8f * s, cy - 8f * s, 21f * s, paint);
                    c.drawLine(cx + 7f * s, cy + 7f * s, cx + 28f * s, cy + 28f * s, paint);
                    break;
                case 22:
                    drawAppleGlyph(c, cx, cy + 4f * s, 20f * s, 0xFF1C1C1E, 0xFFF2F2F6);
                    break;
                case 23:
                    paint.setColor(0xFF1ED760);
                    c.drawCircle(cx, cy, 36f * s, paint);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    arc(c, cx, cy, s, -24f, -14f, 24f, 10f, 215f, 110f, 5.5f, 0xFF121212);
                    arc(c, cx, cy, s, -19f, -3f, 19f, 18f, 215f, 110f, 4.5f, 0xFF121212);
                    arc(c, cx, cy, s, -14f, 7f, 14f, 25f, 215f, 110f, 3.8f, 0xFF121212);
                    break;
                default:
                    break;
            }
        }

        private void drawAppleGlyph(Canvas c, float cx, float cy, float r, int color, int biteColor) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            path.reset();
            path.moveTo(cx, cy - r * 0.52f);
            path.cubicTo(cx + r * 0.55f, cy - r * 0.72f, cx + r * 1.05f, cy - r * 0.15f, cx + r * 0.85f, cy + r * 0.45f);
            path.cubicTo(cx + r * 0.70f, cy + r * 0.98f, cx + r * 0.30f, cy + r * 1.05f, cx, cy + r * 0.88f);
            path.cubicTo(cx - r * 0.30f, cy + r * 1.05f, cx - r * 0.70f, cy + r * 0.98f, cx - r * 0.85f, cy + r * 0.45f);
            path.cubicTo(cx - r * 1.05f, cy - r * 0.15f, cx - r * 0.55f, cy - r * 0.72f, cx, cy - r * 0.52f);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(biteColor);
            c.drawCircle(cx + r * 0.96f, cy - r * 0.04f, r * 0.36f, paint);
            paint.setColor(color);
            path.reset();
            path.moveTo(cx, cy - r * 0.65f);
            path.quadTo(cx + r * 0.08f, cy - r * 1.12f, cx + r * 0.45f, cy - r * 1.15f);
            path.quadTo(cx + r * 0.36f, cy - r * 0.70f, cx, cy - r * 0.65f);
            path.close();
            c.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
