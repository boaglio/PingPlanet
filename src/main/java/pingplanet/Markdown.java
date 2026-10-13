package pingplanet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A Markdown report of a test, to keep, share, or paste into a README or an issue. */
final class Markdown {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Markdown() {
    }

    // ---- world -------------------------------------------------------------

    static String world(Engine.WorldPlan plan, List<Report.Run> runs, List<RecordingView.Warning> warnings, LocalDateTime when) {
        StringBuilder md = new StringBuilder("# PingPlanet report\n\n");
        String test = plan.servers().size() + " servers, " + (plan.downloads()
                ? plan.seconds() + " s download each over " + plan.streams() + " streams" : "latency only");
        md.append("**Date:** ").append(WHEN.format(when)).append(" · **PingPlanet** ").append(PingPlanet.version())
                .append(" · **Test:** ").append(test).append("\n\n");

        List<Report.ServerResult> all = runs.stream().flatMap(r -> r.results().stream()).toList();
        md.append("## Summary\n\n");
        Report.lowestLatency(all).ifPresent(r -> md.append("- **Lowest latency:** ").append(cell(r.server().label()))
                .append(" · ").append(Report.ms(r.latency().medianMs())).append(via(runs, r)).append('\n'));
        Report.fastest(all).ifPresent(r -> md.append("- **Fastest download:** ").append(cell(r.server().label()))
                .append(" · ").append(Report.mbps(r.download().mbps())).append(via(runs, r)).append('\n'));
        for (Report.Run run : runs) {
            md.append("- ").append(run.source() == null ? "" : "**" + run.source().name() + ":** ")
                    .append(run.reached()).append(" of ").append(run.results().size()).append(" servers reached\n");
        }
        md.append('\n');

        if (runs.size() > 1) {
            md.append("## Connections\n\n");
            md.append("| Interface | Address | Reached | Best latency | Median latency | Median download |\n");
            md.append("|---|---|--:|--:|--:|--:|\n");
            for (Report.Run run : runs) {
                md.append("| **").append(run.source().name()).append("** | ").append(run.source().address().getHostAddress())
                        .append(" | ").append(run.reached()).append('/').append(run.results().size())
                        .append(" | ").append(ms(run.bestLatency())).append(" | ").append(ms(run.medianLatency()))
                        .append(" | ").append(mbps(run.medianDownload())).append(" |\n");
            }
            List<Report.Run> measured = runs.stream().filter(r -> !Double.isNaN(r.medianDownload())).toList();
            if (measured.size() >= 2) {
                Report.Run best = measured.stream().max(Comparator.comparingDouble(Report.Run::medianDownload)).orElseThrow();
                Report.Run worst = measured.stream().min(Comparator.comparingDouble(Report.Run::medianDownload)).orElseThrow();
                if (best != worst) {
                    md.append("\n★ ").append(Advice.speedVerdict(best.source().name(), best.medianDownload(),
                            worst.source().name(), worst.medianDownload())).append('\n');
                }
            }
            md.append("\n*Best latency is to the nearest server: the part the connection itself adds.*\n\n");
        }

        warnings(md, warnings);

        for (Report.Run run : runs) {
            md.append(run.source() == null ? "## Results\n\n"
                    : "## Results via " + run.source().name() + " (" + run.source().address().getHostAddress() + ")\n\n");
            md.append(plan.downloads() ? "| | Location | Provider | Latency | Jitter | Download |\n|---|---|---|--:|--:|--:|\n"
                    : "| | Location | Provider | Latency | Jitter |\n|---|---|---|--:|--:|\n");
            for (Report.ServerResult r : run.results()) {
                Latency l = r.latency();
                boolean up = l != null && l.reachable();
                md.append("| ").append(r.server().code()).append(" | ").append(cell(r.server().label()))
                        .append(" | ").append(cell(r.server().provider()))
                        .append(" | ").append(up ? dot(l.medianMs()) + " " + Report.ms(l.medianMs()) : "—")
                        .append(" | ").append(up ? Report.ms(l.jitterMs()) : "—");
                if (plan.downloads()) {
                    md.append(" | ").append(r.download() != null ? "**" + Report.mbps(r.download().mbps()) + "**"
                            : "✖ " + cell(r.error() == null ? "failed" : r.error()));
                }
                md.append(" |\n");
            }
            md.append('\n');
            ranking(md, run);
        }
        md.append("<sub>🟢 under 80 ms · 🟡 under 150 ms · 🟠 under 250 ms · 🔴 250 ms or more. ")
                .append("Made with [PingPlanet](https://github.com/boaglio/PingPlanet).</sub>\n");
        return md.toString();
    }

