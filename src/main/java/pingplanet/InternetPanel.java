package pingplanet;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/** The Internet tab: settings, the world map, live results per connection, ranking and comparison. */
final class InternetPanel extends JPanel {

    private final JTextField countries = new JTextField(12);
    private final JSpinner seconds = GuiKit.spinner(5, 1, 60);
    private final JSpinner streams = GuiKit.spinner(4, 1, 32);
    private final JCheckBox latencyOnly = new JCheckBox("Latency only");
    private final GuiKit.ConnectionPicker picker;
    private final JButton start = GuiKit.primary("Start test");
    private final MapPanel map = new MapPanel();
    private final JTabbedPane results = new JTabbedPane();
    private final GuiKit.Warnings warnings = new GuiKit.Warnings();
    private final GuiKit.StatusBar status = new GuiKit.StatusBar();
    private final List<Server> allServers;
    private Thread worker;

    InternetPanel(List<Server> allServers, List<Net.Source> sources, String defaultName) {
        super(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        this.allServers = allServers;
        picker = new GuiKit.ConnectionPicker(sources, defaultName);

        countries.putClientProperty("JTextField.placeholderText", "all countries");
        countries.setToolTipText("Country codes, e.g. BR,US,JP. Empty tests every server.");
        JPanel controls = new JPanel(new BorderLayout());
        controls.setOpaque(false);
        JPanel settings = GuiKit.row(GuiKit.caption("Countries"), countries, GuiKit.caption("Seconds"), seconds,
                GuiKit.caption("Streams"), streams, latencyOnly);
        controls.add(settings, BorderLayout.NORTH);
        controls.add(picker, BorderLayout.SOUTH);
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(controls, BorderLayout.CENTER);
        start.setPreferredSize(new Dimension(130, 36));
        top.add(start, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        JLabel empty = GuiKit.caption("Press Start test to measure latency and download speed to servers around the world.");
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        results.addTab("Results", empty);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, map, results);
        split.setResizeWeight(0.42);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(warnings, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        map.reset(allServers);
        start.addActionListener(e -> {
            if (worker != null) stop();
            else begin();
        });
    }

    // ---- running -----------------------------------------------------------

    private void begin() {
        Set<String> codes = new LinkedHashSet<>();
        Arrays.stream(countries.getText().split("[,\\s]+")).map(c -> c.strip().toUpperCase(Locale.ROOT))
                .filter(c -> !c.isEmpty()).forEach(codes::add);
        List<Server> servers = Server.filter(allServers, codes);
        if (servers.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No servers match " + String.join(",", codes) + ".", "PingPlanet",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Net.Source> sources = picker.selected();
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Choose at least one connection.", "PingPlanet", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Engine.WorldPlan plan = new Engine.WorldPlan(servers, sources, !latencyOnly.isSelected(),
                (Integer) seconds.getValue(), (Integer) streams.getValue());

        results.removeAll();
        warnings.clear();
        map.reset(servers);
        setRunning(true);
        GuiView view = new GuiView(plan);
        worker = Thread.ofPlatform().daemon().name("pingplanet-internet").start(() -> {
            String outcome;
            try {
                List<Report.Run> runs = Engine.world(plan, view);
                outcome = summary(runs);
            } catch (CancellationException e) {
                outcome = "Stopped";
            } catch (RuntimeException e) {
                outcome = "Failed: " + e.getMessage();
            }
            String message = outcome;
            SwingUtilities.invokeLater(() -> finished(message));
        });
    }

    private void stop() {
        start.setEnabled(false);
        start.setText("Stopping…");
        worker.interrupt();
    }

    private void finished(String message) {
        worker = null;
        map.active(null);
        setRunning(false);
        if (message.equals("Stopped") || message.startsWith("Failed")) status.idle(message);
        else status.done(message);
    }

    private void setRunning(boolean running) {
        start.setEnabled(true);
        start.setText(running ? "Stop" : "Start test");
        start.putClientProperty("JButton.buttonType", running ? null : "default");
        for (Component c : new Component[] {countries, seconds, streams, latencyOnly, picker}) c.setEnabled(!running);
    }

    private static String summary(List<Report.Run> runs) {
        List<Report.ServerResult> all = runs.stream().flatMap(r -> r.results().stream()).toList();
        StringBuilder sb = new StringBuilder("Done");
        Report.lowestLatency(all).ifPresent(r -> sb.append(" · lowest latency ").append(r.server().label())
                .append(" ").append(Report.ms(r.latency().medianMs())));
        Report.fastest(all).ifPresent(r -> sb.append(" · fastest ").append(r.server().label())
                .append(" ").append(Report.mbps(r.download().mbps())));
        return sb.toString();
    }

    // ---- the View, on the Swing thread -------------------------------------

    /** Engine events, moved onto the Swing thread. Also keeps the overall progress, like the terminal. */
    private final class GuiView extends ViewAdapter {

        private final Engine.WorldPlan plan;
        private final Overall overall;
        private final double latencyUnits = 3;
        private final double downloadUnits;
        private int connection;
        private int server;
        private ServerTableModel table;
        private RankingPanel ranking;
        private String phase;

        GuiView(Engine.WorldPlan plan) {
            this.plan = plan;
            downloadUnits = plan.downloads() ? plan.seconds() + 1 : 0;
            overall = new Overall(plan.sources().size() * (latencyUnits + plan.servers().size() * downloadUnits), System.nanoTime());
        }

        private void ui(Runnable r) {
            SwingUtilities.invokeLater(r);
        }

        private void showStatus() {
            StringBuilder where = new StringBuilder();
            if (plan.sources().size() > 1) where.append("Connection ").append(connection).append('/').append(plan.sources().size());
            if (server > 0) where.append(where.isEmpty() ? "" : " · ").append("server ").append(server).append('/').append(plan.servers().size());
            if (phase != null) where.append(where.isEmpty() ? "" : " · ").append(phase);
            overall.remainingNanos(System.nanoTime()).ifPresent(n -> where.append(" · ").append(Overall.remainingText(n)));
            status.show(overall.fraction(), where.toString());
        }

        @Override
        public void worldStart(int servers, boolean downloads, int secs, int strms, Net.Source source) {
            ui(() -> {
                connection++;
                server = 0;
                phase = "measuring latency";
                table = new ServerTableModel(downloads);
                ranking = new RankingPanel();
                if (connection > 1) map.reset(plan.servers());
                JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ServerTableModel.view(table), ranking);
                split.setResizeWeight(0.68);
                split.setBorder(null);
                results.addTab(source == null ? "Results" : source.name() + "  " + source.address().getHostAddress(), split);
                results.setSelectedIndex(results.getTabCount() - 1);
                showStatus();
            });
        }

        @Override
        public Progress latencyProgress(int servers) {
            return (done, nanos) -> ui(() -> {
                overall.within(latencyUnits * done / servers);
                showStatus();
            });
        }

        @Override
        public void latencyDone(int servers, long nanos) {
            ui(() -> {
                overall.step(latencyUnits);
                phase = null;
                showStatus();
            });
        }

        @Override
        public Progress downloading(Server s, Latency latency) {
            ui(() -> {
                server++;
                table.live(s, latency, Double.NaN);
                map.active(s);
                showStatus();
            });
            return (bytes, nanos) -> ui(() -> {
                overall.within(Math.min(downloadUnits, nanos / 1e9));
                table.live(s, latency, nanos == 0 ? Double.NaN : new Throughput(bytes, nanos).mbps());
                showStatus();
            });
        }

        @Override
        public void row(Report.ServerResult result) {
            ui(() -> {
                if (plan.downloads()) overall.step(downloadUnits);
                table.done(result);
                ranking.add(result);
                Latency l = result.latency();
                map.measured(result.server(), l != null && l.reachable() ? l.medianMs() : -1);
                map.active(null);
                showStatus();
            });
        }

        @Override
        public void compare(List<Report.Run> runs) {
            ui(() -> {
                results.addTab("Comparison", new ComparisonPanel(runs));
                results.setSelectedIndex(results.getTabCount() - 1);
            });
        }

        @Override
        public void warning(String message, List<String> commands) {
            ui(() -> warnings.add(message, commands));
        }
    }

    // ---- results table -----------------------------------------------------

    /** One row per server, added when its test starts and updated live while it downloads. */
    static final class ServerTableModel extends AbstractTableModel {

        static final class Row {
            Server server;
            Latency latency;
            double liveMbps = Double.NaN;
            boolean testing;
            Report.ServerResult result;
        }

        private final boolean downloads;
        private final List<Row> rows = new ArrayList<>();

        ServerTableModel(boolean downloads) {
            this.downloads = downloads;
        }

        private Row rowFor(Server s) {
            for (Row r : rows) if (r.server.equals(s)) return r;
            Row r = new Row();
            r.server = s;
            rows.add(r);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            return r;
        }

        void live(Server s, Latency latency, double mbps) {
            Row r = rowFor(s);
            r.latency = latency;
            r.testing = true;
            r.liveMbps = mbps;
            int i = rows.indexOf(r);
            fireTableRowsUpdated(i, i);
        }

        void done(Report.ServerResult result) {
            Row r = rowFor(result.server());
            r.latency = result.latency();
            r.testing = false;
            r.result = result;
            int i = rows.indexOf(r);
            fireTableRowsUpdated(i, i);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return downloads ? 6 : 5;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"", "Location", "Provider", "Latency", "Jitter", "Download"}[c];
        }

        @Override
        public Object getValueAt(int row, int col) {
            return rows.get(row);
        }

        static JScrollPane view(ServerTableModel model) {
            JTable table = new JTable(model);
            table.setRowHeight(28);
            table.setShowHorizontalLines(true);
            table.setFillsViewportHeight(true);
            table.setDefaultRenderer(Object.class, new CellRenderer());
            int[] widths = {48, 230, 86, 110, 74, 250};
            for (int i = 0; i < model.getColumnCount(); i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            return scroll;
        }
    }

    /** Paints every column of the results table. */
    static final class CellRenderer extends DefaultTableCellRenderer {

        private ServerTableModel.Row row;
        private int column;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(table, "", selected, false, r, c);
            row = (ServerTableModel.Row) value;
            column = c;
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            // set every time: the renderer is shared by all cells
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            if (!selected) {
                Color base = table.getBackground();
                setBackground(row.testing ? blend(base, GuiKit.SKY, 0.14) : base);
            }
            Server s = row.server;
            switch (c) {
                case 0 -> {
                    setText(s.code());
                    setFont(getFont().deriveFont(Font.BOLD));
                    setForeground(GuiKit.CYAN);
                }
                case 1 -> setText(s.label());
                case 2 -> {
                    setText(s.provider());
                    setForeground(GuiKit.muted());
                }
                case 4 -> {
                    setText(row.latency != null && row.latency.reachable() ? Report.ms(row.latency.jitterMs()) : "—");
                    setForeground(GuiKit.muted());
                    setHorizontalAlignment(RIGHT);
                    return this;
                }
                default -> setText("");
            }
            setHorizontalAlignment(LEFT);
            return this;
        }

        private static Color blend(Color a, Color b, double t) {
            return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (column != 3 && column != 5) return;
            Graphics2D g2 = GuiKit.smooth(g);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            if (column == 3) paintLatency(g2, fm, baseline);
            else paintDownload(g2, fm, baseline);
            g2.dispose();
        }

        private void paintLatency(Graphics2D g2, FontMetrics fm, int baseline) {
            Latency l = row.latency;
            boolean up = l != null && l.reachable();
            int bars = up ? Term.latencyBars(l.medianMs()) : 0;
            Color color = up ? GuiKit.latencyColor(l.medianMs()) : GuiKit.muted();
            for (int i = 0; i < 4; i++) { // signal bars, like a phone
                int h = 4 + i * 3;
                g2.setColor(i < bars ? color : new Color(128, 128, 128, 60));
                g2.fillRoundRect(8 + i * 5, baseline - h + 1, 3, h, 2, 2);
            }
            String text = l == null ? "" : up ? Report.ms(l.medianMs()) : "—";
            g2.setColor(color);
            g2.drawString(text, getWidth() - 8 - fm.stringWidth(text), baseline);
        }

        private void paintDownload(Graphics2D g2, FontMetrics fm, int baseline) {
            Report.ServerResult result = row.result;
            String text;
            Color color;
            double fraction;
            if (row.testing) {
                if (Double.isNaN(row.liveMbps)) {
                    g2.setColor(GuiKit.muted());
                    g2.drawString("connecting…", 8, baseline);
                    return;
                }
                text = "▸ " + Report.mbps(row.liveMbps);
                color = GuiKit.SKY;
                fraction = GuiKit.logScale(row.liveMbps);
            } else if (result != null && result.download() != null) {
                double mbps = result.download().mbps();
                text = Report.mbps(mbps);
                color = GuiKit.speedColor(mbps);
                fraction = GuiKit.logScale(mbps);
            } else {
                g2.setColor(GuiKit.RED);
                g2.drawString(result == null ? "" : "✖ " + (result.error() == null ? "failed" : result.error()), 8, baseline);
                return;
            }
            int textWidth = 90;
            GuiKit.bar(g2, 8, getHeight() / 2 - 4, getWidth() - 24 - textWidth, 8, fraction, color);
            g2.setFont(getFont().deriveFont(Font.BOLD));
            g2.setColor(color);
            g2.drawString(text, getWidth() - 8 - g2.getFontMetrics().stringWidth(text), baseline);
        }
    }

