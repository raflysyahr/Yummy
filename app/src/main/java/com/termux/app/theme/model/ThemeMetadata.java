package com.termux.app.theme.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Yummy-owned metadata that describes a VS Code-compatible Color Theme file.
 *
 * This class intentionally does not parse JSON. JSON loading and validation are
 * handled by the theme loader in the next implementation stage.
 */
public final class ThemeMetadata {
    private static final Pattern VALID_ID =
        Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private final String id;
    private final String label;
    private final ThemeAppearance appearance;
    private final ThemeSource source;
    private final String themeFile;
    private final String author;
    private final String license;
    private final String licenseFile;
    private final String sourceUrl;
    private final Set<ThemeTarget> supportedTargets;
    private final Set<ThemeTarget> defaultTargets;

    public ThemeMetadata(
        String id,
        String label,
        ThemeAppearance appearance,
        ThemeSource source,
        String themeFile,
        String author,
        String license,
        String licenseFile,
        String sourceUrl,
        Set<ThemeTarget> supportedTargets,
        Set<ThemeTarget> defaultTargets
    ) {
        this.id = requireId(id);
        this.label = requireText(label, "Theme label");
        this.appearance = requireValue(appearance, "Theme appearance");
        this.source = requireValue(source, "Theme source");
        this.themeFile = requireRelativePath(themeFile, "Theme file");
        this.author = optionalText(author);
        this.license = optionalText(license);
        this.licenseFile = optionalRelativePath(licenseFile, "License file");
        this.sourceUrl = optionalText(sourceUrl);
        this.supportedTargets = immutableTargets(supportedTargets, "Supported targets");
        this.defaultTargets = immutableTargetsAllowEmpty(defaultTargets, "Default targets");

        if (!this.supportedTargets.containsAll(this.defaultTargets)) {
            throw new IllegalArgumentException(
                "A theme can only be default for a target it supports");
        }
    }

    private static String requireId(String value) {
        String result = requireText(value, "Theme ID");
        if (!VALID_ID.matcher(result).matches()) {
            throw new IllegalArgumentException("Invalid theme ID: " + result);
        }
        return result;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String requireRelativePath(String value, String fieldName) {
        String path = requireText(value, fieldName).replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(fieldName + " must be relative");
        }
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(fieldName + " contains an unsafe path segment");
            }
        }
        return path;
    }

    private static String optionalRelativePath(String value, String fieldName) {
        return value == null || value.trim().isEmpty()
            ? null : requireRelativePath(value, fieldName);
    }

    private static <T> T requireValue(T value, String fieldName) {
        if (value == null) throw new IllegalArgumentException(fieldName + " is required");
        return value;
    }

    private static Set<ThemeTarget> immutableTargets(
        Set<ThemeTarget> values, String fieldName) {
        Set<ThemeTarget> copy = immutableTargetsAllowEmpty(values, fieldName);
        if (copy.isEmpty()) throw new IllegalArgumentException(fieldName + " cannot be empty");
        return copy;
    }

    private static Set<ThemeTarget> immutableTargetsAllowEmpty(
        Set<ThemeTarget> values, String fieldName) {
        if (values == null) throw new IllegalArgumentException(fieldName + " are required");
        EnumSet<ThemeTarget> copy = EnumSet.noneOf(ThemeTarget.class);
        for (ThemeTarget target : values) {
            if (target == null) throw new IllegalArgumentException(fieldName + " contain null");
            copy.add(target);
        }
        return Collections.unmodifiableSet(copy);
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public ThemeAppearance getAppearance() { return appearance; }
    public ThemeSource getSource() { return source; }
    public String getThemeFile() { return themeFile; }
    public String getAuthor() { return author; }
    public String getLicense() { return license; }
    public String getLicenseFile() { return licenseFile; }
    public String getSourceUrl() { return sourceUrl; }

    public Set<ThemeTarget> getSupportedTargets() {
        return supportedTargets;
    }

    public Set<ThemeTarget> getDefaultTargets() {
        return defaultTargets;
    }

    public boolean supports(ThemeTarget target) {
        return target != null && supportedTargets.contains(target);
    }

    public boolean isDefaultFor(ThemeTarget target) {
        return target != null && defaultTargets.contains(target);
    }
}
