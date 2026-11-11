package dev.superjavendo.i18n;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * User-visible texts. Bundles live in {@code /i18n/messages[_xx].properties} (UTF-8); English is the root bundle and
 * the fallback for a missing key. Placeholders are {@code {0}}, {@code {1}}… (plain replacement, so apostrophes are safe).
 */
public final class I18n {
    /** Language code to name in that language, in menu order. */
    public record Language(String code, String name) { }

    public static final List<Language> LANGUAGES = List.of(
            new Language("en", "English"), new Language("it", "Italiano"), new Language("es", "Español"),
            new Language("fr", "Français"), new Language("de", "Deutsch"), new Language("pt", "Português"),
            new Language("nl", "Nederlands"), new Language("pl", "Polski"));

    private static final ResourceBundle.Control NO_FALLBACK = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
    private static ResourceBundle bundle, root;
    private static String current = "en";

    static { set("en"); }

    private I18n() { }

    /** The best supported language for a code or locale tag ("it", "pt-BR", "" = the system language); English if none matches. */
    public static String resolve(String code) {
        String c = code == null || code.isBlank() ? Locale.getDefault().getLanguage() : Locale.forLanguageTag(code.replace('_', '-')).getLanguage();
        for (Language l : LANGUAGES) if (l.code().equals(c)) return c;
        return "en";
    }

    /** Selects the language (see {@link #resolve}); returns the one in use. */
    public static String set(String code) {
        current = resolve(code);
        root = ResourceBundle.getBundle("i18n.messages", Locale.ROOT, NO_FALLBACK);
        bundle = current.equals("en") ? root : ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag(current), NO_FALLBACK);
        return current;
    }

    public static String language() { return current; }

    /** Bundle of one language (tests). */
    public static ResourceBundle bundleOf(String code) {
        return code.equals("en") ? ResourceBundle.getBundle("i18n.messages", Locale.ROOT, NO_FALLBACK)
                : ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag(code), NO_FALLBACK);
    }

    /** Text for the key with {0}, {1}… replaced by the arguments. A key missing everywhere comes back as itself. */
    public static String t(String key, Object... args) {
        String s;
        try { s = bundle.getString(key); }
        catch (MissingResourceException e) {
            try { s = root.getString(key); } catch (MissingResourceException e2) { return key; }
        }
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }
}
