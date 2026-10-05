package com.github.tvbox.osc.ui.kit;

/** Keeps a slider rail visible by increasing opacity without changing its theme RGB. */
final class SliderTrackColor {
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final double MIN_CONTRAST = 3.0;

    private SliderTrackColor() {
    }

    static int visibleTrack(int requestedTrack, int panelColor, int pageColor) {
        int background = compositeOnOpaque(panelColor, pageColor | 0xFF000000);
        int rgb = requestedTrack & RGB_MASK;
        for (int alpha = requestedTrack >>> 24; alpha <= 255; alpha++) {
            int candidate = (alpha << 24) | rgb;
            if (contrast(compositeOnOpaque(candidate, background), background) >= MIN_CONTRAST) {
                return candidate;
            }
        }
        // A low-contrast theme stays in its chosen color even if full opacity is insufficient.
        return 0xFF000000 | rgb;
    }

    /** Contrast between two rendered, opaque colors; alpha has already been composited. */
    static double contrast(int firstColor, int secondColor) {
        double first = luminance(firstColor);
        double second = luminance(secondColor);
        return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
    }

    private static int compositeOnOpaque(int foreground, int background) {
        int alpha = foreground >>> 24;
        int red = compositeChannel((foreground >>> 16) & 255, (background >>> 16) & 255, alpha);
        int green = compositeChannel((foreground >>> 8) & 255, (background >>> 8) & 255, alpha);
        int blue = compositeChannel(foreground & 255, background & 255, alpha);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int compositeChannel(int foreground, int background, int alpha) {
        return (foreground * alpha + background * (255 - alpha)) / 255;
    }

    private static double luminance(int color) {
        return 0.2126 * linearChannel((color >>> 16) & 255)
                + 0.7152 * linearChannel((color >>> 8) & 255)
                + 0.0722 * linearChannel(color & 255);
    }

    private static double linearChannel(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