    // ---- ranking -----------------------------------------------------------

    /** Servers sorted by download speed, as bars scaled to the fastest. */
    static final class RankingPanel extends JPanel {

        private final List<Report.ServerResult> results = new ArrayList<>();

        RankingPanel() {
            setPreferredSize(new Dimension(320, 200));
            setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        }

        void add(Report.ServerResult r) {
            if (r.download() == null) return;
            results.add(r);
            results.sort(Comparator.comparingDouble((Report.ServerResult x) -> x.download().mbps()).reversed());
            setPreferredSize(new Dimension(320, 40 + results.size() * 22));
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = GuiKit.smooth(g);
            g2.setFont(getFont().deriveFont(Font.BOLD));
            g2.setColor(getForeground());
            g2.drawString("Download ranking", 12, 22);
            if (results.isEmpty()) {
                g2.setFont(getFont());
                g2.setColor(GuiKit.muted());
                g2.drawString("Fills in as servers finish.", 12, 46);
                g2.dispose();
                return;
            }
            g2.setFont(getFont().deriveFont(11.5f));
            FontMetrics fm = g2.getFontMetrics();
            double max = results.getFirst().download().mbps();
            int labelWidth = Math.min(130, getWidth() / 3);
            for (int i = 0; i < results.size(); i++) {
                Report.ServerResult r = results.get(i);
                int y = 40 + i * 22;
                double mbps = r.download().mbps();
                g2.setColor(getForeground());
                String label = r.server().city().isEmpty() ? r.server().country() : r.server().city();
                while (fm.stringWidth(label) > labelWidth - 6 && label.length() > 3) label = label.substring(0, label.length() - 2) + "…";
                g2.drawString(label, 12, y + 12);
                String value = Report.mbps(mbps);
                int valueWidth = 76;
                GuiKit.bar(g2, 12 + labelWidth, y + 3, getWidth() - 24 - labelWidth - valueWidth, 10,
                        max > 0 ? mbps / max : 0, GuiKit.speedColor(mbps));
                g2.setColor(GuiKit.speedColor(mbps));
                g2.drawString(value, getWidth() - 12 - fm.stringWidth(value), y + 12);
            }
            g2.dispose();
        }
    }

