package dev.superjavendo.frontend.modern;

import dev.superjavendo.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PadNavTest {
    @TempDir Path tmp;

    private AppPaths paths(String settings, String... profiles) throws Exception {
        AppPaths p = new AppPaths(tmp);
        Files.createDirectories(p.joystickDir());
        Files.writeString(p.settingsFile(), settings);
        for (int i = 0; i < profiles.length; i += 2) Files.writeString(p.joystickDir().resolve(profiles[i] + ".cfg"), profiles[i + 1]);
        return p;
    }

    @Test void tokensComeFromTheGamepadProfileOfThePlayerOnThatDevice() throws Exception {
        AppPaths p = paths("player1=pad@0\nplayer2=other@1\n",
                "pad", "type=gamepad\nup=AXIS_1-\ndown=AXIS_1+\na=BUTTON_0\nb=BUTTON_1\nstart=BUTTON_7\n",
                "other", "type=gamepad\na=BUTTON_5\n");
        Map<String, String> t = PadNav.tokensFor(p, 0);
        assertEquals("a", t.get("BUTTON_0"));
        assertEquals("up", t.get("AXIS_1-"));
        assertEquals("start", t.get("BUTTON_7"));
        assertNull(t.get("BUTTON_5"));                   // belongs to player 2 on js1
    }

    @Test void profileWithoutDeviceAppliesToEveryDevice() throws Exception {
        AppPaths p = paths("player1=pad\n", "pad", "type=gamepad\na=BUTTON_0\n");
        assertEquals("a", PadNav.tokensFor(p, 3).get("BUTTON_0"));
    }

    @Test void keyboardProfilesAndMissingFilesAreIgnored() throws Exception {
        AppPaths p = paths("player1=kb,ghost\n", "kb", "type=keyboard\na=KEY_Z\n");
        assertTrue(PadNav.tokensFor(p, 0).isEmpty());
    }

    @Test void firstProfileWinsWhenTwoUseTheSameToken() throws Exception {
        AppPaths p = paths("player1=one,two\n", "one", "type=gamepad\na=BUTTON_0\n", "two", "type=gamepad\nb=BUTTON_0\n");
        assertEquals("a", PadNav.tokensFor(p, 0).get("BUTTON_0"));
    }
}
