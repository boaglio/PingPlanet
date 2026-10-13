package pingplanet;

import static pingplanet.Term.BRAND;
import static pingplanet.Term.CYAN;
import static pingplanet.Term.GREEN;
import static pingplanet.Term.RED;
import static pingplanet.Term.SKY;
import static pingplanet.Term.SPEED;
import static pingplanet.Term.YELLOW;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Colors, live progress, tables with rounded corners and a world map. */
final class FancyView implements View {

    /** Ethernet and Wi-Fi speed tiers in Mbps; LAN bars are scaled to the tier above the result. */
    private static final double[] LINK_TIERS = {10, 100, 1_000, 2_500, 5_000, 10_000, 25_000, 40_000, 100_000, 400_000};
    /** Table speed bars use a log scale up to here, so 10 Mbps and 1 Gbps both read well. */
    private static final double BAR_FULL_MBPS = 1_000;

    private final PrintStream out;
    private final Term t;
    private final int columns;
    private int liveWidth;
    private boolean bannerShown;

    // world table layout, decided in latencyDone()
    private boolean downloads;
    private boolean showProvider;
    private boolean showBar;
    private int[] widths;

    FancyView(PrintStream out, Term t) {
        this(out, t, Term.columns());
    }

    FancyView(PrintStream out, Term t, int columns) {
        this.out = out;
        this.t = t;
        this.columns = columns;
    }

    // ---- world -------------------------------------------------------------

    @Override
    public void worldStart(int servers, boolean downloads, int seconds, int streams, Net.Source source) {
        this.downloads = downloads;
        banner();
        if (source != null) sourceHeading(source);
        String plan = downloads ? "%d s download per server · %d streams".formatted(seconds, streams) : "latency only";
        out.println("  " + t.fg(SKY, "◉") + " " + t.bold(servers + " servers") + t.dim(" · " + plan));
        out.println();
    }

    /** A rule with the interface name, so runs through different interfaces stand apart. */
    private void sourceHeading(Net.Source source) {
        String title = " " + t.fg(SKY, "⇄") + " " + t.bold(source.name()) + " " + t.dim(source.address().getHostAddress()) + " ";
        int rest = Math.max(4, Math.min(columns, 96) - 4 - Term.width(title));
        out.println("  " + t.faint("──") + title + t.faint("─".repeat(rest)));
        out.println();
    }

    @Override
    public Progress latencyProgress(int servers) {
        return (done, nanos) -> live("  " + t.fg(SKY, spinner()) + " Measuring latency  "
                + t.bar((double) done / servers, 24, BRAND) + "  " + t.dim(done + "/" + servers));
    }

    @Override
    public void latencyDone(int servers, long nanos) {
        endLive();
        out.println("  " + t.fg(GREEN, "✔") + " Latency measured for " + servers + " servers " + t.dim("in " + Report.duration(nanos)));
        out.println();

        // Narrow terminals drop the provider column first, then the bars.
        showBar = downloads && columns >= 84;
        showProvider = columns >= 96 || !downloads;
        List<Integer> w = new ArrayList<>(List.of(3, 14));
        if (showProvider) w.add(10);
        w.add(12); // signal + latency
        w.add(7);  // jitter
        if (downloads) w.add(showBar ? 30 : 13);
        widths = w.stream().mapToInt(Integer::intValue).toArray();

        out.println(border('╭', '┬', '╮'));
        List<String> titles = new ArrayList<>(List.of("", "Location"));
        if (showProvider) titles.add("Provider");
        titles.add("Latency");
        titles.add("Jitter");
        if (downloads) titles.add("Download");
        out.println(cells(titles.stream().map(t::bold).toList()));
        out.println(border('├', '┼', '┤'));
    }

    @Override
    public Progress downloading(Server server, Latency latency) {
        return (bytes, nanos) -> {
            String cell;
            if (nanos == 0) {
                cell = t.fg(SKY, spinner()) + " " + t.dim("connecting…");
            } else {
                double mbps = new Throughput(bytes, nanos).mbps();
                String value = t.fg(Term.speedColor(mbps), Report.mbps(mbps));
                cell = showBar
                        ? t.fg(SKY, spinner()) + " " + t.bar(logScale(mbps), 16, SPEED) + " " + Term.padLeft(value, 11)
                        : t.fg(SKY, spinner()) + " " + Term.padLeft(value, 11);
            }
            live(row(server, latency, cell));
        };
    }

