package dev.superjavendo.state;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Metadata stored at the start of a save state; readable without loading the state itself. */
public final class StateInfo {
    public final String gameTitle;
    /** Epoch milliseconds when the state was saved, or 0 for states from before this was recorded. */
    public final long savedAtMillis;
    /** PNG screenshot taken at the moment of saving; empty for old states. */
    public final byte[] screenshotPng;

    public StateInfo(String gameTitle, long savedAtMillis, byte[] screenshotPng) {
        this.gameTitle = gameTitle;
        this.savedAtMillis = savedAtMillis;
        this.screenshotPng = screenshotPng == null ? new byte[0] : screenshotPng;
    }

    public boolean hasScreenshot() { return screenshotPng.length > 0; }

    /** Decodes the screenshot, or returns null when there is none. */
    public BufferedImage screenshot() throws IOException {
        return hasScreenshot() ? ImageIO.read(new ByteArrayInputStream(screenshotPng)) : null;
    }
}
