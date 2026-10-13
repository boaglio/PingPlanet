package pingplanet;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Command line entry point. */
public final class PingPlanet {

    private static final String USAGE = """
            PingPlanet - speed test for your local network and for servers around the world

            Usage:
              pingplanet [world] [options]     test internet servers in many countries (default)
              pingplanet lan <host>[:port]     test the local network against "pingplanet serve"
              pingplanet serve [--port N]      wait for "pingplanet lan" tests from other machines
              pingplanet list                  show the internet test servers
              pingplanet interfaces            show this machine's network connections

            Options:
              -c, --countries BR,US,JP   only test these country codes (see "list")
              -l, --latency-only         skip the download test (fast)
              -s, --seconds N            seconds per transfer test (default 5)
              -n, --streams N            parallel connections per transfer (default 4)
              -i, --interface eth0,wlan0 test only through these interfaces (names or local IPs);
                                         "default" uses just the system's default route.
                                         Without -i, every connection to the internet is
                                         tested, one run each, then compared
              -p, --port N               LAN port (default %d)
                  --servers FILE         use your own server list (CSV, same format as "list --csv")
                  --csv                  with "list": print the server list as CSV
                  --json                 print results as JSON
                  --no-color             no colors (also when NO_COLOR is set)
              -h, --help                 show this help
              -v, --version              show the version
            """.formatted(Lan.DEFAULT_PORT);

    private static final int LATENCY_SAMPLES = 5;
    private static final int LAN_LATENCY_SAMPLES = 20;

