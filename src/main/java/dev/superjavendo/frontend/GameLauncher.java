package dev.superjavendo.frontend;

import dev.superjavendo.Snes;
import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.config.AppPaths;
import dev.superjavendo.ui.EmuWindow;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Loads a ROM and opens the emulator in its own window. */
public final class GameLauncher {
    private GameLauncher() { }

    /**
     * @param exitOnClose true for the stand-alone command line mode (closing the window quits the program);
     *                    false when started from the library, which keeps running
     * @return the window (created on the event thread), or null if the ROM could not be loaded
     */
    public static EmuWindow open(Path rom, Map<Integer, String> profiles, boolean exitOnClose) throws Exception {
        AppPaths paths = AppPaths.forCurrentUser();
        Cartridge cart = Cartridge.load(rom);
        String name = rom.getFileName().toString();
        Path srm = rom.resolveSibling(name.replaceFirst("\\.[^.]*$", "") + ".srm");
        paths.migrateLegacyState(rom);
        cart.loadSram(srm);
        System.out.printf("Title: %s | %s | Chip: %s | ROM %d KB | SRAM %d KB | checksum %s | reset $%04X%n",
                cart.title, cart.mapping, cart.chip.label, cart.rom.length / 1024, cart.sram.length / 1024,
                cart.checksumValid ? "ok" : "bad", cart.resetVector);
        Snes snes = new Snes(cart);
        AtomicReference<EmuWindow> ref = new AtomicReference<>();
        Runnable create = () -> ref.set(new EmuWindow(snes, srm, slot -> paths.stateFile(rom, slot), profiles, exitOnClose));
        if (SwingUtilities.isEventDispatchThread()) create.run();
        else SwingUtilities.invokeAndWait(create);
        return ref.get();
    }
}