    // ---- comparison --------------------------------------------------------

    /** One row per connection, with a verdict underneath. */
    static final class ComparisonPanel extends JPanel {

        ComparisonPanel(List<Report.Run> runs) {
            super(new BorderLayout(0, 10));
            setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            double max = runs.stream().mapToDouble(Report.Run::medianDownload).filter(d -> !Double.isNaN(d)).max().orElse(0);
            AbstractTableModel model = new AbstractTableModel() {
                final String[] names = {"Interface", "Address", "Reached", "Best latency", "Median latency", "Median download"};

                @Override
                public int getRowCount() {
                    return runs.size();
                }

                @Override
                public int getColumnCount() {
                    return names.length;
                }

                @Override
                public String getColumnName(int c) {
                    return names[c];
                }

                @Override
                public Object getValueAt(int r, int c) {
                    Report.Run run = runs.get(r);
                    return switch (c) {
                        case 0 -> run.source().name();
                        case 1 -> run.source().address().getHostAddress();
                        case 2 -> run.reached() + "/" + run.results().size();
                        case 3 -> run.bestLatency();
                        case 4 -> run.medianLatency();
                        default -> run.medianDownload();
                    };
                }
            };
            JTable table = new JTable(model);
            table.setRowHeight(30);
            table.setDefaultRenderer(Object.class, comparisonRenderer(max));
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(600, 40 + runs.size() * 30));
            add(scroll, BorderLayout.NORTH);

