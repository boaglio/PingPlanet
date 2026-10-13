package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdviceTest {

    private static final Net.Source ETH = source("eth0", "Ethernet", "192.168.0.10");
    private static final Net.Source WIFI = source("wlan0", "Wi-Fi", "192.168.0.23");
    private static final Server BR = new Server("BR", "Brazil", "São Paulo", "Vultr", 0, 0, URI.create("https://a.example/f"));

    @Test
    void warnsWhenAnotherConnectionIsClearlyFaster() {
        List<Report.Run> runs = List.of(run(ETH, 120, 5), run(WIFI, 310, 9));
        Advice advice = Advice.world(runs, "eth0").orElseThrow();
        assertEquals(WIFI, advice.better());
        assertEquals("Your default connection eth0 is not the best one: wlan0 downloads 2.6× faster "
                + "(310.0 Mbps vs 120.0 Mbps). Your apps use eth0 unless you make wlan0 the default:", advice.message());
    }

    @Test
    void staysQuietWhenTheDefaultIsBestOrTheGapIsNoise() {
        assertEquals(Optional.empty(), Advice.world(List.of(run(ETH, 310, 3), run(WIFI, 120, 9)), "eth0"));
        assertEquals(Optional.empty(), Advice.world(List.of(run(ETH, 300, 3), run(WIFI, 340, 9)), "eth0")); // +13%: noise
    }

    @Test
    void fasterButMuchLaggierIsNotBetter() {
        // e.g. a satellite or mobile link: more bandwidth, far worse latency
        assertEquals(Optional.empty(), Advice.world(List.of(run(ETH, 100, 5), run(WIFI, 300, 60)), "eth0"));
    }

    @Test
    void warnsWhenTheDefaultReachesNothing() {
        List<Report.Run> runs = List.of(run(ETH, Double.NaN, Double.NaN), run(WIFI, 200, 9));
        assertTrue(Advice.world(runs, "eth0").orElseThrow().reason().contains("eth0 reached no servers"));
    }

    @Test
    void latencyOnlyRunsCompareLatency() {
        List<Report.Run> runs = List.of(run(ETH, Double.NaN, 30), run(WIFI, Double.NaN, 8));
        assertTrue(Advice.world(runs, "eth0").orElseThrow().reason().startsWith("wlan0 answers 22 ms sooner"));
        assertEquals(Optional.empty(), Advice.world(List.of(run(ETH, Double.NaN, 10), run(WIFI, Double.NaN, 7)), "eth0"));
    }

    @Test
    void needsTheDefaultAmongTheRuns() {
        assertEquals(Optional.empty(), Advice.world(List.of(run(ETH, 100, 5), run(WIFI, 300, 5)), "usb0"));
        assertEquals(Optional.empty(), Advice.world(List.of(run(ETH, 100, 5)), "eth0"));
    }

    @Test
    void lanAdvice() {
        Report.LanResult slow = lan(310, 4);
        Report.LanResult fast = lan(940, 0.4);
        Advice a = Advice.lan(List.of(new Report.LanRun(WIFI, slow, null), new Report.LanRun(ETH, fast, null)), "wlan0").orElseThrow();
        assertTrue(a.reason().startsWith("eth0 moves data 3.0× faster"), a.reason());
        assertEquals(Optional.empty(),
                Advice.lan(List.of(new Report.LanRun(ETH, fast, null), new Report.LanRun(WIFI, slow, null)), "eth0"));
        Advice unreachable = Advice.lan(List.of(new Report.LanRun(WIFI, null, "timed out"), new Report.LanRun(ETH, fast, null)),
                "wlan0").orElseThrow();
        assertTrue(unreachable.reason().contains("could not reach the LAN host"));
    }

    @Test
    void howToMakeItTheDefaultOnEachSystem() {
        List<Net.Route> routes = List.of(new Net.Route("eth0", "192.168.0.1", 100), new Net.Route("wlan0", "192.168.0.254", 600));
        assertEquals(List.of(
                "sudo nmcli connection modify \"$(nmcli -g GENERAL.CONNECTION device show wlan0)\" ipv4.route-metric 50",
                "sudo nmcli device reapply wlan0"), Advice.howTo("Linux", WIFI, routes, true));
        assertEquals(List.of("sudo ip route replace default via 192.168.0.254 dev wlan0 metric 50"),
                Advice.howTo("Linux", WIFI, routes, false));
        assertTrue(Advice.howTo("Windows 11", WIFI, List.of(), false).getFirst()
                .startsWith("Get-NetIPAddress -IPAddress 192.168.0.23 | Set-NetIPInterface -InterfaceMetric 10"));
        assertTrue(Advice.howTo("Mac OS X", WIFI, List.of(), false).getFirst().contains("drag Wi-Fi to the top"));
    }

    // ---- helpers -----------------------------------------------------------

    /** A run whose one server gave this download (NaN: none) and latency (NaN: unreachable). */
    private static Report.Run run(Net.Source source, double mbps, double ms) {
        Latency latency = Double.isNaN(ms) ? new Latency(List.of(), 5) : new Latency(List.of(ms), 0);
        Throughput download = Double.isNaN(mbps) ? null : new Throughput((long) (mbps * 1e6 / 8 * 5), 5_000_000_000L);
        String error = Double.isNaN(ms) && Double.isNaN(mbps) ? "unreachable" : null;
        List<Report.ServerResult> results = new ArrayList<>();
        results.add(new Report.ServerResult(BR, latency, download, error));
        return new Report.Run(source, results);
    }

    private static Report.LanResult lan(double mbps, double ms) {
        Throughput t = new Throughput((long) (mbps * 1e6 / 8 * 5), 5_000_000_000L);
        return new Report.LanResult("nas", 7357, new Latency(List.of(ms), 0), t, t);
    }

    private static Net.Source source(String name, String display, String ip) {
        try {
            return new Net.Source(name, display, InetAddress.getByName(ip));
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
