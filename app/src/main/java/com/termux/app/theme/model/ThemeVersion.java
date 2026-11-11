package com.termux.app.theme.model;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Semantic Versioning 2.0.0 value with build metadata ignored for precedence. */
public final class ThemeVersion implements Comparable<ThemeVersion> {
    private static final Pattern VERSION = Pattern.compile(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
            + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
    );

    private final String value;
    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    private final String prerelease;

    private ThemeVersion(String value, String major, String minor, String patch, String prerelease) {
        this.value = value;
        this.major = new BigInteger(major);
        this.minor = new BigInteger(minor);
        this.patch = new BigInteger(patch);
        this.prerelease = prerelease;
    }

    public static ThemeVersion parse(String value) {
        if (value == null) throw new IllegalArgumentException("Theme version is required");
        String normalized = value.trim();
        if (normalized.length() > 64) throw new IllegalArgumentException("Theme version exceeds 64 characters");
        java.util.regex.Matcher matcher = VERSION.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                "Invalid theme version. Expected SemVer MAJOR.MINOR.PATCH: " + normalized);
        }
        String prerelease = matcher.group(4);
        if (prerelease != null) {
            String[] identifiers = prerelease.split("\\.", -1);
            for (String identifier : identifiers) {
                if (identifier.isEmpty()) throw new IllegalArgumentException("Invalid prerelease identifier");
                if (identifier.matches("[0-9]+") && identifier.length() > 1
                    && identifier.charAt(0) == '0') {
                    throw new IllegalArgumentException("Numeric prerelease identifiers cannot contain leading zeroes");
                }
            }
        }
        return new ThemeVersion(normalized, matcher.group(1), matcher.group(2),
            matcher.group(3), prerelease);
    }

    public String getValue() { return value; }

    @Override
    public int compareTo(ThemeVersion other) {
        if (other == null) throw new IllegalArgumentException("Other theme version is required");
        int result = major.compareTo(other.major);
        if (result != 0) return result;
        result = minor.compareTo(other.minor);
        if (result != 0) return result;
        result = patch.compareTo(other.patch);
        if (result != 0) return result;
        if (prerelease == null) return other.prerelease == null ? 0 : 1;
        if (other.prerelease == null) return -1;

        String[] left = prerelease.split("\\.");
        String[] right = other.prerelease.split("\\.");
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            boolean leftNumeric = left[i].matches("[0-9]+");
            boolean rightNumeric = right[i].matches("[0-9]+");
            if (leftNumeric && rightNumeric) {
                result = new BigInteger(left[i]).compareTo(new BigInteger(right[i]));
            } else if (leftNumeric != rightNumeric) {
                result = leftNumeric ? -1 : 1;
            } else {
                result = left[i].compareTo(right[i]);
            }
            if (result != 0) return result;
        }
        return Integer.compare(left.length, right.length);
    }

    @Override public String toString() { return value; }
}
