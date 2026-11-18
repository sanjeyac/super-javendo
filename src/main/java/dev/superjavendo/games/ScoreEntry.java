package dev.superjavendo.games;

/** One line of a high-score table: the points and the name the player typed (trailing blanks removed). */
public record ScoreEntry(long score, String name) { }
