package dev.superjavendo.input;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Controller profiles on disk: <code>&lt;dir&gt;/&lt;name&gt;.cfg</code>. Bundled defaults are copied there when missing. */
public final class ProfileStore {
    public static final List<String> BUNDLED = List.of("keyboard", "keyboard2", "easysmx", "xbox");

    private final Path dir;

    public ProfileStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    public Path file(String name) { return dir.resolve(name + ".cfg"); }

    /** Writes the bundled profiles that do not exist yet; never overwrites a user's edits. */
    public void installDefaults() throws IOException {
        Files.createDirectories(dir);
        for (String n : BUNDLED) {
            Path f = file(n);
            if (!Files.exists(f)) Files.write(f, bundledText(n).getBytes(StandardCharsets.UTF_8));
        }
    }

    public ControllerProfile load(String name) throws IOException {
        return ProfileParser.parse(name + ".cfg", Files.readAllLines(file(name), StandardCharsets.UTF_8));
    }

    public static ControllerProfile bundled(String name) {
        return ProfileParser.parse(name + ".cfg", List.of(bundledText(name).split("\n")));
    }

    private static String bundledText(String name) {
        try (InputStream in = ProfileStore.class.getResourceAsStream("/profiles/" + name + ".cfg")) {
            if (in == null) throw new IllegalArgumentException("No bundled profile '" + name + "'");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
