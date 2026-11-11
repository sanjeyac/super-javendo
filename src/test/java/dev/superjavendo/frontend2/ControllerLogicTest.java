package dev.superjavendo.frontend2;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.config.Settings;
import dev.superjavendo.input.ControllerAssignments;
import dev.superjavendo.input.ProfileLibrary;
import dev.superjavendo.input.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControllerLogicTest {
    @TempDir Path tmp;

    private ProfileLibrary library() { return new ProfileLibrary(new ProfileStore(tmp.resolve("joystick"))); }

    @Test void listsBundledProfilesAndCreatesDuplicatesWithFreeIds() throws Exception {
        ProfileLibrary lib = library();
        var infos = lib.list();
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("easysmx") && !i.keyboard()));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("keyboard") && i.keyboard()));
        String a = lib.create("My Pad!", "gamepad"), b = lib.create("My Pad!", "gamepad");
        assertEquals("my-pad", a);
        assertEquals("my-pad-2", b);
        String copy = lib.duplicate("easysmx", "EasySMX copy");
        assertEquals(lib.read("easysmx").of("a"), lib.read(copy).of("a"));
        assertEquals("EasySMX copy", lib.read(copy).name);
    }

    @Test void editorTracksChangesAndSavesOnlyValidProfiles() throws Exception {
        ProfileLibrary lib = library();
        lib.list();
        ProfileEditor e = new ProfileEditor(lib, "easysmx");
        assertFalse(e.dirty());
        e.add("a", "BUTTON_5");
        assertTrue(e.dirty());
        assertTrue(e.targetsOf("BUTTON_5").contains("a"));
        e.save();
        assertFalse(e.dirty());
        assertTrue(lib.read("easysmx").of("a").contains("BUTTON_5"));
        e.add("a", "NOT_A_TOKEN");
        assertThrows(IllegalArgumentException.class, e::save);
        e.discard();
        assertFalse(e.dirty());
        e.remove("a", "BUTTON_5");
        e.defaults();                                              // back to the bundled bindings
        assertEquals(lib.bundled("easysmx").of("a"), lib.read("easysmx").of("a").stream().filter(t -> !t.equals("BUTTON_5")).toList());
        assertEquals("tok.hat", ProfileEditor.kindKey("AXIS_7-"));
        assertEquals("tok.leftstick", ProfileEditor.kindKey("AXIS_1+"));
        assertEquals("tok.key", ProfileEditor.kindKey("KEY_UP"));
        assertEquals("AXIS_7 −", ProfileEditor.pretty("AXIS_7-"));
    }

    @Test void assignmentsRewriteOnlyThePlayerLines() throws Exception {
        Path f = tmp.resolve("controllers.cfg");
        Files.writeString(f, "# my comment\nplayer1=keyboard,easysmx@0\nplayer2=xbox@1\nother=1\n");
        ControllerAssignments a = new ControllerAssignments(f);
        assertEquals(List.of(new ControllerAssignments.Entry("keyboard", -1), new ControllerAssignments.Entry("easysmx", 0)), a.of(1));
        a.set(2, "keyboard2", true, -1);
        a.set(1, "keyboard", false, -1);
        a.setDevice("easysmx", 3);
        assertEquals(List.of(new ControllerAssignments.Entry("easysmx", 3)), a.of(1));
        assertTrue(a.uses(2, "keyboard2"));
        String text = Files.readString(f);
        assertTrue(text.contains("# my comment") && text.contains("other=1"));
        assertTrue(text.contains("player1=easysmx@3") && text.contains("player2=xbox@1,keyboard2"));
    }

    @Test void settingsRoundTripAndClamp() throws Exception {
        AppPaths paths = new AppPaths(tmp);
        Settings s = Settings.load(paths);
        assertEquals(3, s.scale);
        s.language = "de"; s.filter = "nearest"; s.scale = 5; s.volume = 40;
        s.save();
        Settings r = Settings.load(paths);
        assertEquals("de", r.language);
        assertEquals("nearest", r.filter);
        assertEquals(5, r.scale);
        assertEquals(40, r.volume);
        Files.writeString(paths.settingsFile(), "video.scale=99\naudio.volume=-5\nvideo.filter=bogus\n");
        Settings c = Settings.load(paths);
        assertEquals(Settings.MAX_SCALE, c.scale);
        assertEquals(0, c.volume);
        assertEquals("sharp", c.filter);
    }
}
