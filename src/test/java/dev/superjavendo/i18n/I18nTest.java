package dev.superjavendo.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class I18nTest {
    @AfterEach void reset() { I18n.set("en"); }

    private static Set<String> placeholders(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("\\{\\d+}").matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test void everyLanguageHasExactlyTheKeysOfEnglishWithTheSamePlaceholders() {
        ResourceBundle en = I18n.bundleOf("en");
        for (I18n.Language l : I18n.LANGUAGES) {
            ResourceBundle b = I18n.bundleOf(l.code());
            assertEquals(new HashSet<>(en.keySet()), new HashSet<>(b.keySet()), "keys of " + l.code());
            for (String k : en.keySet()) {
                assertFalse(b.getString(k).isBlank(), l.code() + ":" + k);
                assertEquals(placeholders(en.getString(k)), placeholders(b.getString(k)), l.code() + ":" + k);
            }
        }
    }

    @Test void resolvesCodesAndFallsBackToEnglish() {
        assertEquals("it", I18n.resolve("it"));
        assertEquals("pt", I18n.resolve("pt-BR"));
        assertEquals("en", I18n.resolve("ja"));
        assertEquals("de", I18n.set("de"));
        assertEquals("3 Spiele", I18n.t("lib.countn", 3));
    }

    @Test void substitutesArgumentsAndReturnsUnknownKeysAsTheyAre() {
        I18n.set("it");
        assertEquals("3 giochi", I18n.t("lib.countn", 3));
        assertEquals("no.such.key", I18n.t("no.such.key"));
        I18n.set("en");
        assertEquals("Slot 2", I18n.t("slot.n", 2));
    }
}
