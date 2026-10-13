package pingplanet;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The world as dots, with a marker per server colored by latency, a pulsing ring
 * on the server being tested, and a legend. Hover a marker for its details.
 */
final class MapPanel extends JPanel {

    /** A place on the map. {@code ms} is NaN for "not measured yet" and negative for "unreachable". */
    record Marker(Server server, double ms) {
    }

    private static final WorldMap WORLD = WorldMap.load();
    private static final boolean[][] DOTS = WORLD.dots();

    private final List<Marker> markers = new ArrayList<>();
    private Server active;
    private final Timer pulse = new Timer(40, e -> repaint());
    private final long started = System.nanoTime();

    MapPanel() {
        setOpaque(false);
        setPreferredSize(new Dimension(800, 330));
        setToolTipText(""); // turns tooltips on; the text comes from getToolTipText(MouseEvent)
    }

    /** Shows every server as "not measured yet". */
    void reset(List<Server> servers) {
        markers.clear();
        for (Server s : servers) if (s.located()) markers.add(new Marker(s, Double.NaN));
        active = null;
        pulse.stop();
        repaint();
    }

    void measured(Server server, double ms) {
        markers.removeIf(m -> m.server().equals(server));
        if (server.located()) markers.add(new Marker(server, ms));
        repaint();
    }

    /** Pulses this server's marker; null stops the pulse. */
    void active(Server server) {
        active = server;
        if (server != null && server.located()) pulse.start();
        else pulse.stop();
        repaint();
    }

    // ---- painting ----------------------------------------------------------

    private double spacing;
    private double left;
    private double top;

    private void layoutGrid() {
        int rows = DOTS.length;
        int cols = DOTS[0].length;
        spacing = Math.min((getWidth() - 20) / (double) cols, (getHeight() - 36) / (double) rows);
        left = (getWidth() - spacing * cols) / 2;
        top = 6;
    }

    private double[] point(Server s) {
        double[] d = WORLD.dot(s.lat(), s.lon());
        return new double[] {left + (d[1] + 0.5) * spacing, top + (d[0] + 0.5) * spacing};
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = GuiKit.smooth(g);
        layoutGrid();
        Color land = GuiKit.dark() ? new Color(148, 163, 184, 90) : new Color(71, 85, 105, 70);
        g2.setColor(land);
        double r = spacing * 0.34;
        for (int row = 0; row < DOTS.length; row++) {
            for (int col = 0; col < DOTS[row].length; col++) {
                if (!DOTS[row][col]) continue;
                double x = left + (col + 0.5) * spacing;
                double y = top + (row + 0.5) * spacing;
                g2.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            }
        }

        double size = Math.max(6, spacing * 1.5);
        for (Marker m : markers) {
            double[] p = point(m.server());
            Color c = color(m);
            if (!Double.isNaN(m.ms()) && m.ms() >= 0) { // soft glow behind measured markers
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                g2.fill(new Ellipse2D.Double(p[0] - size, p[1] - size, 2 * size, 2 * size));
            }
            g2.setColor(c);
            if (m.ms() < 0) {
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.draw(new Ellipse2D.Double(p[0] - size / 2, p[1] - size / 2, size, size));
            } else {
                g2.fill(new Ellipse2D.Double(p[0] - size / 2, p[1] - size / 2, size, size));
            }
        }

        if (active != null && active.located()) {
            double[] p = point(active);
            double phase = ((System.nanoTime() - started) / 1e9 % 1.2) / 1.2; // one ring every 1.2 s
            double ring = size * (0.8 + phase * 2.6);
            g2.setColor(new Color(GuiKit.SKY.getRed(), GuiKit.SKY.getGreen(), GuiKit.SKY.getBlue(), (int) (220 * (1 - phase))));
            g2.setStroke(new java.awt.BasicStroke(2.2f));
            g2.draw(new Ellipse2D.Double(p[0] - ring / 2, p[1] - ring / 2, ring, ring));
        }
        paintLegend(g2);
        g2.dispose();
    }

    private void paintLegend(Graphics2D g2) {
        Object[][] items = {{GuiKit.GREEN, "<30 ms"}, {GuiKit.LIME, "<80"}, {GuiKit.YELLOW, "<150"},
                {GuiKit.ORANGE, "<250"}, {GuiKit.RED, "250+"}, {GuiKit.muted(), "waiting"}};
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g2.getFontMetrics();
        int x = 12;
        int y = getHeight() - 10;
        for (Object[] item : items) {
            g2.setColor((Color) item[0]);
            g2.fill(new Ellipse2D.Double(x, y - 8, 8, 8));
            g2.setColor(GuiKit.muted());
            g2.drawString((String) item[1], x + 12, y);
            x += 12 + fm.stringWidth((String) item[1]) + 14;
        }
    }

    private static Color color(Marker m) {
        if (Double.isNaN(m.ms())) return GuiKit.dark() ? new Color(100, 116, 139) : new Color(148, 163, 184);
        if (m.ms() < 0) return GuiKit.RED;
        return GuiKit.latencyColor(m.ms());
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        layoutGrid();
        double best = Math.max(10, spacing * 2);
        Marker hit = null;
        for (Marker m : markers) {
            double[] p = point(m.server());
            double d = Math.hypot(p[0] - e.getX(), p[1] - e.getY());
            if (d < best) {
                best = d;
                hit = m;
            }
        }
        if (hit == null) return null;
        String state = Double.isNaN(hit.ms()) ? "not measured yet" : hit.ms() < 0 ? "unreachable" : Report.ms(hit.ms());
        return hit.server().label() + " · " + hit.server().provider() + " · " + state;
    }
}
