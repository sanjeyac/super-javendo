package dev.superjavendo.frontend.modern;

import dev.superjavendo.i18n.I18n;

import java.text.NumberFormat;
import java.util.Locale;

/** Number and time formats of the Rec editor. */
final class RecFormat {
    static final double FPS = 60.0988;

    private RecFormat() { }

    /** m:ss.cc for a frame count (frame / 60.0988). */
    static String time(int frames) {
        double s = frames / FPS;
        int m = (int) (s / 60);
        return String.format(Locale.ROOT, "%d:%05.2f", m, s - m * 60);
    }

    /** Integer with the thousands separator of the interface language. */
    static String num(int n) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag(I18n.language())).format(n);
    }
}
