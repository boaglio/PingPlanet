package pingplanet;

import java.io.Console;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

/**
 * What the terminal can show, and helpers to style text with ANSI 256-color codes.
 * Every style method returns the text unchanged when colors are off.
 */
final class Term {

    // 256-color palette indexes
    static final int GREEN = 46;
    static final int LIME = 154;
    static final int YELLOW = 220;
    static final int ORANGE = 208;
    static final int RED = 196;
    static final int CYAN = 51;
    static final int SKY = 39;
    static final int VIOLET = 141;
    static final int GREY = 244;
    static final int DARK = 238;
    static final int WHITE = 255;

    /** Brand gradient, used for the banner and headings. */
    static final int[] BRAND = {51, 45, 39, 33, 27, 63, 99, 135, 171, 207};
    /** Slow to fast, used along speed bars. */
    static final int[] SPEED = {196, 202, 208, 214, 220, 226, 190, 154, 118, 82, 46, 48, 50, 51};

    static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] EIGHTHS = {"", "▏", "▎", "▍", "▌", "▋", "▊", "▉"};
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    /** Live, animated, Unicode output. When false, PingPlanet prints plain text. */
    final boolean fancy;
    final boolean color;

    Term(boolean fancy, boolean color) {
        this.fancy = fancy;
        this.color = fancy && color;
    }

    static Term plain() {
        return new Term(false, false);
    }

    /**
     * Fancy output needs an interactive terminal that takes UTF-8. Colors also
     * need the terminal to understand ANSI codes and the user not to opt out
     * (https://no-color.org).
     */
    static Term detect(PrintStream out, boolean noColor) {
        boolean interactive = interactive();
        String encoding = System.getProperty("stdout.encoding", Charset.defaultCharset().name());
        boolean utf8 = encoding.toUpperCase().replace("-", "").contains("UTF8");
        boolean ansi = !"dumb".equals(System.getenv("TERM"));
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            // The classic console only understands ANSI codes when the app turns them on, which Java does not.
            ansi = System.getenv("WT_SESSION") != null || System.getenv("TERM_PROGRAM") != null
                    || System.getenv("TERM") != null || "ON".equalsIgnoreCase(System.getenv("ConEmuANSI"));
        }
        boolean colorOk = ansi && !noColor && (System.getenv("NO_COLOR") == null || System.getenv("NO_COLOR").isEmpty());
        return new Term(interactive && utf8 && ansi, colorOk);
    }

    private static boolean interactive() {
        Console console = System.console();
        if (console == null) return false;
        try {
            // Java 22+ returns a console even when output is redirected; isTerminal() tells them apart.
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true; // Java 21: a console only exists when attached to a terminal
        }
    }

    /** Terminal width in characters: $COLUMNS, then stty, then a safe guess. */
    static int columns() {
        try {
            String env = System.getenv("COLUMNS");
            if (env != null) return Integer.parseInt(env.strip());
        } catch (NumberFormatException ignored) {
            // fall through
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) return 120; // Windows Terminal's default
        try {
            Process p = new ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/tty")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String[] size = new String(p.getInputStream().readAllBytes()).strip().split("\\s+");
            if (p.waitFor() == 0 && size.length == 2) return Integer.parseInt(size[1]);
        } catch (Exception ignored) {
            // no stty or no tty
        }
        return 100;
    }

    // ---- styles ------------------------------------------------------------

    String fg(int color, String text) {
        return this.color ? "\u001B[38;5;" + color + "m" + text + "\u001B[39m" : text;
    }

    String bold(String text) {
        return color ? "\u001B[1m" + text + "\u001B[22m" : text;
    }

    String dim(String text) {
        return fg(GREY, text);
    }

    String faint(String text) {
        return fg(DARK, text);
    }

    /** Colors each character along a palette, left to right. */
    String gradient(String text, int[] palette) {
        if (!color || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                sb.append(c);
                continue;
            }
            int p = n == 1 ? 0 : i * (palette.length - 1) / (n - 1);
            sb.append("\u001B[38;5;").append(palette[p]).append('m').append(c);
        }
        return sb.append("\u001B[39m").toString();
    }

    /**
     * A horizontal bar {@code width} cells wide, filled to {@code fraction} with
     * 1/8-cell precision. Filled cells follow {@code palette} by their position.
     */
    String bar(double fraction, int width, int[] palette) {
        fraction = Math.clamp(fraction, 0, 1);
        int eighths = (int) Math.round(fraction * width * 8);
        int full = eighths / 8;
        String partial = EIGHTHS[eighths % 8];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < full; i++) {
            sb.append(fg(palette[i * (palette.length - 1) / Math.max(1, width - 1)], "█"));
        }
        if (!partial.isEmpty()) sb.append(fg(palette[full * (palette.length - 1) / Math.max(1, width - 1)], partial));
        int used = full + (partial.isEmpty() ? 0 : 1);
        sb.append(faint("─".repeat(width - used)));
        return sb.toString();
    }

    // ---- layout ------------------------------------------------------------

    static String strip(String styled) {
        return ANSI.matcher(styled).replaceAll("");
    }

    static int width(String styled) {
        return strip(styled).length();
    }

    static String padRight(String styled, int width) {
        int w = width(styled);
        return w >= width ? styled : styled + " ".repeat(width - w);
    }

    static String padLeft(String styled, int width) {
        int w = width(styled);
        return w >= width ? styled : " ".repeat(width - w) + styled;
    }

    static String cut(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "…";
    }

    /** Moves to the start of the line so the next print overwrites it. */
    static String rewind(int previousWidth) {
        return "\r" + " ".repeat(previousWidth) + "\r";
    }

    // ---- ratings -----------------------------------------------------------

    static int latencyColor(double ms) {
        if (ms < 30) return GREEN;
        if (ms < 80) return LIME;
        if (ms < 150) return YELLOW;
        if (ms < 250) return ORANGE;
        return RED;
    }

    /** 0 to 4 signal bars, like a phone. */
    static int latencyBars(double ms) {
        if (ms < 30) return 4;
        if (ms < 80) return 3;
        if (ms < 150) return 2;
        if (ms < 250) return 1;
        return 0;
    }

    String signal(double ms) {
        int bars = latencyBars(ms);
        String[] glyphs = {"▂", "▄", "▆", "█"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(i < bars ? fg(latencyColor(ms), glyphs[i]) : faint(glyphs[i]));
        }
        return sb.toString();
    }

    static int speedColor(double mbps) {
        if (mbps < 10) return RED;
        if (mbps < 50) return ORANGE;
        if (mbps < 100) return YELLOW;
        if (mbps < 300) return LIME;
        if (mbps < 1000) return GREEN;
        return CYAN;
    }
}
