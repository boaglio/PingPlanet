package pingplanet;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the tests and reports through a {@link View}: the terminal and the window
 * share this code. Interrupting the running thread cancels a test with a
 * {@link CancellationException} within a fraction of a second.
 */
final class Engine {

    static final int LATENCY_SAMPLES = 5;
    static final int LAN_LATENCY_SAMPLES = 20;

    /** A world test: these servers, through each of these connections (a null source is the system default). */
    record WorldPlan(List<Server> servers, List<Net.Source> sources, boolean downloads, int seconds, int streams) {
    }

    /** A LAN test against "pingplanet serve" on host:port, through each of these connections. */
    record LanPlan(String host, int port, List<Net.Source> sources, int seconds, int streams) {
    }

    private Engine() {
    }

    // ---- world -------------------------------------------------------------

    static List<Report.Run> world(WorldPlan plan, View view) {
        Optional<String> defaultName = defaultName(Net.INTERNET);
        if (plan.sources().size() > 1) view.connections(plan.sources(), defaultName.orElse(null));
        view.worldPlan(plan.sources().size(), plan.servers().size(), plan.downloads(), plan.seconds());
        List<Report.Run> runs = new ArrayList<>();
        for (Net.Source source : plan.sources()) {
            runs.add(new Report.Run(source, worldRun(plan, source, view)));
        }
        if (runs.size() > 1) {
            view.compare(runs);
            if (!warnIfRoutedTheSame(runs.stream().map(Report.Run::source).toList(),
                    runs.stream().map(Report.Run::medianDownload).toList(), view)) {
                defaultName.flatMap(name -> Advice.world(runs, name))
                        .ifPresent(advice -> view.warning(advice.message(), advice.howTo()));
            }
        }
        return runs;
    }

    /** The whole world test through one connection. */
    private static List<Report.ServerResult> worldRun(WorldPlan plan, Net.Source source, View view) {
        List<Server> servers = plan.servers();
        InetAddress from = source == null ? null : source.address();
        long begin = System.nanoTime();
        view.worldStart(servers.size(), plan.downloads(), plan.seconds(), plan.streams(), source);
        if (source != null) warnAboutRouting(source, view);

        // Latency first, to every server at once: it is light and does not skew other probes much.
        List<Latency> latencies = new ArrayList<>();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Latency>> futures = servers.stream()
                    .map(s -> pool.submit(() -> InternetTest.latency(s, LATENCY_SAMPLES, from)))
                    .toList();
            Progress progress = view.latencyProgress(servers.size());
            for (long done; (done = futures.stream().filter(Future::isDone).count()) < futures.size(); ) {
                checkCancelled();
                progress.update(done, System.nanoTime() - begin);
                InternetTest.pause(Progress.INTERVAL_NANOS / 1_000_000);
            }
            for (Future<Latency> f : futures) latencies.add(join(f));
        } finally {
            pool.shutdownNow(); // returns at once; a cancelled probe gives up within its own timeout
        }
        view.latencyDone(servers.size(), System.nanoTime() - begin);