    @Override
    public void row(Report.ServerResult r) {
        endLive();
        String cell = null;
        if (downloads) {
            if (r.download() != null) {
                double mbps = r.download().mbps();
                String value = t.bold(t.fg(Term.speedColor(mbps), Report.mbps(mbps)));
                cell = showBar ? t.bar(logScale(mbps), 18, SPEED) + " " + Term.padLeft(value, 11) : Term.padLeft(value, 13);
            } else {
                cell = t.fg(RED, "✖ " + Term.cut(r.error() == null ? "failed" : r.error(), widths[widths.length - 1] - 2));
            }
        }
        out.println(row(r.server(), r.latency(), cell));
    }

    @Override
    public void worldEnd(List<Report.ServerResult> results, long nanos) {
        out.println(border('╰', '┴', '╯'));
        out.println();

        List<WorldMap.Marker> markers = new ArrayList<>();
        for (Report.ServerResult r : results) {
            Server s = r.server();
            if (!s.located()) continue;
            Latency l = r.latency();
            if (l != null && l.reachable()) {
                markers.add(new WorldMap.Marker(s.lat(), s.lon(), "●", Term.latencyColor(l.medianMs()), l.medianMs()));
            } else {
                markers.add(new WorldMap.Marker(s.lat(), s.lon(), "○", RED, Double.MAX_VALUE));
            }
        }
        if (!markers.isEmpty()) {
            out.println("  " + t.bold("Latency map"));
            out.println();
            for (String line : WorldMap.load().render(t, markers)) out.println("  " + line);
            out.println();
            out.println("  " + t.fg(Term.GREEN, "●") + t.dim(" <30 ms  ") + t.fg(Term.LIME, "●") + t.dim(" <80  ")
                    + t.fg(YELLOW, "●") + t.dim(" <150  ") + t.fg(Term.ORANGE, "●") + t.dim(" <250  ")
                    + t.fg(RED, "●") + t.dim(" 250+  ") + t.fg(RED, "○") + t.dim(" unreachable"));
            out.println();
        }

        List<Report.ServerResult> ranked = results.stream()
                .filter(r -> r.download() != null)
                .sorted(Comparator.comparingDouble((Report.ServerResult r) -> r.download().mbps()).reversed())
                .toList();
        if (ranked.size() > 1) {
            out.println("  " + t.bold("Download ranking"));
            out.println();
            double max = ranked.getFirst().download().mbps();
            int labelWidth = ranked.stream().mapToInt(r -> r.server().label().length()).max().orElse(0);
            labelWidth = Math.clamp(labelWidth, 12, 30);
            int barWidth = Math.clamp(columns - 2 - 3 - labelWidth - 1 - 1 - 11 - 2, 10, 44);
            for (int i = 0; i < ranked.size(); i++) {
                Report.ServerResult r = ranked.get(i);
                double mbps = r.download().mbps();
                int color = Term.speedColor(mbps);
                out.println("  " + t.dim(Term.padLeft(String.valueOf(i + 1), 2)) + " "
                        + Term.padRight(Term.cut(r.server().label(), labelWidth), labelWidth) + " "
                        + t.bar(max > 0 ? mbps / max : 0, barWidth, new int[] {color}) + " "
                        + Term.padLeft(t.fg(color, Report.mbps(mbps)), 11));
            }
            out.println();
        }

        Optional<Report.ServerResult> nearest = Report.lowestLatency(results);
        Optional<Report.ServerResult> fastest = Report.fastest(results);
        nearest.ifPresent(r -> {
            double ms = r.latency().medianMs();
            out.println("  " + t.fg(YELLOW, "★") + " Lowest latency    " + t.bold(r.server().label())
                    + t.dim(" · ") + t.fg(Term.latencyColor(ms), Report.ms(ms)));
        });
        fastest.ifPresent(r -> {
            double mbps = r.download().mbps();
            out.println("  " + t.fg(YELLOW, "★") + " Fastest download  " + t.bold(r.server().label())
                    + t.dim(" · ") + t.fg(Term.speedColor(mbps), Report.mbps(mbps)));
        });
        long reached = results.stream().filter(r -> r.error() == null).count();
        if (reached == 0) {
            out.println("  " + t.fg(RED, "✖") + " No server could be reached. Check your internet connection.");
        } else {
            long bytes = results.stream().filter(r -> r.download() != null).mapToLong(r -> r.download().bytes()).sum();
            String data = bytes > 0 ? t.dim(" · ") + Report.bytes(bytes) + " downloaded" : "";
            out.println("  " + t.fg(GREEN, "✔") + " " + reached + " of " + results.size() + " servers reached"
                    + data + t.dim(" · ") + "finished in " + Report.duration(nanos));
        }
        out.println();
    }