    private PingPlanet() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args, System.out, System.err));
        } catch (UsageException e) {
            System.err.println("pingplanet: " + e.getMessage());
            System.err.println("Try \"pingplanet --help\".");
            System.exit(2);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Options o = Options.parse(args);
        if (o.help) {
            out.print(USAGE);
            return 0;
        }
        if (o.version) {
            out.println("PingPlanet " + version());
            return 0;
        }
        try {
            return switch (o.command) {
                case "world" -> world(o, out, err);
                case "lan" -> lan(o, out, err);
                case "serve" -> serve(o, out);
                case "list" -> list(o, out);
                case "interfaces" -> interfaces(o, out);
                default -> throw new UsageException("unknown command: " + o.command);
            };
        } catch (IOException e) {
            err.println("pingplanet: " + message(e));
            return 1;
        }
    }

    // ---- commands ----------------------------------------------------------

    private static int world(Options o, PrintStream out, PrintStream err) throws IOException {
        List<Server> servers = Server.filter(servers(o), o.countries);
        if (servers.isEmpty()) throw new UsageException("no servers match --countries " + String.join(",", o.countries));
        List<Net.Source> sources = sources(o);
        // With --json the results go to stdout and the human-readable view to stderr.
        View view = o.json ? new PlainView(err) : View.create(out, Term.detect(out, o.noColor));

        java.util.Optional<String> defaultName = defaultName(Net.INTERNET);
        if (sources.size() > 1) view.connections(sources, defaultName.orElse(null));
        List<Report.Run> runs = new ArrayList<>();
        for (Net.Source source : sources) {
            runs.add(new Report.Run(source, worldRun(o, servers, source, view)));
        }
        if (runs.size() > 1) {
            view.compare(runs);
            if (!warnIfRoutedTheSame(runs.stream().map(Report.Run::source).toList(),
                    runs.stream().map(Report.Run::medianDownload).toList(), view)) {
                defaultName.flatMap(name -> Advice.world(runs, name))
                        .ifPresent(advice -> view.warning(advice.message(), advice.howTo()));
            }
        }

        if (o.json && runs.size() > 1) Report.runsJson(out, runs);
        else if (o.json) Report.json(out, runs.getFirst().results());
        return runs.stream().anyMatch(r -> r.reached() > 0) ? 0 : 1;
    }

    /** The whole world test through one interface ({@code source} null means the system default). */
    private static List<Report.ServerResult> worldRun(Options o, List<Server> servers, Net.Source source, View view) {
        InetAddress from = source == null ? null : source.address();
        long begin = System.nanoTime();
        view.worldStart(servers.size(), !o.latencyOnly, o.seconds, o.streams, source);
        if (source != null) warnAboutRouting(source, view);

        // Latency first, to every server at once: it is light and does not skew other probes much.
        List<Latency> latencies = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Latency>> futures = servers.stream()
                    .map(s -> pool.submit(() -> InternetTest.latency(s, LATENCY_SAMPLES, from)))
                    .toList();
            Progress progress = view.latencyProgress(servers.size());
            for (long done; (done = futures.stream().filter(Future::isDone).count()) < futures.size(); ) {
                progress.update(done, System.nanoTime() - begin);
                InternetTest.pause(Progress.INTERVAL_NANOS / 1_000_000);
            }
            for (Future<Latency> f : futures) latencies.add(join(f));
        }
        view.latencyDone(servers.size(), System.nanoTime() - begin);

        // Downloads one server at a time, so they do not compete for bandwidth.
        List<Report.ServerResult> results = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            Latency latency = latencies.get(i);
            Throughput download = null;
            String error = latency.reachable() ? null : "unreachable";
            if (!o.latencyOnly) {
                try {
                    download = InternetTest.download(server, o.streams, Duration.ofSeconds(o.seconds),
                            view.downloading(server, latency), InternetTest.FIRST_BYTE_WAIT, from);
                    error = null; // reachable over HTTP even if direct TCP was blocked (by a proxy, say)
                } catch (IOException e) {
                    error = message(e);
                }
            }
            Report.ServerResult result = new Report.ServerResult(server, latency, download, error);
            results.add(result);
            view.row(result);
        }

        view.worldEnd(results, System.nanoTime() - begin);
        return results;
    }

    /**
     * The connections to test. Without --interface, every interface that leads to
     * the internet; a single one (or "-i default") is a plain run with no
     * interface chosen. A null entry means "let the system choose".
     */
    static List<Net.Source> sources(Options o) throws IOException {
        List<Net.Source> sources = new ArrayList<>();
        if (o.interfaces.isEmpty()) {
            sources.addAll(Net.internetSources());
            if (sources.size() <= 1) return java.util.Collections.singletonList(null);
            return sources;
        }
        if (o.interfaces.stream().anyMatch(spec -> spec.equalsIgnoreCase("default"))) {
            if (o.interfaces.size() > 1) throw new UsageException("\"-i default\" cannot be combined with other interfaces");
            return java.util.Collections.singletonList(null);
        }
        for (String spec : o.interfaces) sources.add(Net.resolve(spec));
        return sources;
    }

    /** Name of the interface the system picks for {@code target}, falling back to Linux's default route. */
    private static java.util.Optional<String> defaultName(InetAddress target) {
        java.util.Optional<String> name = Net.defaultSource(target).map(Net.Source::name);
        if (name.isPresent()) return name;
        List<Net.Route> routes = Net.defaultRoutes();
        return routes.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(routes.getFirst().iface());
    }

    /**
     * Identical speeds on Linux, with a connection lacking its source route, almost
     * always mean every test left through the default interface. Says so and
     * returns true, because any advice based on those numbers would be wrong.
     */
    private static boolean warnIfRoutedTheSame(List<Net.Source> sources, List<Double> mbps, View view) {
        if (!Advice.sameSpeeds(mbps)) return false;
        for (Net.Source source : sources) {
            java.util.Optional<List<String>> fix = Net.linuxRouteFix(source);
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

    private static int lan(Options o, PrintStream out, PrintStream err) throws IOException {
        if (o.target == null) throw new UsageException("lan needs the host running \"pingplanet serve\"");
        String host = o.target;
        int port = o.port;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) { // host:port, but leave bare IPv6 addresses alone
            port = parsePort(host.substring(colon + 1));
            host = host.substring(0, colon);
        }
        List<Net.Source> sources = sources(o);
        View view = o.json ? new PlainView(err) : View.create(out, Term.detect(out, o.noColor));

        if (sources.size() == 1) { // one connection: errors end the program with a clear message
            Report.LanResult result = lanRun(o, host, port, sources.getFirst(), view);
            if (o.json) Report.lanJson(out, result);
            return 0;
        }
        InetAddress target;
        try {
            target = InetAddress.getByName(host);
        } catch (java.net.UnknownHostException e) {
            throw new IOException("unknown host " + host, e);
        }
        java.util.Optional<String> defaultName = defaultName(target);
        view.connections(sources, defaultName.orElse(null));
        List<Report.LanRun> runs = new ArrayList<>();
        for (Net.Source source : sources) {
            try {
                runs.add(new Report.LanRun(source, lanRun(o, host, port, source, view), null));
            } catch (IOException e) {
                view.lanError(message(e));
                runs.add(new Report.LanRun(source, null, message(e)));
            }
        }
        view.lanCompare(runs);
        List<Report.LanRun> ok = runs.stream().filter(r -> r.result() != null).toList();
        if (!warnIfRoutedTheSame(ok.stream().map(Report.LanRun::source).toList(),
                ok.stream().map(r -> r.result().download().mbps()).toList(), view)) {
            defaultName.flatMap(name -> Advice.lan(runs, name))
                    .ifPresent(advice -> view.warning(advice.message(), advice.howTo()));
        }
        if (o.json) Report.lanRunsJson(out, runs);
        return runs.stream().anyMatch(r -> r.result() != null) ? 0 : 1;
    }

    /** The LAN test through one interface ({@code source} null means the system default). */
    private static Report.LanResult lanRun(Options o, String host, int port, Net.Source source, View view) throws IOException {
        InetAddress from = source == null ? null : source.address();
        Duration duration = Duration.ofSeconds(o.seconds);
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
        Throughput download = Lan.download(host, port, o.streams, duration, view.lanTransferProgress("Download", o.seconds), from);
        view.lanTransfer("Download", download);
        Throughput upload = Lan.upload(host, port, o.streams, duration, view.lanTransferProgress("Upload", o.seconds), from);
        view.lanTransfer("Upload", upload);
        Report.LanResult result = new Report.LanResult(host, port, latency, download, upload);
        view.lanEnd(result);
        return result;
    }

    private static int serve(Options o, PrintStream out) throws IOException {
        View view = View.create(out, Term.detect(out, o.noColor));
        view.serveStart(o.port, Net.sources().stream().map(s -> s.address().getHostAddress()).toList());
        Lan.serve(o.port, view::serveTest);
        return 0;
    }

    private static int list(Options o, PrintStream out) throws IOException {
        List<Server> servers = Server.filter(servers(o), o.countries);
        if (o.csv) {
            out.println(Server.HEADER);
            for (Server s : servers) out.println(s.toCsv());
            return 0;
        }
        View.create(out, Term.detect(out, o.noColor)).list(servers);
        return 0;
    }

    private static int interfaces(Options o, PrintStream out) {
        List<Net.Route> routes = Net.defaultRoutes();
        View.create(out, Term.detect(out, o.noColor)).interfaces(Net.sources(), routes.isEmpty() ? null : routes.getFirst().iface());
        return 0;
    }

    // ---- helpers -----------------------------------------------------------

    private static List<Server> servers(Options o) throws IOException {
        if (o.serversFile == null) return Server.builtIn();
        if (!Files.isRegularFile(o.serversFile)) throw new UsageException("no such file: " + o.serversFile);
        try {
            return Server.load(o.serversFile);
        } catch (IOException e) {
            throw new IOException(o.serversFile + ": " + e.getMessage(), e);
        }
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    static String version() {
        String v = PingPlanet.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "unknown host " + m;
        if (e instanceof java.net.http.HttpConnectTimeoutException || e instanceof java.net.SocketTimeoutException) {
            return "timed out";
        }
        if (e instanceof java.net.ConnectException && (m == null || m.isBlank())) return "connection refused";
        return m != null && !m.isBlank() ? m : e.getClass().getSimpleName();
    }

    private static int parsePort(String text) {
        int port = parseInt(text, "port");
        if (port < 1 || port > 65535) throw new UsageException("port must be between 1 and 65535");
        return port;
    }

    private static int parseInt(String text, String what) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new UsageException(what + " must be a number: " + text);
        }
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** Parsed command line. */
    static final class Options {
        String command = "world";
        String target;
        Set<String> countries = Set.of();
        boolean latencyOnly;
        int seconds = 5;
        int streams = 4;
        int port = Lan.DEFAULT_PORT;
        Path serversFile;
        boolean csv;
        List<String> interfaces = List.of();
        boolean json;
        boolean noColor;
        boolean help;
        boolean version;

        static Options parse(String[] args) {
            Options o = new Options();
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String value = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) { // --seconds=10
                    value = arg.substring(eq + 1);
                    arg = arg.substring(0, eq);
                }
                switch (arg) {
                    case "-h", "--help" -> o.help = true;
                    case "-v", "--version" -> o.version = true;
                    case "-l", "--latency-only" -> o.latencyOnly = true;
                    case "--json" -> o.json = true;
                    case "--csv" -> o.csv = true;
                    case "--no-color" -> o.noColor = true;
                    case "-c", "--countries" -> o.countries = countries(value != null ? value : next(args, ++i, arg));
                    case "-s", "--seconds" -> o.seconds = range(parseInt(value != null ? value : next(args, ++i, arg), "seconds"), 1, 60, "seconds");
                    case "-n", "--streams" -> o.streams = range(parseInt(value != null ? value : next(args, ++i, arg), "streams"), 1, 32, "streams");
                    case "-p", "--port" -> o.port = parsePort(value != null ? value : next(args, ++i, arg));
                    case "-i", "--interface" -> o.interfaces = Arrays.stream((value != null ? value : next(args, ++i, arg)).split(","))
                            .map(String::strip).filter(x -> !x.isEmpty()).toList();
                    case "--servers" -> o.serversFile = Path.of(value != null ? value : next(args, ++i, arg));
                    default -> {
                        if (arg.startsWith("-")) throw new UsageException("unknown option: " + arg);
                        positional.add(arg);
                    }
                }
            }
            if (!positional.isEmpty()) o.command = positional.removeFirst().toLowerCase(Locale.ROOT);
            if (o.command.equals("lan") && !positional.isEmpty()) o.target = positional.removeFirst();
            if (!positional.isEmpty()) throw new UsageException("unexpected argument: " + positional.getFirst());
            return o;
        }

        private static String next(String[] args, int i, String option) {
            if (i >= args.length) throw new UsageException(option + " needs a value");
            return args[i];
        }

        private static int range(int value, int min, int max, String what) {
            if (value < min || value > max) throw new UsageException(what + " must be between " + min + " and " + max);
            return value;
        }

        private static Set<String> countries(String list) {
            Set<String> codes = new LinkedHashSet<>();
            Arrays.stream(list.split("[,\\s]+"))
                    .map(c -> c.strip().toUpperCase(Locale.ROOT))
                    .filter(c -> !c.isEmpty())
                    .forEach(codes::add);
            return codes;
        }
    }
}
