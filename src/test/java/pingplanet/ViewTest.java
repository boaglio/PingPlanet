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
                    view.worldStart(3, downloads, 5, 4);
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
        view.worldStart(3, true, 5, 4);
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
            view.lanStart("192.168.0.10", 7357);
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
