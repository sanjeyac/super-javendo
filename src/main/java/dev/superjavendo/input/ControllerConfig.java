package dev.superjavendo.input;

import dev.superjavendo.config.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wires controller profiles to players. Reads <code>~/.config/superjavendo/controllers.cfg</code>:
 * <pre>
 * player1=keyboard,easysmx@0     # profile names (joystick/&lt;name&gt;.cfg); "@N" picks /dev/input/jsN
 * player2=xbox@1
 * </pre>
 * The command line overrides the file: {@code -p1 easysmx} / {@code -p2 keyboard2,xbox@1}.
 * Players 3 and 4 are accepted (SNES multitap) but only ports 1 and 2 are emulated for now.
 */
public final class ControllerConfig {
    static final String DEFAULT = """
            # Assegna i profili (joystick/<nome>.cfg) ai giocatori. "@N" sceglie /dev/input/jsN (default 0).
            # Più profili sullo stesso giocatore si sommano (es. tastiera + pad).
            # Il SNES con multitap arriva a 4 giocatori: per ora sono emulate solo le porte 1 e 2.
            player1=keyboard,easysmx@0
            player2=keyboard2,xbox@1
            """;

    private ControllerConfig() { }

    /** Same as {@link #configure(ControllerPorts, ControllerFactory, AppPaths, Map)} with no command-line overrides. */
    public static void configure(ControllerPorts ports, ControllerFactory factory, AppPaths paths) {
        configure(ports, factory, paths, Map.of());
    }

    /**
     * Creates missing files, then plugs every configured player into {@code ports}. Problems in the config
     * file are reported and skipped; a profile requested through {@code overrides} (player number to spec,
     * e.g. {@code 1 -> "easysmx@0"}) must exist, otherwise an {@link IllegalArgumentException} lists the choices.
     */
    public static void configure(ControllerPorts ports, ControllerFactory factory, AppPaths paths, Map<Integer, String> overrides) {
        ProfileStore store = new ProfileStore(paths.joystickDir());
        Path cfg = paths.controllersFile();
        try {
            store.installDefaults();
            if (!Files.exists(cfg)) Files.write(cfg, DEFAULT.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Cannot write controller config in " + paths.configDir() + ": " + e.getMessage());
        }
        List<String> lines;
        try { lines = Files.readAllLines(cfg, StandardCharsets.UTF_8); }
        catch (IOException e) { lines = List.of(DEFAULT.split("\n")); }
        for (int player = 1; player <= 2; player++) {
            String forced = overrides.get(player);
            ControllerInput in = forced != null
                    ? factory.createAll(profiles(player, forced, store, true).toArray(new ControllerProfile[0]))
                    : factory.createAll(profiles(player, specOf(player, lines), store, false).toArray(new ControllerProfile[0]));
            ports.plug(player - 1, in);
        }
        for (int extra : overrides.keySet())
            if (extra > 2) System.err.println("Player " + extra + " ignored: only ports 1 and 2 are emulated");
    }

    /** Validates command-line overrides before the window opens (creates default files if needed). */
    public static void check(AppPaths paths, Map<Integer, String> overrides) {
        ProfileStore store = new ProfileStore(paths.joystickDir());
        try { store.installDefaults(); } catch (IOException ignored) { }
        overrides.forEach((player, spec) -> profiles(player, spec, store, true));
    }

    private static String specOf(int player, List<String> lines) {
        for (String raw : lines) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')).trim() : raw.trim();
            if (line.startsWith("player" + player + "=")) return line.substring(line.indexOf('=') + 1);
        }
        return "";
    }

    private static List<ControllerProfile> profiles(int player, String spec, ProfileStore store, boolean strict) {
        List<ControllerProfile> profiles = new ArrayList<>();
        for (String item : spec.split(",")) {
            item = item.trim();
            if (item.isEmpty()) continue;
            int at = item.indexOf('@');
            String name = at < 0 ? item : item.substring(0, at);
            try {
                ControllerProfile p = store.load(name);
                if (at >= 0) p = p.withDevice(Integer.parseInt(item.substring(at + 1).trim()));
                else if (p.type == ControllerProfile.Type.GAMEPAD) p = p.withDevice(0);
                profiles.add(p);
            } catch (IOException | IllegalArgumentException e) {
                if (strict) throw new IllegalArgumentException("player" + player + ": cannot use profile '" + item + "' ("
                        + e.getMessage() + "). Available in " + store.dir() + ": " + available(store));
                System.err.println("player" + player + ": skipping '" + item + "': " + e.getMessage());
            }
        }
        return profiles;
    }

    private static String available(ProfileStore store) {
        try (var s = Files.list(store.dir())) {
            return s.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".cfg"))
                    .map(f -> f.substring(0, f.length() - 4)).sorted().toList().toString();
        } catch (IOException e) { return "?"; }
    }
}