    // ---- lan ---------------------------------------------------------------

    @Override
    public void lanStart(String host, int port, Net.Source source) {
        banner();
        String via = source == null ? "" : t.dim(" from ") + t.bold(source.name()) + t.dim(" " + source.address().getHostAddress());
        out.println("  " + t.fg(SKY, "⇄") + " LAN test to " + t.bold(host) + t.dim(":" + port) + via);
        out.println();
    }

    @Override
    public Progress lanLatencyProgress(int samples) {
        return (done, nanos) -> live(label("Latency") + t.fg(SKY, spinner()) + " "
                + t.bar((double) done / samples, 20, BRAND) + "  " + t.dim(done + "/" + samples + " pings"));
    }

    @Override
    public void lanLatency(Latency l) {
        endLive();
        double ms = l.medianMs();
        out.println(label("Latency") + t.signal(ms) + "  " + t.bold(t.fg(Term.latencyColor(ms), Report.ms(ms)))
                + t.dim("   jitter ") + Report.ms(l.jitterMs())
                + t.dim(" · loss ") + String.format(Locale.ROOT, "%.0f%%", l.lossPercent()));
    }

    @Override
    public Progress lanTransferProgress(String label, int seconds) {
        return (bytes, nanos) -> {
            double mbps = new Throughput(bytes, nanos).mbps();
            long left = Math.max(0, seconds - nanos / 1_000_000_000L);
            live(transferLine(label, mbps, false) + "  " + t.fg(SKY, spinner()) + t.dim(" " + left + " s"));
        };
    }

    @Override
    public void lanTransfer(String label, Throughput throughput) {
        endLive();
        out.println(transferLine(label, throughput.mbps(), true));
    }

    @Override
    public void lanEnd(Report.LanResult result) {
        out.println();
        out.println("  " + t.dim("Bars are scaled to the next common link speed (100 Mbps, 1 Gbps, 2.5 Gbps…)."));
        out.println();
    }

    private String transferLine(String label, double mbps, boolean done) {
        double tier = tier(mbps);
        int color = Term.speedColor(mbps);
        String value = Report.mbps(mbps);
        String line = label(label) + t.bar(mbps / tier, 32, new int[] {color}) + "  "
                + Term.padLeft(done ? t.bold(t.fg(color, value)) : t.fg(color, value), 11);
        return done ? line + t.dim("  of " + Report.mbps(tier).replace(".0 ", " ").replace(".00 ", " ")) : line;
    }

    private String label(String text) {
        return "  " + Term.padRight(t.bold(text), 10);
    }

    static double tier(double mbps) {
        for (double tier : LINK_TIERS) {
            if (mbps <= tier) return tier;
        }
        return LINK_TIERS[LINK_TIERS.length - 1];
    }

    @Override
    public void compare(List<Report.Run> runs) {
        out.println("  " + t.bold("Interface comparison"));
        out.println();
        double maxDownload = runs.stream().mapToDouble(Report.Run::medianDownload).filter(d -> !Double.isNaN(d)).max().orElse(0);
        double lowestBest = runs.stream().mapToDouble(Report.Run::bestLatency).filter(d -> !Double.isNaN(d)).min().orElse(Double.NaN);
        double lowestMedian = runs.stream().mapToDouble(Report.Run::medianLatency).filter(d -> !Double.isNaN(d)).min().orElse(Double.NaN);
        boolean anyDownload = maxDownload > 0;
        widths = anyDownload ? new int[] {10, 15, 7, 8, 8, 30} : new int[] {10, 15, 7, 8, 8};
        out.println(border('╭', '┬', '╮'));
        List<String> titles = new ArrayList<>(List.of(t.bold("Interface"), t.bold("Address"), t.bold("Reached"),
                t.bold("Best"), t.bold("Median")));
        if (anyDownload) titles.add(t.bold("Download (median)"));
        out.println(cells(titles));
        out.println(border('├', '┼', '┤'));
        for (Report.Run run : runs) {
            double latency = run.medianLatency();
            double download = run.medianDownload();
            List<String> c = new ArrayList<>();
            c.add(t.bold(t.fg(CYAN, Term.cut(run.source().name(), 10))));
            c.add(t.dim(run.source().address().getHostAddress()));
            c.add(Term.padLeft(run.reached() + "/" + run.results().size(), 7));
            c.add(Term.padLeft(latencyCell(run.bestLatency(), lowestBest), 8));
            c.add(Term.padLeft(latencyCell(latency, lowestMedian), 8));
            if (anyDownload) {
                if (Double.isNaN(download)) {
                    c.add(t.dim("—"));
                } else {
                    int color = Term.speedColor(download);
                    String value = t.fg(color, Report.mbps(download));
                    c.add(t.bar(download / maxDownload, 18, new int[] {color}) + " "
                            + Term.padLeft(download == maxDownload ? t.bold(value) : value, 11));
                }
            }
            out.println(cells(c));
        }
        out.println(border('╰', '┴', '╯'));
        String verdict = verdict(runs);
        if (verdict != null) out.println("  " + t.fg(YELLOW, "★") + " " + verdict);
        out.println("  " + t.dim("Best is the latency to the nearest server: the part your connection adds."));
        out.println();
    }

