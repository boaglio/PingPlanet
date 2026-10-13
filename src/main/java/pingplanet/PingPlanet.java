package pingplanet;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Command line entry point. */
public final class PingPlanet {

    private static final String USAGE = """
            PingPlanet - speed test for your local network and for servers around the world

            Usage:
              pingplanet                       open the window (on a desktop, with no arguments)
              pingplanet gui [--servers FILE]  open the window
              pingplanet world [options]       test internet servers in many countries
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
                  --markdown [FILE.md]   also save the results as a Markdown report
                                         (default: pingplanet-<date>-<time>.md here;
                                         a folder puts it there)
                  --no-color             no colors (also when NO_COLOR is set)
              -h, --help                 show this help
              -v, --version              show the version
            """.formatted(Lan.DEFAULT_PORT);

    private PingPlanet() {
    }

    public static void main(String[] args) {
        // No arguments on a desktop (double-click, start menu, "./pingplanet.sh"): open the window.
        if (args.length == 0) {
            if (Gui.available()) {
                Gui.open(Server.builtIn());
                return;
            }
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                System.err.println("pingplanet: no usable display, so running the test here (\"pingplanet world\" skips this check)");
            }
        }
        try {
            Options gui = Options.parse(args);
            if (gui.command.equals("gui")) {
                if (!Gui.available()) {
                    System.err.println("pingplanet: no display for the window here; run a test in the terminal instead, e.g. \"pingplanet world\"");
                    System.exit(1);
                }
                Gui.open(servers(gui));
                return;
            }
        } catch (UsageException | IOException e) {
            // reported below by run()
        }
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
            err.println("pingplanet: " + Engine.message(e));
            return 1;
        }
    }

    // ---- commands ----------------------------------------------------------

    private static int world(Options o, PrintStream out, PrintStream err) throws IOException {
        List<Server> servers = Server.filter(servers(o), o.countries);
        if (servers.isEmpty()) throw new UsageException("no servers match --countries " + String.join(",", o.countries));
        // With --json the results go to stdout and the human-readable view to stderr.
        RecordingView view = new RecordingView(o.json ? new PlainView(err) : View.create(out, Term.detect(out, o.noColor)));
        Engine.WorldPlan plan = new Engine.WorldPlan(servers, sources(o), !o.latencyOnly, o.seconds, o.streams);
        List<Report.Run> runs = Engine.world(plan, view);
        if (o.json && runs.size() > 1) Report.runsJson(out, runs);
        else if (o.json) Report.json(out, runs.getFirst().results());
        if (o.markdown != null) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            saved(o, out, err, Markdown.write(Markdown.target(o.markdown, now), Markdown.world(plan, runs, view.warnings(), now)));
        }
        return runs.stream().anyMatch(r -> r.reached() > 0) ? 0 : 1;
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

    private static int lan(Options o, PrintStream out, PrintStream err) throws IOException {
        if (o.target == null) throw new UsageException("lan needs the host running \"pingplanet serve\"");
        String host = o.target;
        int port = o.port;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) { // host:port, but leave bare IPv6 addresses alone
            port = parsePort(host.substring(colon + 1));
            host = host.substring(0, colon);
        }
        RecordingView view = new RecordingView(o.json ? new PlainView(err) : View.create(out, Term.detect(out, o.noColor)));
        Engine.LanPlan plan = new Engine.LanPlan(host, port, sources(o), o.seconds, o.streams);
        List<Report.LanRun> runs = Engine.lan(plan, view);
        if (o.json && runs.size() > 1) Report.lanRunsJson(out, runs);
        else if (o.json && runs.getFirst().result() != null) Report.lanJson(out, runs.getFirst().result());
        if (o.markdown != null) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            saved(o, out, err, Markdown.write(Markdown.target(o.markdown, now), Markdown.lan(plan, runs, view.warnings(), now)));
        }
        return runs.stream().anyMatch(r -> r.result() != null) ? 0 : 1;
    }

    /** One line saying where the report went (on stderr with --json, so stdout stays pure JSON). */
    private static void saved(Options o, PrintStream out, PrintStream err, java.nio.file.Path file) {
        (o.json ? err : out).println("  Report saved to " + file.toAbsolutePath().normalize());
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

    static String version() {
        String v = PingPlanet.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
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
        /** null: no report; "": the default file name; otherwise a file or folder. */
        String markdown;
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
                    case "--markdown", "--md" -> {
                        if (value != null) {
                            o.markdown = value;
                        } else if (i + 1 < args.length && (args[i + 1].toLowerCase(Locale.ROOT).endsWith(".md")
                                || Files.isDirectory(Path.of(args[i + 1])))) {
                            o.markdown = args[++i]; // only a .md name or a folder, so "--markdown -c BR" still works
                        } else {
                            o.markdown = "";
                        }
                    }
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
