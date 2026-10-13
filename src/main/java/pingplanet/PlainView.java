package pingplanet;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Plain text, one line per result: for pipes, logs and terminals without UTF-8. */
final class PlainView implements View {

    private static final String ROW = "  %-3s %-15s %-13s %-10s %9s %9s %12s  %s%n";

    private final PrintStream out;

    PlainView(PrintStream out) {
        this.out = out;
    }

    // ---- world -------------------------------------------------------------

    @Override
    public void worldStart(int servers, boolean downloads, int seconds, int streams, Net.Source source) {
        out.printf(Locale.ROOT, "PingPlanet %s: %d servers%s%s%n%n", PingPlanet.version(), servers,
                downloads ? ", %d s download each over %d streams".formatted(seconds, streams) : ", latency only",
                source == null ? "" : ", via " + source.label());
    }

    @Override
    public Progress latencyProgress(int servers) {
        return Progress.NONE;
    }

    @Override
    public void latencyDone(int servers, long nanos) {
        out.printf(Locale.ROOT, ROW, "", "Country", "City", "Provider", "Latency", "Jitter", "Download", "");
        out.printf(Locale.ROOT, ROW, "", "-".repeat(15), "-".repeat(13), "-".repeat(10), "-".repeat(9), "-".repeat(9),
                "-".repeat(12), "");
    }

    @Override
    public Progress downloading(Server server, Latency latency) {
        return Progress.NONE;
    }

    @Override
    public void row(Report.ServerResult r) {
        Server s = r.server();
        Latency l = r.latency();
        boolean up = l != null && l.reachable();
        out.printf(Locale.ROOT, ROW, s.code(), Term.cut(s.country(), 15), Term.cut(s.city(), 13), Term.cut(s.provider(), 10),
                up ? Report.ms(l.medianMs()) : "-",
                up ? Report.ms(l.jitterMs()) : "-",
                r.download() != null ? Report.mbps(r.download().mbps()) : "-",
                r.error() != null ? r.error() : "");
    }

    @Override
    public void worldEnd(List<Report.ServerResult> results, long nanos) {
        Optional<Report.ServerResult> nearest = Report.lowestLatency(results);
        Optional<Report.ServerResult> fastest = Report.fastest(results);
        out.println();
        nearest.ifPresent(r -> out.printf(Locale.ROOT, "  Lowest latency:   %s (%s)%n", r.server().label(), Report.ms(r.latency().medianMs())));
        fastest.ifPresent(r -> out.printf(Locale.ROOT, "  Fastest download: %s (%s)%n", r.server().label(), Report.mbps(r.download().mbps())));
        if (nearest.isEmpty() && fastest.isEmpty()) out.println("  No server could be reached. Check your internet connection.");
    }

    // ---- lan ---------------------------------------------------------------

    @Override
    public void lanStart(String host, int port, Net.Source source) {
        out.printf(Locale.ROOT, "PingPlanet %s: LAN test to %s:%d%s%n%n", PingPlanet.version(), host, port,
                source == null ? "" : " via " + source.label());
    }

    @Override
    public Progress lanLatencyProgress(int samples) {
        return Progress.NONE;
    }

    @Override
    public void lanLatency(Latency l) {
        out.printf(Locale.ROOT, "  Latency   %s  (jitter %s, loss %.0f%%)%n",
                Report.ms(l.medianMs()), Report.ms(l.jitterMs()), l.lossPercent());
    }

    @Override
    public Progress lanTransferProgress(String label, int seconds) {
        return Progress.NONE;
    }

    @Override
    public void lanTransfer(String label, Throughput t) {
        out.printf(Locale.ROOT, "  %-9s %s%n", label, Report.mbps(t.mbps()));
    }

    @Override
    public void lanEnd(Report.LanResult result) {
        // every line is already printed
    }

    @Override
    public void lanError(String message) {
        out.println("  Failed: " + message);
        out.println();
    }

    @Override
    public void lanCompare(List<Report.LanRun> runs) {
        out.println();
        out.println("Comparison:");
        for (Report.LanRun run : runs) {
            Report.LanResult r = run.result();
            if (r == null) {
                out.printf(Locale.ROOT, "  %-28s failed: %s%n", run.source().label(), run.error());
            } else {
                out.printf(Locale.ROOT, "  %-28s latency %9s  download %12s  upload %12s%n", run.source().label(),
                        Report.ms(r.latency().medianMs()), Report.mbps(r.download().mbps()), Report.mbps(r.upload().mbps()));
            }
        }
    }

    @Override
    public void compare(List<Report.Run> runs) {
        out.println();
        out.println("Comparison:");
        for (Report.Run run : runs) {
            double latency = run.medianLatency();
            double download = run.medianDownload();
            double best = run.bestLatency();
            out.printf(Locale.ROOT, "  %-28s reached %2d/%-2d  best latency %9s  median latency %9s  median download %12s%n",
                    run.source().label(), run.reached(), run.results().size(), Double.isNaN(best) ? "-" : Report.ms(best),
                    Double.isNaN(latency) ? "-" : Report.ms(latency), Double.isNaN(download) ? "-" : Report.mbps(download));
        }
    }

    // ---- other -------------------------------------------------------------

    @Override
    public void warning(String message, List<String> commands) {
        out.println("Warning: " + message);
        for (String c : commands) out.println("  " + c);
        out.println();
    }

    @Override
    public void interfaces(List<Net.Source> sources, String defaultName) {
        if (sources.isEmpty()) out.println("No network interfaces with an IPv4 address are up.");
        for (Net.Source s : sources) {
            out.printf(Locale.ROOT, "  %-12s %-16s %s%s%n", s.name(), s.address().getHostAddress(), s.displayName(),
                    s.name().equals(defaultName) ? "  (default route)" : "");
        }
    }

    @Override
    public void serveStart(int port, List<String> addresses) {
        out.printf(Locale.ROOT, "PingPlanet %s: waiting for LAN tests on port %d (Ctrl+C to stop)%n", PingPlanet.version(), port);
        String suffix = port == Lan.DEFAULT_PORT ? "" : ":" + port;
        if (addresses.isEmpty()) {
            out.println("On another machine run: pingplanet lan <this machine's IP>" + suffix);
        } else {
            for (String a : addresses) out.println("On another machine run: pingplanet lan " + a + suffix);
        }
    }

    @Override
    public void serveTest(String peer) {
        out.println("  " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + peer + "  test started");
    }

    @Override
    public void list(List<Server> servers) {
        for (Server s : servers) {
            out.printf(Locale.ROOT, "  %-3s %-15s %-13s %-10s %s%n", s.code(), s.country(), s.city(), s.provider(), s.url().getHost());
        }
    }
}
