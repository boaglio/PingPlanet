package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ViewTest {

    private static final Term COLOR = new Term(true, true);

    @Test
    void widthIgnoresColorCodes() {
        String styled = COLOR.bold(COLOR.fg(Term.GREEN, "12 ms"));
        assertTrue(styled.length() > 5);
        assertEquals(5, Term.width(styled));
        assertEquals(8, Term.width(Term.padLeft(styled, 8)));
        assertEquals("12 ms", new Term(true, false).bold(new Term(true, false).fg(Term.GREEN, "12 ms")));
    }

    @Test
    void barsAlwaysFillTheirWidth() {
        for (double f : new double[] {-1, 0, 0.01, 0.33, 0.5, 0.999, 1, 2}) {
            assertEquals(20, Term.width(COLOR.bar(f, 20, Term.SPEED)), "fraction " + f);
            assertEquals(7, Term.width(Term.plain().bar(f, 7, new int[] {1})), "fraction " + f);
        }
        assertEquals("██████████", Term.plain().bar(1, 10, new int[] {1}));
        assertEquals("─────", Term.plain().bar(0, 5, new int[] {1}));
        assertEquals("██▌──", Term.plain().bar(0.5, 5, new int[] {1}));
    }

    @Test
    void latencyRatings() {
        assertEquals(4, Term.latencyBars(5));
        assertEquals(2, Term.latencyBars(120));
        assertEquals(0, Term.latencyBars(400));
        assertEquals(4, Term.width(COLOR.signal(50)));
    }

    @Test
    void linkTiers() {
        assertEquals(100, FancyView.tier(94));
        assertEquals(1_000, FancyView.tier(941));
        assertEquals(2_500, FancyView.tier(1_100));
    }

    @Test
    void mapPlacesCitiesOnLand() {
        WorldMap map = WorldMap.load();
        assertEquals(80, map.width());
        for (Server s : Server.builtIn()) {
            if (!s.located()) continue;
            assertNotNull(map.cell(s.lat(), s.lon()), s.label());
        }
        assertNull(map.cell(-80, 0)); // Antarctica is cut off
        int[] saoPaulo = map.cell(-23.55, -46.63);
        int[] tokyo = map.cell(35.68, 139.69);
        assertTrue(saoPaulo[0] > tokyo[0], "São Paulo is south of Tokyo");
        assertTrue(saoPaulo[1] < tokyo[1], "São Paulo is west of Tokyo");

        List<String> plain = map.render(Term.plain(), List.of(new WorldMap.Marker(-23.55, -46.63, "●", 1, 0)));
        assertEquals('●', plain.get(saoPaulo[0]).charAt(saoPaulo[1]));
        for (String line : plain) assertEquals(80, line.length());
    }

    @Test
    void worldTableLinesAllHaveTheSameWidth() {
        for (int columns : new int[] {120, 90, 80}) {
            for (boolean downloads : new boolean[] {true, false}) {
                String screen = render(columns, view -> {
                    view.worldStart(3, downloads, 5, 4, null);
                    view.latencyProgress(3).update(1, 1);
                    view.latencyDone(3, 1_000_000_000);
                    for (Report.ServerResult r : results(downloads)) {
                        view.downloading(r.server(), r.latency()).update(0, 0);
                        view.downloading(r.server(), r.latency()).update(5_000_000, 500_000_000);
                        view.row(r);
                    }
                    view.worldEnd(results(downloads), 20_000_000_000L);
                });
                List<String> table = java.util.Arrays.stream(screen.split("\n")) // lines() would also split at \r
                        .map(l -> Term.strip(l.substring(l.lastIndexOf('\r') + 1))) // what is left on screen after live redraws
                        .filter(l -> l.startsWith("  ╭") || l.startsWith("  │") || l.startsWith("  ├") || l.startsWith("  ╰"))
                        .toList();
                assertEquals(3 + 3 + 1, table.size(), screen);
                int width = Term.width(table.getFirst());
                assertTrue(width <= Math.max(columns, 84), "table is " + width + " wide for " + columns + " columns");
                for (String line : table) assertEquals(width, Term.width(line), "columns=" + columns + "\n" + screen);
                assertTrue(screen.contains("Latency map"));
                assertEquals(downloads, screen.contains("Download ranking"));
                assertTrue(screen.contains("Lowest latency"));
            }
        }
    }

    @Test
    void plainViewHasNoColorsOrBoxes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        View view = View.create(new PrintStream(bytes, true, StandardCharsets.UTF_8), Term.plain());
        assertTrue(view instanceof PlainView);
        view.worldStart(3, true, 5, 4, null);
        view.latencyDone(3, 1);
        results(true).forEach(view::row);
        view.worldEnd(results(true), 1);
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains("\u001B["));
        assertFalse(text.contains("│"));
        assertTrue(text.contains("Fastest download: São Paulo, Brazil"), text);
    }

    @Test
    void lanAndListRender() {
        String lan = render(100, view -> {
            view.lanStart("192.168.0.10", 7357, null);
            view.lanLatencyProgress(20).update(10, 1);
            view.lanLatency(new Latency(List.of(0.4, 0.5, 0.3), 0));
            view.lanTransferProgress("Download", 5).update(50_000_000, 1_000_000_000);
            view.lanTransfer("Download", new Throughput(588_000_000, 5_000_000_000L));
            view.lanEnd(null);
        });
        assertTrue(lan.contains("940.8 Mbps"), lan);
        assertTrue(lan.contains("of 1 Gbps"), lan);

        String list = render(100, view -> view.list(Server.filter(Server.builtIn(), Set.of("BR", "JP"))));
        assertTrue(list.contains("São Paulo"));
        assertTrue(list.contains("hnd-jp-ping.vultr.com"));
    }

    @Test
    void comparesInterfaces() throws Exception {
        Net.Source eth = new Net.Source("eth0", "Ethernet", java.net.InetAddress.getByName("192.168.0.10"));
        Net.Source wifi = new Net.Source("wlan0", "Wi-Fi", java.net.InetAddress.getByName("192.168.0.23"));
        Server br = new Server("BR", "Brazil", "São Paulo", "Vultr", -23.55, -46.63, URI.create("https://a.example/f"));
        List<Report.Run> runs = List.of(
                new Report.Run(eth, List.of(new Report.ServerResult(br, new Latency(List.of(3.0), 0),
                        new Throughput(225_000_000, 5_000_000_000L), null))),     // 360 Mbps
                new Report.Run(wifi, List.of(new Report.ServerResult(br, new Latency(List.of(12.0), 0),
                        new Throughput(75_000_000, 5_000_000_000L), null))));     // 120 Mbps
        String screen = Term.strip(render(100, view -> {
            view.worldStart(1, true, 5, 4, eth);
            view.compare(runs);
        }));
        assertTrue(screen.contains("eth0 192.168.0.10"), screen);
        assertTrue(screen.contains("Interface comparison"), screen);
        assertTrue(screen.contains("eth0 is 3.0× faster than wlan0, with 9.0 ms less latency"), screen);
        List<String> table = java.util.Arrays.stream(screen.split("\n")).filter(l -> l.startsWith("  │") || l.startsWith("  ╭")).toList();
        for (String line : table) assertEquals(Term.width(table.getFirst()), Term.width(line), screen);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new PlainView(new PrintStream(bytes, true, StandardCharsets.UTF_8)).compare(runs);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("wlan0 192.168.0.23"));
    }

    @Test
    void comparesLanInterfaces() throws Exception {
        Net.Source eth = new Net.Source("eth0", "Ethernet", java.net.InetAddress.getByName("192.168.0.10"));
        Net.Source wifi = new Net.Source("wlan0", "Wi-Fi", java.net.InetAddress.getByName("192.168.0.23"));
        Net.Source usb = new Net.Source("usb0", "USB", java.net.InetAddress.getByName("10.0.0.2"));
        Report.LanResult wired = new Report.LanResult("nas", 7357, new Latency(List.of(0.3), 0),
                new Throughput(587_500_000, 5_000_000_000L), new Throughput(580_000_000, 5_000_000_000L)); // 940 / 928 Mbps
        Report.LanResult wireless = new Report.LanResult("nas", 7357, new Latency(List.of(4.0), 0),
                new Throughput(195_000_000, 5_000_000_000L), new Throughput(150_000_000, 5_000_000_000L)); // 312 / 240 Mbps
        List<Report.LanRun> runs = List.of(new Report.LanRun(eth, wired, null), new Report.LanRun(wifi, wireless, null),
                new Report.LanRun(usb, null, "cannot connect to nas:7357 (timed out)"));
        String screen = Term.strip(render(100, view -> {
            view.lanStart("nas", 7357, usb);
            view.lanError("cannot connect to nas:7357 (timed out)");
            view.lanCompare(runs);
        }));
        assertTrue(screen.contains("eth0 is 3.0× faster than wlan0 on this network"), screen);
        assertTrue(screen.contains("✖ cannot connect"), screen);
        List<String> table = java.util.Arrays.stream(screen.split("\n")).filter(l -> l.startsWith("  │") || l.startsWith("  ╭")).toList();
        assertEquals(5, table.size(), screen);
        for (String line : table) assertEquals(Term.width(table.getFirst()), Term.width(line), screen);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new PlainView(new PrintStream(bytes, true, StandardCharsets.UTF_8)).lanCompare(runs);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("usb0 10.0.0.2"));
    }

    @Test
    void listsEveryConnectionBeforeTesting() throws Exception {
        Net.Source eth = new Net.Source("enp8s0", "Ethernet", java.net.InetAddress.getByName("192.168.0.10"));
        Net.Source wifi = new Net.Source("wlo1", "Wi-Fi", java.net.InetAddress.getByName("192.168.0.23"));
        String screen = Term.strip(render(100, view -> view.connections(List.of(eth, wifi), "enp8s0")));
        assertTrue(screen.contains("2 connections will be tested"), screen);
        assertTrue(screen.contains("enp8s0  192.168.0.10") && screen.contains("● default"), screen);
        assertTrue(screen.contains("wlo1    192.168.0.23"), screen);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new PlainView(new PrintStream(bytes, true, StandardCharsets.UTF_8)).connections(List.of(eth, wifi), "enp8s0");
        assertTrue(bytes.toString(StandardCharsets.UTF_8)
                .contains("Testing 2 connections: enp8s0 192.168.0.10 (default), wlo1 192.168.0.23"));
    }

    @Test
    void sameSpeedsAreNotCalledFaster() throws Exception {
        Net.Source eth = new Net.Source("enp8s0", "Ethernet", java.net.InetAddress.getByName("192.168.0.10"));
        Net.Source wifi = new Net.Source("wlo1", "Wi-Fi", java.net.InetAddress.getByName("192.168.0.23"));
        Server br = new Server("BR", "Brazil", "São Paulo", "Vultr", -23.55, -46.63, URI.create("https://a.example/f"));
        List<Report.Run> runs = List.of(
                new Report.Run(eth, List.of(new Report.ServerResult(br, new Latency(List.of(3.0), 0),
                        new Throughput(190_625_000, 5_000_000_000L), null))),    // 305 Mbps
                new Report.Run(wifi, List.of(new Report.ServerResult(br, new Latency(List.of(3.4), 0),
                        new Throughput(188_125_000, 5_000_000_000L), null))));   // 301 Mbps
        String screen = Term.strip(render(100, view -> view.compare(runs)));
        assertFalse(screen.contains("faster than"), screen);
        assertTrue(screen.contains("enp8s0 and wlo1 are about as fast"), screen);
    }

    @Test
    void listsInterfacesAndWarnings() throws Exception {
        Net.Source wifi = new Net.Source("wlan0", "Wi-Fi", java.net.InetAddress.getByName("192.168.0.23"));
        String screen = Term.strip(render(100, view -> {
            view.interfaces(List.of(wifi), "wlan0");
            view.warning("route it", List.of("sudo ip rule add from 192.168.0.23 table 7357"));
        }));
        assertTrue(screen.contains("● default"), screen);
        assertTrue(screen.contains("pingplanet -i wlan0"), screen);
        assertTrue(screen.contains("sudo ip rule add from 192.168.0.23 table 7357"), screen);
    }

    @Test
    void wrapsLongLines() {
        assertEquals(List.of("one two", "three"), FancyView.wrap("one two three", 8));
        assertEquals(List.of("unbreakable-word"), FancyView.wrap("unbreakable-word", 5));
    }

    // ---- helpers -----------------------------------------------------------

    private static String render(int columns, java.util.function.Consumer<View> script) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        script.accept(new FancyView(new PrintStream(bytes, true, StandardCharsets.UTF_8), COLOR, columns));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static List<Report.ServerResult> results(boolean downloads) {
        Server br = new Server("BR", "Brazil", "São Paulo", "Vultr", -23.55, -46.63, URI.create("https://a.example/f"));
        Server jp = new Server("JP", "Japan", "Tokyo", "Vultr", 35.68, 139.69, URI.create("https://b.example/f"));
        Server any = new Server("ANY", "Nearest edge", "", "Cloudflare", Double.NaN, Double.NaN, URI.create("https://c.example/f"));
        return List.of(
                new Report.ServerResult(br, new Latency(List.of(12.0, 13.0), 0),
                        downloads ? new Throughput(180_000_000, 5_000_000_000L) : null, null),
                new Report.ServerResult(jp, new Latency(List.of(260.0, 270.0), 0),
                        downloads ? new Throughput(20_000_000, 5_000_000_000L) : null, null),
                new Report.ServerResult(any, new Latency(List.of(), 5), null, "connection refused by a very long message"));
    }
}