        // Downloads one server at a time, so they do not compete for bandwidth.
        List<Report.ServerResult> results = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            checkCancelled();
            Server server = servers.get(i);
            Latency latency = latencies.get(i);
            Throughput download = null;
            String error = latency.reachable() ? null : "unreachable";
            if (plan.downloads()) {
                try {
                    download = InternetTest.download(server, plan.streams(), Duration.ofSeconds(plan.seconds()),
                            view.downloading(server, latency), InternetTest.FIRST_BYTE_WAIT, from);
                    error = null; // reachable over HTTP even if direct TCP was blocked (by a proxy, say)
                } catch (IOException e) {
                    checkCancelled();
                    error = message(e);
                }
                checkCancelled();
            }
            Report.ServerResult result = new Report.ServerResult(server, latency, download, error);
            results.add(result);
            view.row(result);
        }

        view.worldEnd(results, System.nanoTime() - begin);
        return results;
    }

    // ---- lan ---------------------------------------------------------------

    /** Every connection's LAN test; a connection that fails is reported to the view and the others still run. */
    static List<Report.LanRun> lan(LanPlan plan, View view) {
        view.lanPlan(plan.sources().size(), plan.seconds());
        Optional<String> defaultName = Optional.empty();
        if (plan.sources().size() > 1) {
            try {
                defaultName = defaultName(InetAddress.getByName(plan.host()));
            } catch (java.net.UnknownHostException e) {
                // each run reports it
            }
            view.connections(plan.sources(), defaultName.orElse(null));
        }
        List<Report.LanRun> runs = new ArrayList<>();
        for (Net.Source source : plan.sources()) {
            checkCancelled();
            try {
                runs.add(new Report.LanRun(source, lanRun(plan, source, view), null));
            } catch (IOException e) {
                checkCancelled();
                view.lanError(message(e));
                runs.add(new Report.LanRun(source, null, message(e)));
            }
        }
        if (runs.size() > 1) {
            view.lanCompare(runs);
            List<Report.LanRun> ok = runs.stream().filter(r -> r.result() != null).toList();
            if (!warnIfRoutedTheSame(ok.stream().map(Report.LanRun::source).toList(),
                    ok.stream().map(r -> r.result().download().mbps()).toList(), view)) {
                defaultName.flatMap(name -> Advice.lan(runs, name))
                        .ifPresent(advice -> view.warning(advice.message(), advice.howTo()));
            }
        }
        return runs;
    }

    private static Report.LanResult lanRun(LanPlan plan, Net.Source source, View view) throws IOException {
        InetAddress from = source == null ? null : source.address();
        Duration duration = Duration.ofSeconds(plan.seconds());
        String host = plan.host();
        int port = plan.port();
        view.lanStart(host, port, source);
        if (source != null) warnAboutRouting(source, view);

        Latency latency;
        try {
            latency = Lan.latency(host, port, LAN_LATENCY_SAMPLES, view.lanLatencyProgress(LAN_LATENCY_SAMPLES), from);
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new IOException("cannot connect to %s:%d (%s). Is \"pingplanet serve\" running there, and is the port open in its firewall?"
                    .formatted(host, port, message(e)), e);
        }
        view.lanLatency(latency);
        checkCancelled();
        Throughput download = Lan.download(host, port, plan.streams(), duration,
                view.lanTransferProgress("Download", plan.seconds()), from);
        view.lanTransfer("Download", download);
        checkCancelled();
        Throughput upload = Lan.upload(host, port, plan.streams(), duration,
                view.lanTransferProgress("Upload", plan.seconds()), from);
        view.lanTransfer("Upload", upload);
        Report.LanResult result = new Report.LanResult(host, port, latency, download, upload);
        view.lanEnd(result);
        return result;
    }

    // ---- shared ------------------------------------------------------------

    /** Name of the interface the system picks for {@code target}, falling back to Linux's default route. */
    static Optional<String> defaultName(InetAddress target) {
        Optional<String> name = Net.defaultSource(target).map(Net.Source::name);
        if (name.isPresent()) return name;
        List<Net.Route> routes = Net.defaultRoutes();
        return routes.isEmpty() ? Optional.empty() : Optional.of(routes.getFirst().iface());
    }

    /**
     * Identical speeds on Linux, with a connection lacking its source route, almost
     * always mean every test left through the default interface. Says so and
     * returns true, because any advice based on those numbers would be wrong.
     */
    private static boolean warnIfRoutedTheSame(List<Net.Source> sources, List<Double> mbps, View view) {
        if (!Advice.sameSpeeds(mbps)) return false;
        for (Net.Source source : sources) {
            Optional<List<String>> fix = Net.linuxRouteFix(source);
            if (fix.isPresent()) {
                view.warning("These connections got the same speed, which on Linux usually means every test left through the "
                        + "default interface: " + source.name() + " has no source route yet. Add it once (until reboot), "
                        + "then run PingPlanet again to compare them for real:", fix.get());
                return true;
            }
        }
        return false;
    }

    private static void warnAboutRouting(Net.Source source, View view) {
        Net.linuxRouteFix(source).ifPresent(commands -> view.warning(
                "Linux routes by destination, so this test may still leave through the default interface instead of "
                        + source.name() + ". For an exact result, add a source route once (until reboot):", commands));
    }

    /** Throws when the thread running the test was interrupted (the GUI's Stop button). */
    static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("stopped");
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("stopped");
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    /** A short, readable reason for a failure. */
    static String message(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "unknown host " + m;
        if (e instanceof java.net.http.HttpConnectTimeoutException || e instanceof java.net.SocketTimeoutException) {
            return "timed out";
        }
        if (e instanceof java.net.ConnectException && (m == null || m.isBlank())) return "connection refused";
        return m != null && !m.isBlank() ? m : e.getClass().getSimpleName();
    }
}
