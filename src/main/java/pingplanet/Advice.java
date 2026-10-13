package pingplanet;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tells the user when the connection their system uses by default is not the
 * best one tested, and how to make the better one the default.
 *
 * <p>Margins keep run-to-run noise from raising false alarms: another connection
 * must be at least 20% faster without being more than 20 ms slower to answer,
 * or (latency-only runs) at least 5 ms and 20% quicker to answer.
 */
record Advice(Net.Source current, Net.Source better, String reason) {

    static final double MIN_SPEEDUP = 1.2;
    static final double MAX_EXTRA_LATENCY_MS = 20;
    static final double MIN_LATENCY_GAIN_MS = 5;
    /** Speeds within 10% of each other are "about the same": the normal spread between two runs. */
    static final double SAME_SPEED = 1.1;

    /** "eth0 is 2.8× faster than wlan0", or "eth0 and wlan0 are about as fast" when the gap is noise. */
    static String speedVerdict(String fast, double fastMbps, String slow, double slowMbps) {
        if (slowMbps > 0 && fastMbps / slowMbps < SAME_SPEED) {
            return fast + " and " + slow + " are about as fast (" + Report.mbps(fastMbps) + " vs " + Report.mbps(slowMbps) + ")";
        }
        String times = slowMbps > 0 ? String.format(Locale.ROOT, "%.1f×", fastMbps / slowMbps) : "much";
        return fast + " is " + times + " faster than " + slow;
    }

    /** True when every measured connection got about the same download speed. */
    static boolean sameSpeeds(List<Double> mbps) {
        List<Double> measured = mbps.stream().filter(m -> !Double.isNaN(m) && m > 0).toList();
        if (measured.size() < 2) return false;
        double max = measured.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double min = measured.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        return max / min < SAME_SPEED;
    }

    String message() {
        return "Your default connection " + current.name() + " is not the best one: " + reason + ". Your apps use "
                + current.name() + " unless you make " + better.name() + " the default:";
    }

    // ---- deciding ------------------------------------------------------------

    static Optional<Advice> world(List<Report.Run> runs, String defaultName) {
        Optional<Report.Run> def = runs.stream().filter(r -> r.source() != null && r.source().name().equals(defaultName)).findFirst();
        if (def.isEmpty() || runs.size() < 2) return Optional.empty();
        Report.Run d = def.get();
        List<Report.Run> others = runs.stream().filter(r -> r != d && r.source() != null).toList();

        if (d.reached() == 0) {
            return others.stream().filter(r -> r.reached() > 0).max(Comparator.comparingLong(Report.Run::reached))
                    .map(r -> new Advice(d.source(), r.source(),
                            d.source().name() + " reached no servers while " + r.source().name() + " reached " + r.reached()));
        }
        Optional<Report.Run> faster = others.stream()
                .filter(r -> !Double.isNaN(r.medianDownload()) && !Double.isNaN(d.medianDownload()))
                .max(Comparator.comparingDouble(Report.Run::medianDownload));
        if (faster.isPresent()) {
            Report.Run f = faster.get();
            return speedAdvice(d.source(), d.medianDownload(), d.bestLatency(), f.source(), f.medianDownload(), f.bestLatency(),
                    "downloads");
        }
        return others.stream().filter(r -> !Double.isNaN(r.bestLatency()))
                .min(Comparator.comparingDouble(Report.Run::bestLatency))
                .flatMap(q -> latencyAdvice(d.source(), d.bestLatency(), q.source(), q.bestLatency()));
    }

    static Optional<Advice> lan(List<Report.LanRun> runs, String defaultName) {
        Optional<Report.LanRun> def = runs.stream().filter(r -> r.source().name().equals(defaultName)).findFirst();
        if (def.isEmpty() || runs.size() < 2) return Optional.empty();
        Report.LanRun d = def.get();
        List<Report.LanRun> ok = runs.stream().filter(r -> r != d && r.result() != null).toList();
        if (d.result() == null) {
            return ok.stream().findFirst().map(r -> new Advice(d.source(), r.source(),
                    d.source().name() + " could not reach the LAN host while " + r.source().name() + " could"));
        }
        double dSpeed = d.result().download().mbps();
        double dLatency = d.result().latency().medianMs();
        return ok.stream()
                .max(Comparator.comparingDouble(r -> r.result().download().mbps()))
                .flatMap(f -> speedAdvice(d.source(), dSpeed, dLatency, f.source(), f.result().download().mbps(),
                        f.result().latency().medianMs(), "moves data"));
    }

    private static Optional<Advice> speedAdvice(Net.Source current, double currentMbps, double currentMs,
                                                Net.Source other, double otherMbps, double otherMs, String verb) {
        if (currentMbps <= 0 ? otherMbps <= 0 : otherMbps < currentMbps * MIN_SPEEDUP) return Optional.empty();
        if (!Double.isNaN(otherMs) && !Double.isNaN(currentMs) && otherMs > currentMs + MAX_EXTRA_LATENCY_MS) return Optional.empty();
        String times = currentMbps > 0 ? String.format(Locale.ROOT, "%.1f× faster", otherMbps / currentMbps) : "faster";
        return Optional.of(new Advice(current, other, other.name() + " " + verb + " " + times + " ("
                + Report.mbps(otherMbps) + " vs " + Report.mbps(currentMbps) + ")"));
    }

    private static Optional<Advice> latencyAdvice(Net.Source current, double currentMs, Net.Source other, double otherMs) {
        if (Double.isNaN(currentMs) || otherMs > currentMs - MIN_LATENCY_GAIN_MS || otherMs > currentMs / MIN_SPEEDUP) {
            return Optional.empty();
        }
        return Optional.of(new Advice(current, other, other.name() + " answers " + Report.ms(currentMs - otherMs)
                + " sooner (" + Report.ms(otherMs) + " vs " + Report.ms(currentMs) + ")"));
    }

    // ---- fixing ------------------------------------------------------------

    /** How to make {@code better} the default connection on this system. */
    List<String> howTo() {
        return howTo(System.getProperty("os.name", ""), better, Net.defaultRoutes(), onPath("nmcli"));
    }

    static List<String> howTo(String os, Net.Source better, List<Net.Route> routes, boolean nmcli) {
        String name = better.name();
        if (os.startsWith("Windows")) {
            return List.of("Get-NetIPAddress -IPAddress " + better.address().getHostAddress()
                    + " | Set-NetIPInterface -InterfaceMetric 10    # PowerShell, as administrator");
        }
        if (os.startsWith("Mac")) {
            return List.of("System Settings → Network → ⋯ → Set Service Order…, then drag " + better.displayName() + " to the top");
        }
        if (nmcli) {
            return List.of(
                    "sudo nmcli connection modify \"$(nmcli -g GENERAL.CONNECTION device show " + name + ")\" ipv4.route-metric 50",
                    "sudo nmcli device reapply " + name);
        }
        String gateway = routes.stream().filter(r -> r.iface().equals(name)).map(Net.Route::gateway).findFirst()
                .orElse("<gateway of " + name + ">");
        return List.of("sudo ip route replace default via " + gateway + " dev " + name + " metric 50");
    }

    private static boolean onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, command))) return true;
        }
        return false;
    }
}
