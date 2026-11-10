package dev.superjavendo.state;

import java.io.IOException;

/** A component whose complete emulation state can be written to and restored from a save state. */
public interface Stateful {
    void save(StateOut o) throws IOException;
    void load(StateIn i) throws IOException;
}
