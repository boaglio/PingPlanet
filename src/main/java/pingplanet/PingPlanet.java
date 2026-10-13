package pingplanet;

import java.io.IOException;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
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

            Options:
              -c, --countries BR,US,JP   only test these country codes (see "list")
              -l, --latency-only         skip the download test (fast)
              -s, --seconds N            seconds per transfer test (default 5)
              -n, --streams N            parallel connections per transfer (default 4)
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
        // With --json the results go to stdout and the human-readable view to stderr.
        View view = o.json ? new PlainView(err) : View.create(out, Term.detect(out, o.noColor));
        long begin = System.nanoTime();
        view.worldStart(servers.size(), !o.latencyOnly, o.seconds, o.streams);

        // Latency first, to every server at once: it is light and does not skew other probes much.
        List<Latency> latencies = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Latency>> futures = servers.stream()
                    .map(s -> pool.submit(() -> InternetTest.latency(s, LATENCY_SAMPLES)))
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
                            view.downloading(server, latency));
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
        if (o.json) Report.json(out, results);
        return results.stream().anyMatch(r -> r.error() == null) ? 0 : 1;
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
        Duration duration = Duration.ofSeconds(o.seconds);
        View view = o.json ? new PlainView(err) : View.create(out, Term.detect(out, o.noColor));
        view.lanStart(host, port);

        Latency latency;
        try {
            latency = Lan.latency(host, port, LAN_LATENCY_SAMPLES, view.lanLatencyProgress(LAN_LATENCY_SAMPLES));
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new IOException("cannot connect to %s:%d (%s). Is \"pingplanet serve\" running there, and is the port open in its firewall?"
                    .formatted(host, port, message(e)), e);
        }
        view.lanLatency(latency);
        Throughput download = Lan.download(host, port, o.streams, duration, view.lanTransferProgress("Download", o.seconds));
        view.lanTransfer("Download", download);
        Throughput upload = Lan.upload(host, port, o.streams, duration, view.lanTransferProgress("Upload", o.seconds));
        view.lanTransfer("Upload", upload);
        Report.LanResult result = new Report.LanResult(host, port, latency, download, upload);
        view.lanEnd(result);
        if (o.json) Report.lanJson(out, result);
        return 0;
    }

    private static int serve(Options o, PrintStream out) throws IOException {
        View view = View.create(out, Term.detect(out, o.noColor));
        view.serveStart(o.port, localAddresses());
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

    /** This machine's LAN IPv4 addresses, so "serve" can say what to type on the other machine. */
    static List<String> localAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
                String name = nic.getName();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()
                        || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                        || name.startsWith("virbr") || name.startsWith("vmnet") || name.startsWith("vboxnet")) {
                    continue;
                }
                for (InetAddress a : nic.inetAddresses().toList()) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        addresses.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException e) {
            // no addresses to suggest; the view prints a placeholder
        }
        return addresses;
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