    /** The download ranking as text bars, which every Markdown viewer shows as they are. */
    private static void ranking(StringBuilder md, Report.Run run) {
        List<Report.ServerResult> ranked = run.results().stream()
                .filter(r -> r.download() != null)
                .sorted(Comparator.comparingDouble((Report.ServerResult r) -> r.download().mbps()).reversed())
                .toList();
        if (ranked.size() < 2) return;
        double max = ranked.getFirst().download().mbps();
        int labelWidth = Math.min(28, ranked.stream().mapToInt(r -> r.server().label().length()).max().orElse(10));
        md.append("<details><summary>Download ranking").append(run.source() == null ? "" : " via " + run.source().name())
                .append("</summary>\n\n```\n");
        for (Report.ServerResult r : ranked) {
            double mbps = r.download().mbps();
            md.append(String.format(Locale.ROOT, "%-" + labelWidth + "s ", Term.cut(r.server().label(), labelWidth)))
                    .append(Term.plain().bar(max > 0 ? mbps / max : 0, 30, new int[] {0}))
                    .append(String.format(Locale.ROOT, " %11s%n", Report.mbps(mbps)));
        }
        md.append("```\n\n</details>\n\n");
    }

    // ---- lan ---------------------------------------------------------------

    static String lan(Engine.LanPlan plan, List<Report.LanRun> runs, List<RecordingView.Warning> warnings, LocalDateTime when) {
        StringBuilder md = new StringBuilder("# PingPlanet LAN report\n\n");
        md.append("**Date:** ").append(WHEN.format(when)).append(" · **PingPlanet** ").append(PingPlanet.version())
                .append(" · **Host:** ").append(cell(plan.host())).append(':').append(plan.port())
                .append(" · **Test:** ").append(plan.seconds()).append(" s each way over ").append(plan.streams()).append(" streams\n\n");
        md.append("| Connection | Address | Latency | Jitter | Loss | Download | Upload |\n|---|---|--:|--:|--:|--:|--:|\n");
        for (Report.LanRun run : runs) {
            String name = run.source() == null ? "default" : "**" + run.source().name() + "**";
            String address = run.source() == null ? "" : run.source().address().getHostAddress();
            Report.LanResult r = run.result();
            if (r == null) {
                md.append("| ").append(name).append(" | ").append(address).append(" | ✖ ").append(cell(run.error()))
                        .append(" | | | | |\n");
                continue;
            }
            Latency l = r.latency();
            md.append("| ").append(name).append(" | ").append(address)
                    .append(" | ").append(Report.ms(l.medianMs())).append(" | ").append(Report.ms(l.jitterMs()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f%%", l.lossPercent()))
                    .append(" | **").append(Report.mbps(r.download().mbps())).append("** | **")
                    .append(Report.mbps(r.upload().mbps())).append("** |\n");
        }
        List<Report.LanRun> ok = runs.stream().filter(r -> r.result() != null).toList();
        if (ok.size() >= 2) {
            Report.LanRun best = ok.stream().max(Comparator.comparingDouble(r -> r.result().download().mbps())).orElseThrow();
            Report.LanRun worst = ok.stream().min(Comparator.comparingDouble(r -> r.result().download().mbps())).orElseThrow();
            if (best != worst) {
                md.append("\n★ ").append(Advice.speedVerdict(best.source().name(), best.result().download().mbps(),
                        worst.source().name(), worst.result().download().mbps())).append(" on this network\n");
            }
        }
        md.append('\n');
        warnings(md, warnings);
        md.append("<sub>Made with [PingPlanet](https://github.com/boaglio/PingPlanet).</sub>\n");
        return md.toString();
    }

    // ---- files -------------------------------------------------------------

    /**
     * Where the report goes: {@code spec} itself, or a new "pingplanet-yyyyMMdd-HHmmss.md"
     * in {@code spec} when it is a folder, or in the current folder when it is empty.
     */
    static Path target(String spec, LocalDateTime when) {
        Path folder = spec == null || spec.isBlank() ? Path.of("") : Path.of(spec);
        if (!folder.toString().isEmpty() && !Files.isDirectory(folder)) return folder; // a file name: used as given
        // A generated name never replaces an earlier report, even one from the same second.
        String base = "pingplanet-" + STAMP.format(when);
        Path file = folder.resolve(base + ".md");
        for (int n = 2; Files.exists(file); n++) file = folder.resolve(base + "-" + n + ".md");
        return file;
    }

    static Path write(Path file, String markdown) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
        return file;
    }

    // ---- helpers -----------------------------------------------------------

    private static void warnings(StringBuilder md, List<RecordingView.Warning> warnings) {
        if (warnings.isEmpty()) return;
        md.append("## Warnings\n\n");
        for (RecordingView.Warning w : warnings) {
            md.append("> ⚠️ ").append(w.message()).append("\n\n");
            if (!w.commands().isEmpty()) md.append("```sh\n").append(String.join("\n", w.commands())).append("\n```\n\n");
        }
    }

    /** Which connection got this result, when there were several. */
    private static String via(List<Report.Run> runs, Report.ServerResult result) {
        if (runs.size() < 2) return "";
        return runs.stream().filter(r -> r.results().contains(result)).findFirst()
                .map(r -> " (via " + r.source().name() + ")").orElse("");
    }

    /** 🟢 🟡 🟠 🔴 by latency, like the colors on screen. */
    static String dot(double ms) {
        if (ms < 80) return "🟢";
        if (ms < 150) return "🟡";
        if (ms < 250) return "🟠";
        return "🔴";
    }

    /** Text that is safe inside a table cell. */
    static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private static String ms(double value) {
        return Double.isNaN(value) ? "—" : Report.ms(value);
    }

    private static String mbps(double value) {
        return Double.isNaN(value) ? "—" : Report.mbps(value);
    }
}