    private String latencyCell(double ms, double lowest) {
        if (Double.isNaN(ms)) return t.dim("—");
        String text = t.fg(Term.latencyColor(ms), Report.ms(ms));
        return ms == lowest ? t.bold(text) : text;
    }

    /** "eth0 is 2.4× faster than wlan0, with 9 ms less latency", or null when it cannot tell. */
    private String verdict(List<Report.Run> runs) {
        List<Report.Run> measured = runs.stream().filter(r -> !Double.isNaN(r.medianDownload())).toList();
        if (measured.size() < 2) return null;
        Report.Run best = measured.stream().max(Comparator.comparingDouble(Report.Run::medianDownload)).orElseThrow();
        Report.Run worst = measured.stream().min(Comparator.comparingDouble(Report.Run::medianDownload)).orElseThrow();
        if (best == worst || worst.medianDownload() <= 0) return null;
        String text = t.bold(best.source().name()) + " is "
                + String.format(Locale.ROOT, "%.1f×", best.medianDownload() / worst.medianDownload())
                + " faster than " + t.bold(worst.source().name());
        double diff = worst.bestLatency() - best.bestLatency();
        if (!Double.isNaN(diff) && Math.abs(diff) >= 1) {
            text += diff > 0 ? ", with " + Report.ms(diff) + " less latency" : ", but has " + Report.ms(-diff) + " more latency";
        }
        return text;
    }

    // ---- other -------------------------------------------------------------

    @Override
    public void warning(String message, List<String> commands) {
        List<String> lines = wrap(message, Math.max(40, Math.min(columns, 100) - 6));
        for (int i = 0; i < lines.size(); i++) out.println("  " + t.fg(YELLOW, (i == 0 ? "⚠ " : "  ") + lines.get(i)));
        for (String c : commands) out.println("      " + t.fg(CYAN, c));
        out.println();
    }

    @Override
    public void interfaces(List<Net.Source> sources, String defaultName) {
        banner();
        if (sources.isEmpty()) {
            out.println("  " + t.fg(RED, "✖") + " No network interfaces with an IPv4 address are up.");
            out.println();
            return;
        }
        widths = new int[] {12, 15, 30, 13};
        out.println(border('╭', '┬', '╮'));
        out.println(cells(List.of(t.bold("Interface"), t.bold("Address"), t.bold("Description"), "")));
        out.println(border('├', '┼', '┤'));
        for (Net.Source s : sources) {
            out.println(cells(List.of(t.bold(t.fg(CYAN, Term.cut(s.name(), 12))), s.address().getHostAddress(),
                    t.dim(Term.cut(s.displayName(), 30)),
                    s.name().equals(defaultName) ? t.fg(GREEN, "● default") : "")));
        }
        out.println(border('╰', '┴', '╯'));
        String names = String.join(",", sources.stream().map(Net.Source::name).distinct().toList());
        out.println("  " + t.dim("Compare them with ") + t.fg(CYAN, "pingplanet -i " + names));
        out.println();
    }

    // ---- serve and list ----------------------------------------------------

    @Override
    public void serveStart(int port, List<String> addresses) {
        banner();
        out.println("  " + t.fg(GREEN, "◉") + " " + t.bold("Ready for LAN tests") + t.dim(" on port " + port + " · Ctrl+C to stop"));
        out.println();
        String suffix = port == Lan.DEFAULT_PORT ? "" : ":" + port;
        out.println("  On another machine on this network, run:");
        out.println();
        if (addresses.isEmpty()) {
            out.println("    " + t.fg(CYAN, "pingplanet lan ") + t.dim("<this machine's IP>") + t.fg(CYAN, suffix));
        }
        for (String a : addresses) out.println("    " + t.fg(CYAN, "pingplanet lan " + a + suffix));
        out.println();
    }

