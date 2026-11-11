package com.termux.app.theme.adapter;

/** Parses the hexadecimal color formats accepted by VS Code Color Themes. */
public final class AppThemeColorValueParser {
    private AppThemeColorValueParser() { }

    /**
     * Parses {@code #RGB}, {@code #RGBA}, {@code #RRGGBB}, or {@code #RRGGBBAA}.
     *
     * <p>The returned integer uses Android's ARGB layout ({@code 0xAARRGGBB}). VS Code places
     * the optional alpha channel at the end of the hexadecimal value, so it is reordered here.</p>
     *
     * @throws IllegalArgumentException when the value is null or is not a supported hex color.
     */
    public static int parseHexColor(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Color value is required");
        }

        String hex = value.trim();
        if (hex.length() < 4 || hex.charAt(0) != '#') {
            throw new IllegalArgumentException("Expected a VS Code hexadecimal color: " + value);
        }

        int red;
        int green;
        int blue;
        int alpha = 255;
        switch (hex.length()) {
            case 4: // #RGB
                red = expandNibble(hex.charAt(1));
                green = expandNibble(hex.charAt(2));
                blue = expandNibble(hex.charAt(3));
                break;
            case 5: // #RGBA
                red = expandNibble(hex.charAt(1));
                green = expandNibble(hex.charAt(2));
                blue = expandNibble(hex.charAt(3));
                alpha = expandNibble(hex.charAt(4));
                break;
            case 7: // #RRGGBB
                red = parseByte(hex, 1);
                green = parseByte(hex, 3);
                blue = parseByte(hex, 5);
                break;
            case 9: // #RRGGBBAA
                red = parseByte(hex, 1);
                green = parseByte(hex, 3);
                blue = parseByte(hex, 5);
                alpha = parseByte(hex, 7);
                break;
            default:
                throw new IllegalArgumentException("Unsupported VS Code color length: " + value);
        }

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int expandNibble(char value) {
        int nibble = Character.digit(value, 16);
        if (nibble < 0) {
            throw new IllegalArgumentException("Invalid hexadecimal color digit: " + value);
        }
        return (nibble << 4) | nibble;
    }

    private static int parseByte(String value, int offset) {
        int high = Character.digit(value.charAt(offset), 16);
        int low = Character.digit(value.charAt(offset + 1), 16);
        if (high < 0 || low < 0) {
            throw new IllegalArgumentException("Invalid hexadecimal color: " + value);
        }
        return (high << 4) | low;
    }
}
