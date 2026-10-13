package pingplanet;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/** Colors, painting helpers and small components shared by the window's tabs. */
final class GuiKit {

    // Readable on both light and dark backgrounds (the terminal's neon colors are not).
    static final Color GREEN = new Color(0x22c55e);
    static final Color LIME = new Color(0x84cc16);
    static final Color YELLOW = new Color(0xeab308);
    static final Color ORANGE = new Color(0xf97316);
    static final Color RED = new Color(0xef4444);
    static final Color CYAN = new Color(0x06b6d4);
    static final Color SKY = new Color(0x0ea5e9);
    static final Color VIOLET = new Color(0x8b5cf6);
    static final Color PINK = new Color(0xec4899);
    static final Color[] BRAND = {CYAN, SKY, VIOLET, PINK};

    private GuiKit() {
    }

    static Color latencyColor(double ms) {
        return guiColor(Term.latencyColor(ms));
    }

    static Color speedColor(double mbps) {
        return guiColor(Term.speedColor(mbps));
    }

    /** The GUI version of a terminal palette color. */
    static Color guiColor(int term) {
        return switch (term) {
            case Term.GREEN -> GREEN;
            case Term.LIME -> LIME;
            case Term.YELLOW -> YELLOW;
            case Term.ORANGE -> ORANGE;
            case Term.RED -> RED;
            case Term.CYAN -> CYAN;
            default -> SKY;
        };
    }

    static Color muted() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    static boolean dark() {
        Color bg = UIManager.getColor("Panel.background");
        return bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
    }

