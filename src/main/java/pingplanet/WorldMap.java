package pingplanet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The world drawn in braille characters (made by tools/make-map.py), with
 * markers placed on it by latitude and longitude.
 */
final class WorldMap {

    /** A point to draw: {@code rank} decides which marker wins when two share a cell (lower wins). */
    record Marker(double lat, double lon, String glyph, int color, double rank) {
    }

    static final int LAND = 67;

    private static final Pattern HEADER = Pattern.compile(
            "# cols=(\\d+) rows=(\\d+) lon_left=(\\S+) lat_top=(\\S+) deg_per_dot=(\\S+)");

    private final List<String> lines;
    private final int cols;
    private final double lonLeft;
    private final double latTop;
    private final double degPerDot;

    private WorldMap(List<String> lines, int cols, double lonLeft, double latTop, double degPerDot) {
        this.lines = lines;
        this.cols = cols;
        this.lonLeft = lonLeft;
        this.latTop = latTop;
        this.degPerDot = degPerDot;
    }

    static WorldMap load() {
        try (InputStream in = WorldMap.class.getResourceAsStream("/pingplanet/world.txt")) {
            if (in == null) throw new IllegalStateException("world.txt is missing from the jar");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Matcher m = HEADER.matcher(reader.readLine());
            if (!m.matches()) throw new IllegalStateException("bad world.txt header");
            List<String> lines = new ArrayList<>(reader.lines().toList());
            return new WorldMap(lines, Integer.parseInt(m.group(1)), Double.parseDouble(m.group(3)),
                    Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int width() {
        return cols;
    }

    /** Text cell [row, col] for a position, or null when it falls outside the map. */
    int[] cell(double lat, double lon) {
        int col = (int) Math.floor((lon - lonLeft) / degPerDot / 2);
        int row = (int) Math.floor((latTop - lat) / degPerDot / 4);
        if (row < 0 || row >= lines.size() || col < 0 || col >= cols) return null;
        return new int[] {row, col};
    }

    /** The map as styled lines, land in a muted color and markers on top. */
    List<String> render(Term t, List<Marker> markers) {
        Marker[][] grid = new Marker[lines.size()][cols];
        for (Marker m : markers) {
            int[] c = cell(m.lat(), m.lon());
            if (c == null) continue;
            Marker old = grid[c[0]][c[1]];
            if (old == null || m.rank() < old.rank()) grid[c[0]][c[1]] = m;
        }
        List<String> out = new ArrayList<>();
        for (int row = 0; row < lines.size(); row++) {
            String line = lines.get(row);
            StringBuilder sb = new StringBuilder();
            StringBuilder land = new StringBuilder();
            for (int col = 0; col < cols; col++) {
                Marker m = grid[row][col];
                if (m == null) {
                    land.append(col < line.length() ? line.charAt(col) : '⠀');
                    continue;
                }
                sb.append(t.fg(LAND, land.toString()));
                land.setLength(0);
                sb.append(t.bold(t.fg(m.color(), m.glyph())));
            }
            sb.append(t.fg(LAND, land.toString()));
            out.add(sb.toString());
        }
        return out;
    }
}
