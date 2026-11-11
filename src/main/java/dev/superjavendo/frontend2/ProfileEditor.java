package dev.superjavendo.frontend2;

import dev.superjavendo.input.ProfileFile;
import dev.superjavendo.input.ProfileLibrary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The editable copy of one profile: changes stay here until {@link #save()}; {@link #dirty()} compares with what is on disk. */
final class ProfileEditor {
    /** The SNES buttons in the order of the assignment grid, then the two emulator actions. */
    static final List<String> BUTTONS = List.of("up", "down", "left", "right", "select", "start", "l", "r", "y", "x", "b", "a");
    static final List<String> ACTIONS = List.of("save_state", "load_state");

    private final ProfileLibrary library;
    private final String id;
    private ProfileFile working;
    private List<String> savedLines;

    ProfileEditor(ProfileLibrary library, String id) throws IOException {
        this.library = library;
        this.id = id;
        working = library.read(id);
        savedLines = working.toLines();
    }

    String id() { return id; }
    boolean keyboard() { return working.isKeyboard(); }
    String name() { return working.name; }
    void setName(String n) { working.name = n; }
    List<String> tokens(String button) { return working.of(button); }

    void add(String button, String token) { working.add(button, token); }
    void remove(String button, String token) { working.remove(button, token); }

    boolean dirty() { return !working.toLines().equals(savedLines); }

    /** Buttons (not actions) with no input. */
    List<String> unassigned() {
        List<String> out = new ArrayList<>();
        for (String b : BUTTONS) if (working.of(b).isEmpty()) out.add(b);
        return out;
    }

    /** Validates and writes the file. */
    void save() throws IOException {
        working.write(library.file(id));
        savedLines = working.toLines();
    }

    void discard() throws IOException {
        working = library.read(id);
        savedLines = working.toLines();
    }

    /** Replaces the bindings by the bundled ones (or clears them if the profile has no bundled version). Not saved until {@link #save()}. */
    void defaults() {
        ProfileFile b = library.bundled(id);
        for (String k : ProfileFile.BUTTONS) for (String t : new ArrayList<>(working.of(k))) working.remove(k, t);
        if (b != null) for (String k : ProfileFile.BUTTONS) for (String t : b.of(k)) working.add(k, t);
    }

    /** SNES buttons and actions that a token activates in this profile. */
    List<String> targetsOf(String token) {
        List<String> out = new ArrayList<>();
        for (String k : ProfileFile.BUTTONS) if (working.of(k).contains(token)) out.add(k);
        return out;
    }

    /** Message key describing what kind of input the token is. */
    static String kindKey(String token) {
        if (token.startsWith("KEY_")) return "tok.key";
        if (token.startsWith("BUTTON_")) return "tok.button";
        if (token.startsWith("AXIS_")) {
            try {
                int n = Integer.parseInt(token.substring(5, token.length() - 1));
                if (n == 6 || n == 7) return "tok.hat";
                if (n == 0 || n == 1) return "tok.leftstick";
                if (n == 3 || n == 4) return "tok.rightstick";
            } catch (NumberFormatException ignored) { }
            return "tok.axis";
        }
        return "tok.button";
    }

    /** Display form: {@code AXIS_7-} becomes {@code AXIS_7 −}. */
    static String pretty(String token) {
        if (token.startsWith("AXIS_") && (token.endsWith("+") || token.endsWith("-"))) return token.substring(0, token.length() - 1) + " " + (token.endsWith("+") ? "+" : "−");
        return token;
    }
}
