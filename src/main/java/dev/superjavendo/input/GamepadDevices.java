package dev.superjavendo.input;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The joystick devices the Linux joystick API exposes: {@code /dev/input/jsN}, named from {@code /sys/class/input/jsN/device/name}. */
public final class GamepadDevices {
    private GamepadDevices() { }

    public static boolean connected(int index) { return Files.isReadable(Path.of("/dev/input/js" + index)); }

    /** Name of the device, or "" if unknown. */
    public static String name(int index) {
        try { return Files.readString(Path.of("/sys/class/input/js" + index + "/device/name"), StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    /** Indices of the devices present now, ascending. */
    public static List<Integer> present() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) if (Files.exists(Path.of("/dev/input/js" + i))) out.add(i);
        return out;
    }
}
