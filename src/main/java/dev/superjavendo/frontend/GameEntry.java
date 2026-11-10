package dev.superjavendo.frontend;

import java.nio.file.Path;

/** One game in the library. */
public record GameEntry(Path path, String title) {
    @Override public String toString() { return title; }
}