    @Override
    public void serveTest(String peer) {
        out.println("  " + t.dim(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))) + "  "
                + t.fg(SKY, "⇄") + " " + t.bold(peer) + " started a test");
    }

    @Override
    public void list(List<Server> servers) {
        banner();
        List<WorldMap.Marker> markers = servers.stream()
                .filter(Server::located)
                .map(s -> new WorldMap.Marker(s.lat(), s.lon(), "●", CYAN, 0))
                .toList();
        if (!markers.isEmpty()) {
            for (String line : WorldMap.load().render(t, markers)) out.println("  " + line);
            out.println();
        }
        widths = new int[] {3, 14, 15, 10, 26};
        out.println(border('╭', '┬', '╮'));
        out.println(cells(List.of("", t.bold("City"), t.bold("Country"), t.bold("Provider"), t.bold("Host"))));
        out.println(border('├', '┼', '┤'));
        for (Server s : servers) {
            out.println(cells(List.of(t.bold(t.fg(CYAN, s.code())), Term.cut(s.city().isEmpty() ? "Anycast" : s.city(), 14),
                    Term.cut(s.country(), 15), t.dim(Term.cut(s.provider(), 10)), t.dim(Term.cut(s.host(), 26)))));
        }
        out.println(border('╰', '┴', '╯'));
        out.println("  " + t.dim(servers.size() + " servers · test some with ") + t.fg(CYAN, "pingplanet -c BR,US,JP"));
        out.println();
    }

    // ---- pieces ------------------------------------------------------------

    private void banner() {
        if (bannerShown) return; // once per run, also when testing several interfaces
        bannerShown = true;
        List<String> lines = new ArrayList<>();
        try (InputStream in = FancyView.class.getResourceAsStream("/pingplanet/banner.txt")) {
            if (in != null) lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
        } catch (IOException e) {
            // the banner is decoration; carry on without it
        }
        int width = lines.stream().mapToInt(String::length).max().orElse(0);
        out.println();
        for (String line : lines) out.println("  " + t.gradient(Term.padRight(line, width), BRAND));
        out.println("  " + t.dim("speed test for your network and the planet") + t.faint(" · ") + t.dim("v" + PingPlanet.version()));
        out.println();
    }

    /** One world table row; {@code download} is the last cell, or null in latency-only mode. */
    private String row(Server s, Latency l, String download) {
        List<String> c = new ArrayList<>();
        c.add(t.bold(t.fg(CYAN, s.code())));
        c.add(Term.cut(s.city().isEmpty() ? s.country() : s.city(), 14));
        if (showProvider) c.add(t.dim(Term.cut(s.provider(), 10)));
        if (l != null && l.reachable()) {
            double ms = l.medianMs();
            c.add(t.signal(ms) + " " + Term.padLeft(t.fg(Term.latencyColor(ms), Report.ms(ms)), 7));
            c.add(Term.padLeft(t.dim(Report.ms(l.jitterMs())), 7));
        } else {
            c.add(t.faint("▂▄▆█") + " " + Term.padLeft(t.dim("—"), 7));
            c.add(Term.padLeft(t.dim("—"), 7));
        }
        if (downloads) c.add(download == null ? "" : download);
        return cells(c);
    }

    private String cells(List<String> cells) {
        StringBuilder sb = new StringBuilder("  ").append(t.faint("│"));
        for (int i = 0; i < cells.size(); i++) {
            sb.append(' ').append(Term.padRight(cells.get(i), widths[i])).append(' ').append(t.faint("│"));
        }
        return sb.toString();
    }

    private String border(char left, char middle, char right) {
        StringBuilder sb = new StringBuilder().append(left);
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2)).append(i < widths.length - 1 ? middle : right);
        }
        return "  " + t.faint(sb.toString());
    }

    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static double logScale(double mbps) {
        return Math.log10(1 + Math.max(0, mbps)) / Math.log10(1 + BAR_FULL_MBPS);
    }

    private static String spinner() {
        return Term.SPINNER[(int) (System.nanoTime() / 80_000_000L % Term.SPINNER.length)];
    }

    /** Draws over the current line; the next live() or endLive() replaces it. */
    private void live(String line) {
        int width = Term.width(line);
        out.print("\r" + line + " ".repeat(Math.max(0, liveWidth - width)));
        out.flush();
        liveWidth = width;
    }

    private void endLive() {
        if (liveWidth == 0) return;
        out.print(Term.rewind(liveWidth));
        out.flush();
        liveWidth = 0;
    }
}