            List<Report.Run> measured = runs.stream().filter(r -> !Double.isNaN(r.medianDownload())).toList();
            if (measured.size() >= 2) {
                Report.Run best = measured.stream().max(Comparator.comparingDouble(Report.Run::medianDownload)).orElseThrow();
                Report.Run worst = measured.stream().min(Comparator.comparingDouble(Report.Run::medianDownload)).orElseThrow();
                if (best != worst) {
                    JLabel verdict = new JLabel("★  " + Advice.speedVerdict(best.source().name(), best.medianDownload(),
                            worst.source().name(), worst.medianDownload()));
                    verdict.setFont(GuiKit.bigFont(15f));
                    add(verdict, BorderLayout.CENTER);
                }
            }
            add(GuiKit.caption("Best latency is to the nearest server: the part the connection itself adds."), BorderLayout.SOUTH);
        }

        private static TableCellRenderer comparisonRenderer(double max) {
            return new DefaultTableCellRenderer() {
                private double value = Double.NaN;
                private int column;

                @Override
                public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                    column = c;
                    value = v instanceof Double d ? d : Double.NaN;
                    String text = v instanceof Double d ? (Double.isNaN(d) ? "—" : c == 5 ? "" : Report.ms(d)) : String.valueOf(v);
                    super.getTableCellRendererComponent(t, text, sel, false, r, c);
                    setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                    setFont(c == 0 ? getFont().deriveFont(Font.BOLD) : getFont());
                    setForeground(c == 0 ? GuiKit.CYAN : (c == 3 || c == 4) && !Double.isNaN(value) ? GuiKit.latencyColor(value)
                            : c == 1 ? GuiKit.muted() : t.getForeground());
                    return this;
                }

                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (column != 5 || Double.isNaN(value)) return;
                    Graphics2D g2 = GuiKit.smooth(g);
                    Color color = GuiKit.speedColor(value);
                    GuiKit.bar(g2, 8, getHeight() / 2 - 4, getWidth() - 110, 8, max > 0 ? value / max : 0, color);
                    g2.setFont(getFont().deriveFont(Font.BOLD));
                    g2.setColor(color);
                    String text = Report.mbps(value);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(text, getWidth() - 8 - fm.stringWidth(text), (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            };
        }
    }
}
