package dev.superjavendo.input;

import dev.superjavendo.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileParserTest {
    @Test void parsesKeyboardProfile() {
        ControllerProfile p = ProfileParser.parse("k.cfg", List.of(
                "# comment", "type=keyboard", "name=Mine", "up=KEY_UP", "b = key_z , KEY_SPACE  # two keys"));
        assertEquals("Mine", p.name);
        assertEquals(Joypad.UP, p.buttons.get(KeyEvent.VK_UP));
        assertEquals(Joypad.B, p.buttons.get(KeyEvent.VK_Z));
        assertEquals(Joypad.B, p.buttons.get(KeyEvent.VK_SPACE));
    }

    @Test void parsesGamepadProfile() {
        ControllerProfile p = ProfileParser.parse("g.cfg", List.of(
                "type=gamepad", "threshold=5000", "up=AXIS_7-,BUTTON_12", "a=BUTTON_1"));
        assertEquals(5000, p.threshold);
        assertEquals(Joypad.A, p.buttons.get(1));
        assertEquals(Joypad.UP, p.buttons.get(12));
        assertEquals(new ControllerProfile.AxisBinding(7, -1, Joypad.UP), p.axes.get(0));
    }

    @Test void reportsErrorsWithFileAndLine() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ProfileParser.parse("bad.cfg", List.of("type=keyboard", "up=KEY_NOPE")));
        assertTrue(e.getMessage().contains("bad.cfg") && e.getMessage().contains("KEY_NOPE"));
        assertThrows(IllegalArgumentException.class, () -> ProfileParser.parse("x.cfg", List.of("up=KEY_UP")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParser.parse("x.cfg", List.of("type=keyboard", "jump=KEY_UP")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParser.parse("x.cfg", List.of("type=gamepad", "up=KEY_UP")));
    }

    @Test void allBundledProfilesAreValidAndComplete() {
        for (String n : ProfileStore.BUNDLED) {
            ControllerProfile p = ProfileStore.bundled(n);
            int all = 0;
            for (int m : p.buttons.values()) all |= m & 0xFFF0;
            for (var a : p.axes) all |= a.mask();
            assertEquals(0xFFF0, all, n + " must bind all 12 buttons");
        }
        assertEquals(ControllerProfile.Type.GAMEPAD, ProfileStore.bundled("easysmx").type);
    }

    @Test void configCreatesFilesAndPlugsPlayers(@TempDir Path tmp) throws Exception {
        AppPaths paths = new AppPaths(tmp);
        ControllerPorts ports = new ControllerPorts();
        // keyboards only: a real gamepad plugged into the build machine must not influence the test
        ControllerConfig.configure(ports, new ControllerFactory(), paths, java.util.Map.of(1, "keyboard", 2, "keyboard2"));
        for (String n : ProfileStore.BUNDLED) assertTrue(Files.exists(paths.joystickDir().resolve(n + ".cfg")));
        assertTrue(Files.exists(paths.controllersFile()));
        ports.onKey(KeyEvent.VK_Z, true);
        assertEquals(Joypad.B, ports.get(0).poll());
        assertEquals(0, ports.get(1).poll());
        ports.close();
    }

    @Test void userEditsSurviveAndBadProfilesAreSkipped(@TempDir Path tmp) throws Exception {
        AppPaths paths = new AppPaths(tmp);
        Files.createDirectories(paths.joystickDir());
        Files.writeString(paths.joystickDir().resolve("keyboard.cfg"), "type=keyboard\nb=KEY_K\n");
        Files.writeString(paths.joystickDir().resolve("broken.cfg"), "type=keyboard\nb=KEY_???\n");
        Files.writeString(paths.controllersFile(), "player1=broken,keyboard\nplayer2=missing\n");
        ControllerPorts ports = new ControllerPorts();
        ControllerConfig.configure(ports, new ControllerFactory(), paths);
        ports.onKey(KeyEvent.VK_K, true);
        assertEquals(Joypad.B, ports.get(0).poll());       // custom file kept, broken one ignored
        assertEquals(0, ports.get(1).poll());
        ports.close();
    }

    @Test void commandLineOverridesTheConfigFile(@TempDir Path tmp) throws Exception {
        AppPaths paths = new AppPaths(tmp);
        ControllerPorts ports = new ControllerPorts();
        ControllerConfig.configure(ports, new ControllerFactory(), paths, java.util.Map.of(1, "keyboard2", 2, "keyboard@3"));
        ports.onKey(KeyEvent.VK_Z, true);          // keyboard P1 key: nobody uses it now
        ports.onKey(KeyEvent.VK_J, true);          // keyboard2 left -> player 1
        ports.onKey(KeyEvent.VK_UP, true);         // keyboard up -> player 2
        assertEquals(Joypad.LEFT, ports.get(0).poll());
        assertEquals(Joypad.UP | Joypad.B, ports.get(1).poll());
        ports.close();
    }

    @Test void unknownProfileOnCommandLineListsTheChoices(@TempDir Path tmp) {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ControllerConfig.check(new AppPaths(tmp), java.util.Map.of(1, "nope")));
        assertTrue(e.getMessage().contains("nope") && e.getMessage().contains("easysmx"));
    }

    @Test void oneSnesButtonCanHaveManyInputs() {
        ControllerProfile p = ProfileParser.parse("g.cfg", List.of("type=gamepad", "l=BUTTON_6,BUTTON_8", "l=AXIS_2+",
                "save_state=BUTTON_13"));
        assertEquals(Joypad.L, p.buttons.get(6));
        assertEquals(Joypad.L, p.buttons.get(8));
        assertEquals(new ControllerProfile.AxisBinding(2, 1, Joypad.L), p.axes.get(0));
        assertEquals(ControllerInput.SAVE_STATE, p.buttons.get(13));
    }

    @Test void saveLoadButtonsAreActionsNotConsoleButtons() {
        ControllerPorts ports = new ControllerPorts();
        ports.plug(0, new KeyboardInput(ProfileStore.bundled("keyboard")));
        ports.onKey(KeyEvent.VK_F5, true);
        assertEquals(ControllerInput.SAVE_STATE, ports.actions());
        Joypad jp = new Joypad();
        ports.apply(jp);
        jp.writeStrobe(1); jp.writeStrobe(0);
        for (int i = 0; i < 16; i++) assertEquals(0, jp.readBit(0));   // nothing reaches the pad
    }

    @Test void stateSlotsHaveDistinctFiles() {
        AppPaths p = new AppPaths(Path.of("/c"));
        Path rom = Path.of("/r/Game.sfc");
        assertEquals(Path.of("/c/states/Game.state"), p.stateFile(rom, 0));
        assertEquals(Path.of("/c/states/Game.3.state"), p.stateFile(rom, 3));
    }
}
