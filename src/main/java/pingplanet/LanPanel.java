package pingplanet;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.geom.Arc2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/** The LAN tab: test against another machine running PingPlanet's server, with live gauges. */
final class LanPanel extends JPanel {

    private final JTextField host = new JTextField(16);
    private final JSpinner port = GuiKit.spinner(Lan.DEFAULT_PORT, 1, 65535);
    private final JSpinner seconds = GuiKit.spinner(5, 1, 60);
    private final JSpinner streams = GuiKit.spinner(4, 1, 32);
    private final GuiKit.ConnectionPicker picker;
    private final JButton start = GuiKit.primary("Start test");
    private final Gauge latency = new Gauge("Latency");
    private final Gauge download = new Gauge("Download");
    private final Gauge upload = new Gauge("Upload");
    private final DefaultTableModel table = new DefaultTableModel(
            new Object[] {"Connection", "Address", "Latency", "Download", "Upload", "Note"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final GuiKit.Warnings warnings = new GuiKit.Warnings();
    private final GuiKit.StatusBar status = new GuiKit.StatusBar();
    private Thread worker;

    LanPanel(List<Net.Source> sources, String defaultName) {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        picker = new GuiKit.ConnectionPicker(sources, defaultName);

        host.putClientProperty("JTextField.placeholderText", "IP of the machine running the server");
        JPanel controls = new JPanel(new BorderLayout());
        controls.setOpaque(false);
        controls.add(GuiKit.row(GuiKit.caption("Host"), host, GuiKit.caption("Port"), port, GuiKit.caption("Seconds"), seconds,
                GuiKit.caption("Streams"), streams), BorderLayout.NORTH);
        controls.add(picker, BorderLayout.SOUTH);
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(controls, BorderLayout.CENTER);
        start.setPreferredSize(new Dimension(130, 36));
        top.add(start, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        JPanel gauges = new JPanel(new GridLayout(1, 3, 14, 0));
        gauges.setOpaque(false);
        gauges.add(latency);
        gauges.add(download);
        gauges.add(upload);
        JTable results = new JTable(table);
        results.setRowHeight(28);
        JScrollPane scroll = new JScrollPane(results);
        gauges.setPreferredSize(new Dimension(900, 250));
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(gauges, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(warnings, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        status.idle("Start the server on the other machine (Server tab, or \"pingplanet serve\"), then enter its address here.");

        start.addActionListener(e -> {
            if (worker != null) {
                start.setEnabled(false);
                start.setText("Stopping…");
                worker.interrupt();
            } else {
                begin();
            }
        });
        host.addActionListener(e -> {
            if (worker == null) begin();
        });
    }

    private void begin() {
        String target = host.getText().strip();
        if (target.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter the address of the machine running the PingPlanet server.",
                    "PingPlanet", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Net.Source> sources = picker.selected();
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Choose at least one connection.", "PingPlanet", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Engine.LanPlan plan = new Engine.LanPlan(target, (Integer) port.getValue(), sources,
                (Integer) seconds.getValue(), (Integer) streams.getValue());
        table.setRowCount(0);
        warnings.clear();
        for (Gauge g : new Gauge[] {latency, download, upload}) g.clear();
        setRunning(true);
        GuiView view = new GuiView(plan);
        worker = Thread.ofPlatform().daemon().name("pingplanet-lan").start(() -> {
            String outcome;
            try {
                List<Report.LanRun> runs = Engine.lan(plan, view);
                outcome = runs.stream().anyMatch(r -> r.result() != null) ? "Done" : "Failed: " + runs.getFirst().error();
            } catch (CancellationException e) {
                outcome = "Stopped";
            } catch (RuntimeException e) {
                outcome = "Failed: " + e.getMessage();
            }
            String message = outcome;
            SwingUtilities.invokeLater(() -> {
                worker = null;
                setRunning(false);
                if (message.equals("Done")) status.done(message);
                else status.idle(message);
            });
        });
    }

    private void setRunning(boolean running) {
        start.setEnabled(true);
        start.setText(running ? "Stop" : "Start test");
        start.putClientProperty("JButton.buttonType", running ? null : "default");
        for (Component c : new Component[] {host, port, seconds, streams, picker}) c.setEnabled(!running);
    }

    /** Engine events on the Swing thread, with the same overall progress weights as the terminal. */
    private final class GuiView extends ViewAdapter {

        private final Engine.LanPlan plan;
        private final Overall overall;
        private int connection;
        private double connectionStart;
        private Net.Source source;
        private Latency lastLatency;
        private Throughput lastDownload;
        private String phase;

        GuiView(Engine.LanPlan plan) {
            this.plan = plan;
            overall = new Overall(plan.sources().size() * (1 + 2.0 * plan.seconds()), System.nanoTime());
        }

        private void ui(Runnable r) {
            SwingUtilities.invokeLater(r);
        }

        private void showStatus() {
            StringBuilder where = new StringBuilder();
            if (plan.sources().size() > 1) where.append("Connection ").append(connection).append('/').append(plan.sources().size());
            if (phase != null) where.append(where.isEmpty() ? "" : " · ").append(phase);
            overall.remainingNanos(System.nanoTime()).ifPresent(n -> where.append(" · ").append(Overall.remainingText(n)));
            status.show(overall.fraction(), where.toString());
        }

        @Override
        public void lanStart(String h, int p, Net.Source src) {
            ui(() -> {
                connection++;
                connectionStart = overall.doneUnits();
                source = src;
                for (Gauge g : new Gauge[] {latency, download, upload}) g.clear();
                phase = "latency";
                showStatus();
            });
        }

        @Override
        public Progress lanLatencyProgress(int samples) {
            return (done, nanos) -> ui(() -> {
                overall.within((double) done / samples);
                latency.working((double) done / samples, done + "/" + samples + " pings");
                showStatus();
            });
        }

        @Override
        public void lanLatency(Latency l) {
            ui(() -> {
                overall.step(1);
                lastLatency = l;
                double ms = l.medianMs();
                latency.show(Report.ms(ms), Term.latencyBars(ms) / 4.0, GuiKit.latencyColor(ms),
                        "jitter " + Report.ms(l.jitterMs()) + " · loss " + Math.round(l.lossPercent()) + "%");
            });
        }

        @Override
        public Progress lanTransferProgress(String label, int secs) {
            Gauge gauge = label.equals("Download") ? download : upload;
            return (bytes, nanos) -> ui(() -> {
                overall.within(Math.min(secs, nanos / 1e9));
                phase = label.toLowerCase();
                double mbps = new Throughput(bytes, nanos).mbps();
                double tier = FancyView.tier(mbps);
                gauge.live(Report.mbps(mbps), mbps / tier, "of " + linkName(tier));
                showStatus();
            });
        }

        @Override
        public void lanTransfer(String label, Throughput t) {
            ui(() -> {
                overall.step(plan.seconds());
                double mbps = t.mbps();
                double tier = FancyView.tier(mbps);
                (label.equals("Download") ? download : upload).show(Report.mbps(mbps), mbps / tier, GuiKit.speedColor(mbps),
                        "of " + linkName(tier));
                if (label.equals("Download")) {
                    lastDownload = t;
                } else {
                    table.addRow(new Object[] {name(), address(), Report.ms(lastLatency.medianMs()),
                            Report.mbps(lastDownload.mbps()), Report.mbps(mbps), ""});
                }
            });
        }

        @Override
        public void lanError(String message) {
            ui(() -> {
                overall.skipTo(connectionStart + 1 + 2.0 * plan.seconds());
                table.addRow(new Object[] {name(), address(), "—", "—", "—", message});
                latency.failed();
                showStatus();
            });
        }

        @Override
        public void lanCompare(List<Report.LanRun> runs) {
            List<Report.LanRun> ok = runs.stream().filter(r -> r.result() != null).toList();
            if (ok.size() < 2) return;
            Report.LanRun best = ok.stream().max(java.util.Comparator.comparingDouble(r -> r.result().download().mbps())).orElseThrow();
            Report.LanRun worst = ok.stream().min(java.util.Comparator.comparingDouble(r -> r.result().download().mbps())).orElseThrow();
            String verdict = Advice.speedVerdict(best.source().name(), best.result().download().mbps(),
                    worst.source().name(), worst.result().download().mbps()) + " on this network";
            ui(() -> table.addRow(new Object[] {"★", "", "", "", "", verdict}));
        }

        @Override
        public void warning(String message, List<String> commands) {
            ui(() -> warnings.add(message, commands));
        }

        private String name() {
            return source == null ? "default" : source.name();
        }

        private String address() {
            return source == null ? "" : source.address().getHostAddress();
        }
    }

    /** "1 Gbps", "2.5 Gbps", "100 Mbps". */
    static String linkName(double tierMbps) {
        return Report.mbps(tierMbps).replace(".00 ", " ").replace(".0 ", " ").replace(".50 ", ".5 ");
    }

    // ---- gauge -------------------------------------------------------------

    /** A card with a half-circle gauge, a big value and a note underneath. */
    static final class Gauge extends JPanel {

        private final String title;
        private String value = "—";
        private String note = "";
        private double fraction;
        private Color color = GuiKit.SKY;
        private boolean live;

        Gauge(String title) {
            this.title = title;
            setOpaque(false);
            setPreferredSize(new Dimension(240, 220));
        }

        void clear() {
            set("—", 0, GuiKit.SKY, "", false);
        }

        void working(double f, String text) {
            set("…", f, GuiKit.SKY, text, true);
        }

        void live(String v, double f, String text) {
            set(v, f, GuiKit.SKY, text, true);
        }

        void show(String v, double f, Color c, String text) {
            set(v, f, c, text, false);
        }

        void failed() {
            set("✖", 0, GuiKit.RED, "could not connect", false);
        }

        private void set(String v, double f, Color c, String text, boolean isLive) {
            value = v;
            fraction = Math.clamp(f, 0, 1);
            color = c;
            note = text;
            live = isLive;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = GuiKit.smooth(g);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(GuiKit.card());
            g2.fillRoundRect(0, 0, w, h, 18, 18);

            g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g2.setColor(GuiKit.muted());
            g2.drawString(title.toUpperCase(), 16, 26);

            int size = Math.max(10, Math.min(w - 60, (h - 70) * 2)); // the arc's diameter: as big as fits
            int cx = w / 2;
            int cy = 40 + size / 2;
            float stroke = Math.max(8f, size / 14f);
            g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(GuiKit.dark() ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 25));
            g2.draw(new Arc2D.Double(cx - size / 2.0, cy - size / 2.0, size, size, 180, -180, Arc2D.OPEN));
            if (fraction > 0) {
                g2.setColor(color);
                g2.draw(new Arc2D.Double(cx - size / 2.0, cy - size / 2.0, size, size, 180, -180 * fraction, Arc2D.OPEN));
            }

            // as big as fits inside the arc
            float fontSize = Math.max(18f, size / 6.5f);
            g2.setFont(GuiKit.bigFont(fontSize));
            FontMetrics fm = g2.getFontMetrics();
            while (fm.stringWidth(value) > size * 0.68 && fontSize > 14) {
                fontSize -= 1;
                g2.setFont(GuiKit.bigFont(fontSize));
                fm = g2.getFontMetrics();
            }
            g2.setColor(live ? GuiKit.SKY : color == GuiKit.SKY ? getForeground() : color);
            g2.drawString(value, cx - fm.stringWidth(value) / 2, cy - 4);
            g2.setFont(getFont().deriveFont(12f));
            fm = g2.getFontMetrics();
            g2.setColor(GuiKit.muted());
            g2.drawString(note, cx - fm.stringWidth(note) / 2, cy + 20);
            g2.dispose();
        }
    }

    /** The rows shown in the results table, for tests. */
    List<List<Object>> rows() {
        List<List<Object>> rows = new ArrayList<>();
        for (int r = 0; r < table.getRowCount(); r++) {
            List<Object> row = new ArrayList<>();
            for (int c = 0; c < table.getColumnCount(); c++) row.add(table.getValueAt(r, c));
            rows.add(row);
        }
        return rows;
    }
}