    /** A slightly raised surface for cards. */
    static Color card() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return Color.WHITE;
        return dark() ? bg.brighter() : new Color(Math.max(0, bg.getRed() - 8), Math.max(0, bg.getGreen() - 8), Math.max(0, bg.getBlue() - 6));
    }

    static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    /** A rounded bar: a faint track, filled to {@code fraction} in {@code color}. */
    static void bar(Graphics2D g, int x, int y, int w, int h, double fraction, Color color) {
        double r = h;
        Color track = dark() ? new Color(255, 255, 255, 28) : new Color(0, 0, 0, 22);
        g.setColor(track);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r, r));
        double fill = Math.clamp(fraction, 0, 1) * w;
        if (fill <= 0) return;
        g.setPaint(new GradientPaint(x, 0, color.darker(), (float) (x + Math.max(fill, 1)), 0, color));
        g.fill(new RoundRectangle2D.Double(x, y, Math.max(fill, h), h, r, r));
    }

    /** Log scale from 1 Mbps to 1 Gbps, so 10 Mbps and 1 Gbps both read well. */
    static double logScale(double mbps) {
        return Math.log10(1 + Math.max(0, mbps)) / Math.log10(1 + 1000);
    }

    static Font bigFont(float size) {
        Font f = UIManager.getFont("defaultFont");
        if (f == null) f = new JLabel().getFont();
        return f.deriveFont(Font.BOLD, size);
    }

    static Font mono() {
        Font f = UIManager.getFont("monospaced.font");
        return f != null ? f : new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }

    static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100);
    }

    /** The app icon: a planet with a ring, in the brand colors. */
    static BufferedImage planet(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img.getGraphics());
        double s = size;
        g.setPaint(new GradientPaint(0, 0, CYAN, (float) s, (float) s, VIOLET));
        g.fill(new Ellipse2D.Double(s * 0.2, s * 0.2, s * 0.6, s * 0.6));
        g.setColor(PINK);
        g.setStroke(new BasicStroke((float) (s * 0.07), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.rotate(Math.toRadians(-20), s / 2, s / 2);
        g.draw(new Ellipse2D.Double(s * 0.04, s * 0.38, s * 0.92, s * 0.24));
        g.dispose();
        return img;
    }

    static JButton primary(String text) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "default");
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        return b;
    }

    /** A number spinner without thousands separators (a port is 7357, not 7.357). */
    static javax.swing.JSpinner spinner(int value, int min, int max) {
        javax.swing.JSpinner s = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(value, min, max, 1));
        s.setEditor(new javax.swing.JSpinner.NumberEditor(s, "#"));
        return s;
    }

    static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(muted());
        return l;
    }

    static JPanel row(Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        for (Component c : parts) p.add(c);
        return p;
    }

    // ---- connection picker -------------------------------------------------

    /**
     * Check boxes for the connections to test, all on by default, the default
     * route marked. With one connection or none there is nothing to choose.
     */
    static final class ConnectionPicker extends JPanel {

        private final List<Net.Source> sources;
        private final List<JCheckBox> boxes = new ArrayList<>();

        ConnectionPicker(List<Net.Source> sources, String defaultName) {
            super(new FlowLayout(FlowLayout.LEFT, 8, 0));
            setOpaque(false);
            this.sources = sources;
            add(caption("Connections"));
            if (sources.size() <= 1) {
                add(new JLabel(sources.isEmpty() ? "system default" : sources.getFirst().label()));
                return;
            }
            for (Net.Source s : sources) {
                JCheckBox box = new JCheckBox(s.name() + (s.name().equals(defaultName) ? " (default)" : ""), true);
                box.setToolTipText(s.address().getHostAddress() + " · " + s.displayName());
                boxes.add(box);
                add(box);
            }
        }

        /** The chosen connections; a single null means "let the system choose". */
        List<Net.Source> selected() {
            if (sources.size() <= 1) return java.util.Collections.singletonList(null);
            List<Net.Source> picked = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) picked.add(sources.get(i));
            return picked;
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            for (JCheckBox b : boxes) b.setEnabled(enabled);
        }
    }

    // ---- warnings ----------------------------------------------------------

    /** Yellow notes with commands to copy, stacked above the status bar. */
    static final class Warnings extends JPanel {

        Warnings() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        void clear() {
            removeAll();
            revalidate();
            repaint();
        }

        void add(String message, List<String> commands) {
            // the same note can come once per connection; show it once
            for (Component c : getComponents()) {
                if (message.equals(((JComponent) c).getClientProperty("message"))) return;
            }
            JPanel note = new JPanel(new BorderLayout(10, 6)) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = smooth(g);
                    g2.setColor(new Color(YELLOW.getRed(), YELLOW.getGreen(), YELLOW.getBlue(), dark() ? 40 : 55));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.setColor(YELLOW);
                    g2.fillRoundRect(0, 0, 4, getHeight(), 4, 4);
                    g2.dispose();
                }
            };
            note.putClientProperty("message", message);
            note.setOpaque(false);
            note.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 10));
            JTextArea text = new JTextArea("⚠  " + message);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setEditable(false);
            text.setOpaque(false);
            text.setFont(text.getFont().deriveFont(Font.BOLD));
            note.add(text, BorderLayout.NORTH);
            if (!commands.isEmpty()) {
                JTextArea code = new JTextArea(String.join("\n", commands));
                code.setFont(mono());
                code.setEditable(false);
                code.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                JButton copy = new JButton("Copy");
                copy.addActionListener(e -> {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code.getText()), null);
                    copy.setText("Copied");
                });
                JPanel commandsRow = new JPanel(new BorderLayout(8, 0));
                commandsRow.setOpaque(false);
                commandsRow.add(code, BorderLayout.CENTER);
                commandsRow.add(copy, BorderLayout.EAST);
                note.add(commandsRow, BorderLayout.CENTER);
            }
            if (getComponentCount() > 0) super.add(Box.createVerticalStrut(6));
            super.add(note);
            revalidate();
            repaint();
        }
    }

    // ---- status bar --------------------------------------------------------

    /** Overall progress for the whole run, with where we are and the time left. */
    static final class StatusBar extends JPanel {

        private final JProgressBar bar = new JProgressBar(0, 1000);
        private final JLabel text = new JLabel("Ready");

        StatusBar() {
            super(new BorderLayout(12, 0));
            setOpaque(false);
            bar.setStringPainted(true);
            bar.setString("");
            bar.setPreferredSize(new Dimension(320, 18));
            text.setForeground(muted());
            add(bar, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        void show(double fraction, String where) {
            bar.setValue((int) Math.round(fraction * 1000));
            bar.setString(percent(fraction));
            text.setText(where);
        }

        void idle(String message) {
            bar.setValue(0);
            bar.setString("");
            text.setText(message);
        }

        void done(String message) {
            bar.setValue(1000);
            bar.setString("100%");
            text.setText(message);
        }
    }
}
