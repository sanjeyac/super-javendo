package dev.superjavendo.games;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Registry / factory: CRC32 (No-Intro, ROM data without copier header) → the {@link GameSupport} of that game.
 * A CRC that is here is a verified dump of a game we know; an unknown CRC just means "no idea", not "invalid" (the whole
 * catalogue is {@code NoIntroDb}). To support a new game: write its class and add one {@code register} line in the static block.
 */
public final class Games {
    private Games() { }

    private static final Map<String, Supplier<? extends GameSupport>> BY_CRC = new HashMap<>();

    static {
        register(FZero.CRCS, FZero::new);
    }

    private static void register(Set<String> crcs, Supplier<? extends GameSupport> factory) {
        for (String crc : crcs) {
            if (BY_CRC.put(crc, factory) != null) throw new IllegalStateException("CRC registered twice: " + crc);
        }
    }

    /** A new support object for the game with this CRC32 (hex, any case), or empty if we do not know it. */
    public static Optional<GameSupport> byCrc(String crc) {
        if (crc == null) return Optional.empty();
        Supplier<? extends GameSupport> f = BY_CRC.get(crc.trim().toUpperCase(Locale.ROOT));
        return f == null ? Optional.empty() : Optional.of(f.get());
    }

    /** The No-Intro name of this dump if one of our games owns the CRC, else null. Needs no DAT file. */
    public static String dumpName(String crc) { return byCrc(crc).map(g -> g.dumpName(crc)).orElse(null); }

    /** Every registered CRC (tests, diagnostics). */
    static Set<String> crcs() { return Set.copyOf(BY_CRC.keySet()); }
}
