package pingplanet;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** This machine's network connections, and choosing which one a test goes out through. */
final class Net {

    /** A local address to send from, with the interface that owns it ("eth0", "wlan0", "Wi-Fi"…). */
    record Source(String name, String displayName, InetAddress address) {

        String label() {
            return name + " " + address.getHostAddress();
        }
    }

    private static final Path PROC_ROUTE = Path.of("/proc/net/route");

    private Net() {
    }

    /** Interfaces that are up and have an IPv4 address, skipping loopback and container bridges. */
    static List<Source> sources() {
        List<Source> sources = new ArrayList<>();
        try {
            for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
                if (!usable(nic)) continue;
                for (InetAddress a : nic.inetAddresses().toList()) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        sources.add(new Source(nic.getName(), nic.getDisplayName(), a));
                    }
                }
            }
        } catch (IOException e) {
            // nothing to list
        }
        return sources;
    }

    private static boolean usable(NetworkInterface nic) throws IOException {
        String name = nic.getName();
        return nic.isUp() && !nic.isLoopback() && !nic.isVirtual()
                && !name.startsWith("docker") && !name.startsWith("br-") && !name.startsWith("veth")
                && !name.startsWith("virbr") && !name.startsWith("vmnet") && !name.startsWith("vboxnet");
    }

    /**
     * The connections to test when the user did not choose: one address per
     * interface, default route first. Where the routing table is readable
     * (Linux), only interfaces with a default route count, which leaves out VPN
     * and overlay links such as Tailscale that do not lead to the internet.
     */
    static List<Source> internetSources() {
        List<Source> picked = internetSources(sources(), defaultRoutes());
        // Without a routing table to read, ask the system which one it would use and put it first.
        Optional<Source> preferred = defaultSource(INTERNET);
        if (preferred.isPresent()) {
            List<Source> ordered = new ArrayList<>(picked);
            if (ordered.removeIf(s -> s.name().equals(preferred.get().name()))) ordered.addFirst(preferred.get());
            return ordered;
        }
        return picked;
    }

    /** Any public address works: no packet is sent to it. */
    static final InetAddress INTERNET = ipv4(1, 1, 1, 1);

    private static InetAddress ipv4(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e); // never: four bytes are always a valid IPv4 address
        }
    }

    /**
     * The connection the system uses for {@code target} unless told otherwise.
     * "Connecting" a UDP socket sends nothing but makes the OS pick a route and a
     * local address, which works the same on Linux, macOS and Windows.
     */
    static Optional<Source> defaultSource(InetAddress target) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(target, 53);
            InetAddress local = socket.getLocalAddress();
            return sources().stream().filter(s -> s.address().equals(local)).findFirst();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static List<Source> internetSources(List<Source> all, List<Route> defaults) {
        List<String> routed = defaults.stream().map(Route::iface).distinct().toList();
        List<Source> picked = new ArrayList<>();
        for (Source s : all) {
            boolean seen = picked.stream().anyMatch(p -> p.name().equals(s.name()));
            if (!seen && (routed.isEmpty() || routed.contains(s.name()))) picked.add(s);
        }
        // routes are sorted by metric, so this puts the default interface first
        picked.sort(java.util.Comparator.comparingInt(s -> routed.contains(s.name()) ? routed.indexOf(s.name()) : routed.size()));
        return picked;
    }

    /** Finds a source by interface name ("wlan0") or by one of this machine's IP addresses. */
    static Source resolve(String spec) throws IOException {
        List<Source> all = sources();
        for (Source s : all) {
            if (s.name().equalsIgnoreCase(spec) || s.displayName().equalsIgnoreCase(spec)) return s;
        }
        for (Source s : all) {
            if (s.address().getHostAddress().equals(spec)) return s;
        }
        String known = all.isEmpty() ? "none found" : String.join(", ", all.stream().map(Source::label).toList());
        throw new IOException("no network interface or local address \"" + spec + "\" (available: " + known + ")");
    }

    // ---- Linux routing -----------------------------------------------------

    /**
     * Linux picks the outgoing interface from the routing table, not from the
     * source address, so a socket bound to the Wi-Fi address may still leave
     * through the cable. Returns the commands that add a source route for
     * {@code source}, or empty when none is needed (other systems, the default
     * interface, or a rule already in place).
     */
    static Optional<List<String>> linuxRouteFix(Source source) {
        if (!System.getProperty("os.name", "").equalsIgnoreCase("Linux") || !Files.isReadable(PROC_ROUTE)) {
            return Optional.empty();
        }
        List<Route> defaults = defaultRoutes();
        if (defaults.isEmpty() || defaults.getFirst().iface().equals(source.name())) return Optional.empty();
        String ip = source.address().getHostAddress();
        if (hasSourceRule(ip)) return Optional.empty();
        String gateway = defaults.stream()
                .filter(r -> r.iface().equals(source.name()))
                .map(Route::gateway)
                .findFirst()
                .orElse("<gateway of " + source.name() + ">");
        return Optional.of(List.of(
                "sudo ip route add default via " + gateway + " dev " + source.name() + " table 7357",
                "sudo ip rule add from " + ip + " table 7357"));
    }

    record Route(String iface, String gateway, int metric) {
    }

    /** Default routes from /proc/net/route, preferred (lowest metric) first. */
    static List<Route> defaultRoutes() {
        try {
            return parseDefaultRoutes(Files.readAllLines(PROC_ROUTE, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return List.of();
        }
    }

    static List<Route> parseDefaultRoutes(List<String> lines) {
        List<Route> routes = new ArrayList<>();
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) { // skip the header
            String[] f = line.trim().split("\\s+");
            if (f.length < 8 || !f[1].equals("00000000") || !f[7].equals("00000000")) continue;
            byte[] gw = HexFormat.of().parseHex(f[2]); // little-endian
            String gateway = (gw[3] & 0xff) + "." + (gw[2] & 0xff) + "." + (gw[1] & 0xff) + "." + (gw[0] & 0xff);
            routes.add(new Route(f[0], gateway, Integer.parseInt(f[6])));
        }
        routes.sort((a, b) -> Integer.compare(a.metric(), b.metric()));
        return routes;
    }

    private static boolean hasSourceRule(String ip) {
        try {
            Process p = new ProcessBuilder("ip", "rule", "show").redirectErrorStream(true).start();
            String rules = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy();
            String lower = rules.toLowerCase(Locale.ROOT);
            return lower.contains("from " + ip + " ") || lower.contains("from " + ip + "/32 ");
        } catch (IOException e) {
            return false; // no "ip" tool: assume no rule and let the user check
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
