package pingplanet;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;

/** Saves the window as a PNG, for sharing results or putting them in a README. */
final class Screenshot {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Screenshot() {
    }

    /** Paints {@code c} into an image, {@code scale} times its size (2 on a HiDPI screen keeps it sharp). */
    static BufferedImage capture(Component c, double scale) {
        int w = Math.max(1, (int) Math.round(c.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(c.getHeight() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(scale, scale);
        c.paint(g);
        g.dispose();
        return img;
    }

    /** The screen's scale factor for {@code c}: 1 on a normal screen, 2 on most HiDPI ones. */
    static double scale(Component c) {
        GraphicsConfiguration gc = c.getGraphicsConfiguration();
        return gc == null ? 1 : Math.max(1, gc.getDefaultTransform().getScaleX());
    }

    /** Saves {@code c} as "pingplanet-yyyyMMdd-HHmmss.png" in {@code folder}, and returns the file. */
    static Path save(Component c, Path folder, LocalDateTime now) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve("pingplanet-" + STAMP.format(now) + ".png");
        for (int n = 2; Files.exists(file); n++) { // two clicks in the same second
            file = folder.resolve("pingplanet-" + STAMP.format(now) + "-" + n + ".png");
        }
        ImageIO.write(capture(c, scale(c)), "png", file.toFile());
        return file;
    }

    /** ~/Pictures when it exists (most desktops have it), otherwise the home folder. */
    static Path defaultFolder() {
        Path home = Path.of(System.getProperty("user.home"));
        Path pictures = home.resolve("Pictures");
        return Files.isDirectory(pictures) ? pictures : home;
    }
}
