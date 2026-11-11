package dev.superjavendo.input;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** The profiles on disk (listing, creating, duplicating) and the editable copy of one of them. */
public final class ProfileLibrary {
    public record Info(String id, String name, String type) { public boolean keyboard() { return type.equals("keyboard"); } }

    private final ProfileStore store;

    public ProfileLibrary(ProfileStore store) { this.store = store; }

    public List<Info> list() throws IOException {
        store.installDefaults();
        List<Info> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(store.dir())) {
            for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".cfg")).sorted()::iterator) {
                String id = f.getFileName().toString().replaceFirst("\\.cfg$", "");
                try {
                    ProfileFile pf = ProfileFile.read(f);
                    out.add(new Info(id, pf.name.isBlank() ? id : pf.name, pf.type));
                } catch (IOException e) {
                    System.err.println("Cannot read " + f + ": " + e.getMessage());
                }
            }
        }
        return out;
    }

    public ProfileFile read(String id) throws IOException { return ProfileFile.read(store.file(id)); }

    public Path file(String id) { return store.file(id); }

    /** A file id from a display name: lower case letters, digits and dashes; made unique with a number if taken. */
    public String freeId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "profile";
        String id = base;
        for (int i = 2; Files.exists(store.file(id)); i++) id = base + "-" + i;
        return id;
    }

    /** Creates an empty profile of the given type ("keyboard" or "gamepad") and returns its id. */
    public String create(String name, String type) throws IOException {
        ProfileFile f = new ProfileFile();
        f.type = type;
        f.name = name;
        String id = freeId(name);
        f.write(store.file(id));
        return id;
    }

    /** Copies a profile under a new display name and returns the id of the copy. */
    public String duplicate(String id, String newName) throws IOException {
        ProfileFile f = read(id);
        f.name = newName;
        String nid = freeId(newName);
        f.write(store.file(nid));
        return nid;
    }

    /** The bundled version of a profile (same id), or null if there is none. */
    public ProfileFile bundled(String id) {
        if (!ProfileStore.BUNDLED.contains(id)) return null;
        try {
            Path tmp = Files.createTempFile("profile", ".cfg");
            try {
                Files.writeString(tmp, String.join("\n", bundledLines(id)));
                return ProfileFile.read(tmp);
            } finally { Files.deleteIfExists(tmp); }
        } catch (IOException e) { return null; }
    }

    private static List<String> bundledLines(String id) throws IOException {
        try (var in = ProfileStore.class.getResourceAsStream("/profiles/" + id + ".cfg")) {
            return List.of(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n"));
        }
    }
}
