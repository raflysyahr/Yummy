package com.termux.app.theme.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.LruCache;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PixelFormat;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.caverock.androidsvg.SVG;
import com.termux.app.theme.model.AppThemeColorRole;
import com.termux.app.theme.model.ThemeAppearance;
import com.termux.app.theme.model.VSCodeIconTheme;
import com.termux.app.theme.registry.YummyUiIconRegistry;
import com.termux.app.theme.resolver.VSCodeIconThemeResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime bridge between VS Code File Icon Themes and concrete Yummy ImageViews.
 *
 * <p>The active theme is supplied by the theme-management layer. File/folder associations are
 * resolved by {@link VSCodeIconThemeResolver}; fixed Yummy controls use stable registry slots.
 * Every binding retains a Yummy fallback and custom artwork is never color-tinted.</p>
 */
public final class YummyIconThemeController {
    private static final int RENDER_SIZE_PX = 72;
    private static final long MAX_ASSET_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_FONT_BYTES = 5 * 1024 * 1024;

    private final Context appContext;
    private final YummyUiIconRegistry registry = YummyUiIconRegistry.getDefault();
    private final WeakHashMap<ImageView, UiBinding> uiBindings = new WeakHashMap<>();
    private final WeakHashMap<ImageView, AssociationBinding> associationBindings = new WeakHashMap<>();
    private final WeakHashMap<TextView, CompoundBinding> compoundBindings = new WeakHashMap<>();
    private final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getAllocationByteCount();
        }
    };
    private final Map<String, Typeface> typefaceCache = new HashMap<>();

    @Nullable private VSCodeIconTheme theme;
    @Nullable private VSCodeIconThemeResolver resolver;
    @Nullable private File extensionRoot;
    @Nullable private String themeDocumentPath;
    private Map<String, String> explicitMappings = Collections.emptyMap();

    public YummyIconThemeController(@NonNull Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
    }

    /** Installs an in-memory runtime theme. Assets must live below extensionRoot. */
    public void setTheme(@Nullable VSCodeIconTheme newTheme,
                         @Nullable File newExtensionRoot,
                         @Nullable String newThemeDocumentPath,
                         @Nullable ThemeAppearance appearance,
                         @Nullable Map<String, String> mappings,
                         @NonNull AppThemeViewRegistry colorRegistry) {
        if (newTheme == null || newExtensionRoot == null || newThemeDocumentPath == null) {
            theme = null;
            resolver = null;
            extensionRoot = null;
            themeDocumentPath = null;
            explicitMappings = Collections.emptyMap();
        } else {
            try {
                File canonicalRoot = newExtensionRoot.getCanonicalFile();
                if (!canonicalRoot.isDirectory()) throw new IOException("Icon Theme asset root is unavailable");
                theme = newTheme;
                resolver = new VSCodeIconThemeResolver(newTheme, appearance);
                extensionRoot = canonicalRoot;
                themeDocumentPath = normalizeDocumentPath(newThemeDocumentPath);
                explicitMappings = mappings == null ? Collections.emptyMap()
                    : registry.validateMappings(newTheme, mappings);
            } catch (IOException | IllegalArgumentException error) {
                theme = null;
                resolver = null;
                extensionRoot = null;
                themeDocumentPath = null;
                explicitMappings = Collections.emptyMap();
            }
        }
        synchronized (bitmapCache) { bitmapCache.evictAll(); }
        synchronized (typefaceCache) { typefaceCache.clear(); }
        refreshBindings(colorRegistry);
    }

    public void clearTheme(@NonNull AppThemeViewRegistry colorRegistry) {
        setTheme(null, null, null, ThemeAppearance.UNKNOWN, null, colorRegistry);
    }

    public boolean hasActiveTheme() {
        return theme != null && resolver != null && extensionRoot != null;
    }

    /** Binds a fixed Yummy UI action, preserving its registered built-in fallback. */
    public void bindUiIcon(@NonNull ImageView view, @NonNull String slotId,
                           @Nullable AppThemeColorRole fallbackTintRole,
                           @NonNull AppThemeViewRegistry colorRegistry) {
        bindUiIcon(view, slotId, fallbackTintRole, null, colorRegistry);
    }

    /** Allows a caller to preserve a provider-specific or component-specific fallback drawable. */
    public void bindUiIcon(@NonNull ImageView view, @NonNull String slotId,
                           @Nullable AppThemeColorRole fallbackTintRole,
                           @Nullable Drawable fallbackOverride,
                           @NonNull AppThemeViewRegistry colorRegistry) {
        if (view == null || slotId == null) return;
        YummyUiIconRegistry.IconSlot slot = registry.getSlot(slotId);
        if (slot == null || (slot.getFallbackDrawable() == 0 && fallbackOverride == null)) return;
        UiBinding previous = uiBindings.get(view);
        ColorStateList originalTint = previous == null
            ? view.getImageTintList() : previous.originalTint;
        Drawable fallback = previous != null ? previous.fallbackOverride : fallbackOverride;
        uiBindings.put(view, new UiBinding(slotId, slot.getFallbackDrawable(),
            fallback, fallbackTintRole, originalTint));
        associationBindings.remove(view);
        applyUiBinding(view, uiBindings.get(view), colorRegistry);
    }

    /** Binds a fixed Yummy UI icon in a TextView start compound-drawable slot. */
    public void bindCompoundUiIcon(@NonNull TextView view, @NonNull String slotId) {
        if (view == null || slotId == null || registry.getSlot(slotId) == null) return;
        CompoundBinding previous = compoundBindings.get(view);
        Drawable fallback = previous == null
            ? view.getCompoundDrawablesRelative()[0] : previous.fallback;
        if (fallback == null) return;
        Rect bounds = fallback.getBounds();
        int width = bounds.width() > 0 ? bounds.width() : fallback.getIntrinsicWidth();
        int height = bounds.height() > 0 ? bounds.height() : fallback.getIntrinsicHeight();
        if (width <= 0) width = RENDER_SIZE_PX;
        if (height <= 0) height = RENDER_SIZE_PX;
        compoundBindings.put(view, new CompoundBinding(slotId, fallback, width, height));
        applyCompoundBinding(view, compoundBindings.get(view));
    }

    /** Binds a filename association. The supplied Drawable is always the safe Yummy fallback. */
    public void bindFileIcon(@NonNull ImageView view, @NonNull String fileName,
                             @Nullable String parentFolderName, @Nullable String languageId,
                             @NonNull Drawable fallback) {
        if (view == null || fileName == null || fallback == null) return;
        uiBindings.remove(view);
        associationBindings.put(view, AssociationBinding.file(
            fileName, parentFolderName, languageId, fallback));
        applyAssociationBinding(view, associationBindings.get(view));
    }

    /** Binds a folder association. The supplied Drawable is always the safe Yummy fallback. */
    public void bindFolderIcon(@NonNull ImageView view, @NonNull String folderName,
                               @Nullable String parentFolderName, boolean expanded,
                               boolean root, @NonNull Drawable fallback) {
        if (view == null || folderName == null || fallback == null) return;
        uiBindings.remove(view);
        associationBindings.put(view, AssociationBinding.folder(
            folderName, parentFolderName, expanded, root, fallback));
        applyAssociationBinding(view, associationBindings.get(view));
    }

    /** Removes any Icon Theme binding before an ImageView is recycled for another row. */
    public void unbind(@Nullable ImageView view, @NonNull AppThemeViewRegistry colorRegistry) {
        if (view == null) return;
        uiBindings.remove(view);
        associationBindings.remove(view);
        colorRegistry.unbindImageTint(view);
    }

    /** Reapplies custom images after App Theme palette changes. */
    public void refreshBindings(@NonNull AppThemeViewRegistry colorRegistry) {
        for (Map.Entry<ImageView, UiBinding> entry : uiBindings.entrySet()) {
            ImageView view = entry.getKey();
            if (view != null) applyUiBinding(view, entry.getValue(), colorRegistry);
        }
        for (Map.Entry<ImageView, AssociationBinding> entry : associationBindings.entrySet()) {
            ImageView view = entry.getKey();
            if (view != null) applyAssociationBinding(view, entry.getValue());
        }
        for (Map.Entry<TextView, CompoundBinding> entry : compoundBindings.entrySet()) {
            TextView view = entry.getKey();
            if (view != null) applyCompoundBinding(view, entry.getValue());
        }
    }

    private void applyUiBinding(ImageView view, UiBinding binding,
                                AppThemeViewRegistry colorRegistry) {
        if (binding == null) return;
        Drawable custom = resolveUiIcon(binding.slotId);
        if (custom != null) {
            colorRegistry.unbindImageTint(view);
            view.setImageTintList(null);
            view.setColorFilter(null);
            view.setImageDrawable(custom);
        } else {
            colorRegistry.unbindImageTint(view);
            if (binding.fallbackOverride != null) view.setImageDrawable(binding.fallbackOverride);
            else view.setImageResource(binding.fallbackDrawable);
            view.setImageTintList(binding.originalTint);
            if (binding.fallbackTintRole != null) {
                colorRegistry.bindImageTint(view, binding.fallbackTintRole);
            }
        }
    }

    private void applyCompoundBinding(TextView view, CompoundBinding binding) {
        if (binding == null) return;
        Drawable custom = resolveUiIcon(binding.slotId);
        Drawable selected = custom == null ? binding.fallback : custom;
        if (selected != null) selected.setBounds(0, 0, binding.width, binding.height);
        Drawable[] current = view.getCompoundDrawablesRelative();
        view.setCompoundDrawablesRelative(selected, current[1], current[2], current[3]);
    }

    private void applyAssociationBinding(ImageView view, AssociationBinding binding) {
        if (binding == null) return;
        Drawable custom = null;
        if (resolver != null) {
            VSCodeIconThemeResolver.Resolution resolution;
            if (binding.folder) {
                resolution = resolver.resolveFolder(binding.name, binding.parentName,
                    binding.expanded, binding.root);
            } else {
                resolution = resolver.resolveFile(binding.name, binding.parentName,
                    binding.languageId);
            }
            custom = renderResolution(resolution);
        }
        view.setImageTintList(null);
        view.setColorFilter(null);
        view.setImageDrawable(custom == null ? binding.fallback : custom);
    }

    @Nullable
    private Drawable resolveUiIcon(String slotId) {
        if (theme == null) return null;
        YummyUiIconRegistry.ResolvedIcon resolved = registry.resolve(slotId, theme, explicitMappings);
        if (resolved == null || !resolved.usesCustomIcon()) return null;
        VSCodeIconTheme.IconDefinition definition = resolved.getDefinition();
        return renderDefinition(definition, effectiveFontId(definition),
            effectiveFontColor(definition), effectiveFontSize(definition));
    }

    @Nullable
    private Drawable renderResolution(@Nullable VSCodeIconThemeResolver.Resolution resolution) {
        if (resolution == null) return null;
        if (resolution.getDefinition().usesImage()) {
            return renderImage(resolution.getIconPath());
        }
        VSCodeIconTheme.FontDefinition font = resolution.getFontDefinition();
        return renderGlyph(resolution.getFontCharacter(), font,
            resolution.getFontColor(), resolution.getFontSize());
    }

    @Nullable
    private Drawable renderDefinition(@Nullable VSCodeIconTheme.IconDefinition definition,
                                      @Nullable String fontId,
                                      @Nullable String fontColor,
                                      @Nullable String fontSize) {
        if (definition == null) return null;
        if (definition.usesImage()) return renderImage(definition.getIconPath());
        VSCodeIconTheme.FontDefinition font = findFont(fontId);
        return renderGlyph(definition.getFontCharacter(), font, fontColor, fontSize);
    }

    @Nullable
    private Drawable renderImage(@Nullable String rawPath) {
        File file = resolveResource(rawPath);
        if (file == null || !file.isFile() || file.length() <= 0L
            || file.length() > MAX_ASSET_BYTES) return null;
        String key = file.getAbsolutePath() + "#" + file.lastModified() + "#" + file.length();
        Bitmap bitmap;
        synchronized (bitmapCache) { bitmap = bitmapCache.get(key); }
        if (bitmap == null || bitmap.isRecycled()) {
            String extension = extension(file.getName());
            try {
                if ("svg".equals(extension)) {
                    bitmap = renderSvg(file);
                } else if ("png".equals(extension) || "webp".equals(extension)
                    || "jpg".equals(extension) || "jpeg".equals(extension) || "bmp".equals(extension)) {
                    bitmap = decodeRaster(file);
                } else {
                    return null;
                }
            } catch (Exception ignored) {
                return null;
            }
            if (bitmap == null) return null;
            synchronized (bitmapCache) { bitmapCache.put(key, bitmap); }
        }
        return new BitmapDrawable(appContext.getResources(), bitmap);
    }

    @Nullable
    private static Bitmap renderSvg(File file) throws Exception {
        SVG.setInternalEntitiesEnabled(false);
        try (FileInputStream input = new FileInputStream(file)) {
            SVG svg = SVG.getFromInputStream(input);
            Bitmap bitmap = Bitmap.createBitmap(RENDER_SIZE_PX, RENDER_SIZE_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            svg.renderToCanvas(canvas, new RectF(0f, 0f, RENDER_SIZE_PX, RENDER_SIZE_PX));
            return bitmap;
        }
    }

    @Nullable
    private static Bitmap decodeRaster(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / sample > RENDER_SIZE_PX * 4
            || bounds.outHeight / sample > RENDER_SIZE_PX * 4) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) return null;
        float scale = Math.min((float) RENDER_SIZE_PX / decoded.getWidth(),
            (float) RENDER_SIZE_PX / decoded.getHeight());
        int width = Math.max(1, Math.round(decoded.getWidth() * scale));
        int height = Math.max(1, Math.round(decoded.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        Bitmap canvasBitmap = Bitmap.createBitmap(RENDER_SIZE_PX, RENDER_SIZE_PX,
            Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        float left = (RENDER_SIZE_PX - width) / 2f;
        float top = (RENDER_SIZE_PX - height) / 2f;
        canvas.drawBitmap(scaled, left, top, null);
        if (scaled != decoded) scaled.recycle();
        decoded.recycle();
        return canvasBitmap;
    }

    @Nullable
    private Drawable renderGlyph(@Nullable String glyph,
                                 @Nullable VSCodeIconTheme.FontDefinition font,
                                 @Nullable String color, @Nullable String size) {
        if (glyph == null || glyph.isEmpty() || font == null) return null;
        Typeface typeface = loadTypeface(font);
        if (typeface == null) return null;
        int resolvedColor = parseColor(color, Color.GRAY);
        float resolvedSize = parseFontSize(size, 20f);
        return new FontGlyphDrawable(glyph, typeface, resolvedColor, resolvedSize);
    }

    @Nullable
    private Typeface loadTypeface(VSCodeIconTheme.FontDefinition font) {
        for (VSCodeIconTheme.FontSource source : font.getSources()) {
            File file = resolveResource(source.getPath());
            if (file == null || !file.isFile() || file.length() <= 0L
                || file.length() > MAX_FONT_BYTES) continue;
            String ext = extension(file.getName());
            // Android Typeface supports TTF/OTF. WOFF/WOFF2 stay safely on the built-in fallback.
            if (!"ttf".equals(ext) && !"otf".equals(ext)) continue;
            String key = file.getAbsolutePath() + "#" + file.lastModified();
            synchronized (typefaceCache) {
                Typeface cached = typefaceCache.get(key);
                if (cached != null) return cached;
            }
            try {
                Typeface loaded = Typeface.createFromFile(file);
                synchronized (typefaceCache) { typefaceCache.put(key, loaded); }
                return loaded;
            } catch (RuntimeException ignored) {
                // Try the next declared font source.
            }
        }
        return null;
    }

    @Nullable
    private VSCodeIconTheme.FontDefinition findFont(@Nullable String id) {
        if (theme == null) return null;
        if (id != null) {
            for (VSCodeIconTheme.FontDefinition font : theme.getFonts()) {
                if (id.equals(font.getId())) return font;
            }
        }
        for (VSCodeIconTheme.FontDefinition font : theme.getFonts()) {
            if (font.getSources() != null && !font.getSources().isEmpty()) return font;
        }
        return null;
    }

    @Nullable
    private String effectiveFontId(VSCodeIconTheme.IconDefinition definition) {
        if (definition.getFontId() != null) return definition.getFontId();
        VSCodeIconTheme.Associations associations = theme == null ? null : theme.getAssociations();
        if (associations != null && associations.getFile() != null) {
            VSCodeIconTheme.IconDefinition defaultFile = theme.getIconDefinition(associations.getFile());
            if (defaultFile != null && defaultFile.getFontId() != null) return defaultFile.getFontId();
        }
        VSCodeIconTheme.FontDefinition font = findFont(null);
        return font == null ? null : font.getId();
    }

    @Nullable
    private String effectiveFontColor(VSCodeIconTheme.IconDefinition definition) {
        if (definition.getFontColor() != null) return definition.getFontColor();
        VSCodeIconTheme.Associations associations = theme == null ? null : theme.getAssociations();
        if (associations != null && associations.getFile() != null) {
            VSCodeIconTheme.IconDefinition defaultFile = theme.getIconDefinition(associations.getFile());
            if (defaultFile != null) return defaultFile.getFontColor();
        }
        return null;
    }

    @Nullable
    private String effectiveFontSize(VSCodeIconTheme.IconDefinition definition) {
        if (definition.getFontSize() != null) return definition.getFontSize();
        VSCodeIconTheme.Associations associations = theme == null ? null : theme.getAssociations();
        if (associations != null && associations.getFile() != null) {
            VSCodeIconTheme.IconDefinition defaultFile = theme.getIconDefinition(associations.getFile());
            if (defaultFile != null) return defaultFile.getFontSize();
        }
        return null;
    }

    @Nullable
    private File resolveResource(@Nullable String rawReference) {
        if (extensionRoot == null || themeDocumentPath == null || rawReference == null) return null;
        String reference = rawReference.trim();
        if (reference.isEmpty() || reference.startsWith("/") || reference.startsWith("\\")
            || reference.indexOf('\\') >= 0 || reference.indexOf('\0') >= 0
            || reference.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) return null;
        String parent = "";
        int slash = themeDocumentPath.lastIndexOf('/');
        if (slash >= 0) parent = themeDocumentPath.substring(0, slash + 1);
        String combined = parent + reference;
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : combined.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) return null;
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        StringBuilder relative = new StringBuilder();
        for (String segment : segments) {
            if (relative.length() > 0) relative.append(File.separatorChar);
            relative.append(segment);
        }
        try {
            File root = extensionRoot.getCanonicalFile();
            File target = new File(root, relative.toString()).getCanonicalFile();
            String rootPath = root.getPath();
            if (!target.getPath().startsWith(rootPath + File.separator)) return null;
            return target;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String normalizeDocumentPath(String rawPath) {
        String path = rawPath.trim().replace('\\', '/');
        if (path.startsWith("/")) throw new IllegalArgumentException("Theme path must be relative");
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) throw new IllegalArgumentException("Theme path escapes extension root");
                segments.removeLast();
            } else segments.addLast(segment);
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("Theme path is empty");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(segment);
        }
        return normalized.toString();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static int parseColor(@Nullable String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try { return Color.parseColor(value.trim()); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private static float parseFontSize(@Nullable String value, float fallback) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (normalized.endsWith("%")) return Math.max(8f, Math.min(64f,
                RENDER_SIZE_PX * Float.parseFloat(normalized.substring(0, normalized.length() - 1)) / 100f));
            if (normalized.endsWith("px")) normalized = normalized.substring(0, normalized.length() - 2);
            else if (normalized.endsWith("em")) return fallback * Float.parseFloat(
                normalized.substring(0, normalized.length() - 2));
            float parsed = Float.parseFloat(normalized);
            return Math.max(8f, Math.min(64f, parsed));
        } catch (NumberFormatException ignored) { return fallback; }
    }

    private static final class UiBinding {
        final String slotId;
        final int fallbackDrawable;
        final Drawable fallbackOverride;
        final AppThemeColorRole fallbackTintRole;
        final ColorStateList originalTint;
        UiBinding(String slotId, int fallbackDrawable, Drawable fallbackOverride,
                  AppThemeColorRole fallbackTintRole, ColorStateList originalTint) {
            this.slotId = slotId;
            this.fallbackDrawable = fallbackDrawable;
            this.fallbackOverride = fallbackOverride;
            this.fallbackTintRole = fallbackTintRole;
            this.originalTint = originalTint;
        }
    }

    private static final class CompoundBinding {
        final String slotId;
        final Drawable fallback;
        final int width;
        final int height;
        CompoundBinding(String slotId, Drawable fallback, int width, int height) {
            this.slotId = slotId;
            this.fallback = fallback;
            this.width = width;
            this.height = height;
        }
    }

    private static final class AssociationBinding {
        final boolean folder;
        final String name;
        final String parentName;
        final String languageId;
        final boolean expanded;
        final boolean root;
        final Drawable fallback;

        private AssociationBinding(boolean folder, String name, String parentName,
            String languageId, boolean expanded, boolean root, Drawable fallback) {
            this.folder = folder;
            this.name = name;
            this.parentName = parentName;
            this.languageId = languageId;
            this.expanded = expanded;
            this.root = root;
            this.fallback = fallback;
        }
        static AssociationBinding file(String name, String parent, String language, Drawable fallback) {
            return new AssociationBinding(false, name, parent, language, false, false, fallback);
        }
        static AssociationBinding folder(String name, String parent, boolean expanded,
            boolean root, Drawable fallback) {
            return new AssociationBinding(true, name, parent, null, expanded, root, fallback);
        }
    }

    private static final class FontGlyphDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final String glyph;
        private final float requestedSize;

        FontGlyphDrawable(String glyph, Typeface typeface, int color, float size) {
            this.glyph = glyph;
            this.requestedSize = size;
            paint.setTypeface(typeface);
            paint.setColor(color);
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float size = Math.min(requestedSize, Math.min(bounds.width(), bounds.height()) * 0.9f);
            paint.setTextSize(size);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(glyph, bounds.exactCenterX(), baseline, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter); invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return RENDER_SIZE_PX; }
        @Override public int getIntrinsicHeight() { return RENDER_SIZE_PX; }
    }
}
