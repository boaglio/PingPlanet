package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 10, 13, 8, 20, 5);
    private static final Server BR = new Server("BR", "Brazil", "São Paulo", "Vultr", -23.55, -46.63, URI.create("https://a.example/f"));
    private static final Server JP = new Server("JP", "Japan", "Tokyo", "Vultr", 35.68, 139.69, URI.create("https://b.example/f"));

    @Test
    void worldReportHasSummaryTableAndRanking() {
        List<Report.ServerResult> results = List.of(
                new Report.ServerResult(BR, new Latency(List.of(3.4), 0), new Throughput(190_000_000, 5_000_000_000L), null),
                new Report.ServerResult(JP, new Latency(List.of(262.0), 0), null, "no data | from host"));
        Engine.WorldPlan plan = new Engine.WorldPlan(List.of(BR, JP), Collections.singletonList(null), true, 5, 4);
        String md = Markdown.world(plan, List.of(new Report.Run(null, results)), List.of(), WHEN);

        assertTrue(md.startsWith("# PingPlanet report\n"), md);
        assertTrue(md.contains("**Date:** 2026-10-13 08:20"), md);
        assertTrue(md.contains("**Test:** 2 servers, 5 s download each over 4 streams"), md);
        assertTrue(md.contains("- **Lowest latency:** São Paulo, Brazil · 3.4 ms"), md);
        assertTrue(md.contains("- **Fastest download:** São Paulo, Brazil · 304.0 Mbps"), md);
        assertTrue(md.contains("| BR | São Paulo, Brazil | Vultr | 🟢 3.4 ms | 0.0 ms | **304.0 Mbps** |"), md);
        assertTrue(md.contains("| JP | Tokyo, Japan | Vultr | 🔴 262 ms | 0.0 ms | ✖ no data \\| from host |"), md); // | escaped
        assertFalse(md.contains("## Connections"), "one connection: no comparison");
        assertFalse(md.contains("Download ranking"), "one download: nothing to rank");
    }

    @Test
    void severalConnectionsGetAComparisonAndWarnings() throws Exception {
        Net.Source eth = new Net.Source("enp8s0", "Ethernet", InetAddress.getByName("192.168.0.10"));
        Net.Source wifi = new Net.Source("wlo1", "Wi-Fi", InetAddress.getByName("192.168.0.23"));
        Report.Run a = new Report.Run(eth, List.of(
                new Report.ServerResult(BR, new Latency(List.of(3.0), 0), new Throughput(190_000_000, 5_000_000_000L), null),
                new Report.ServerResult(JP, new Latency(List.of(260.0), 0), new Throughput(60_000_000, 5_000_000_000L), null)));
        Report.Run b = new Report.Run(wifi, List.of(
                new Report.ServerResult(BR, new Latency(List.of(9.0), 0), new Throughput(70_000_000, 5_000_000_000L), null),
                new Report.ServerResult(JP, new Latency(List.of(270.0), 0), new Throughput(20_000_000, 5_000_000_000L), null)));
        Engine.WorldPlan plan = new Engine.WorldPlan(List.of(BR, JP), List.of(eth, wifi), true, 5, 4);
        String md = Markdown.world(plan, List.of(a, b),
                List.of(new RecordingView.Warning("Linux routes by destination.", List.of("sudo ip rule add from 192.168.0.23 table 7357"))), WHEN);

        assertTrue(md.contains("## Connections"), md);
        assertTrue(md.contains("| **enp8s0** | 192.168.0.10 | 2/2 | 3.0 ms |"), md);
        assertTrue(md.contains("★ enp8s0 is 2.8× faster than wlo1"), md);
        assertTrue(md.contains("- **Fastest download:** São Paulo, Brazil · 304.0 Mbps (via enp8s0)"), md);
        assertTrue(md.contains("## Results via enp8s0 (192.168.0.10)") && md.contains("## Results via wlo1 (192.168.0.23)"), md);
        assertTrue(md.contains("> ⚠️ Linux routes by destination.\n\n```sh\nsudo ip rule add from 192.168.0.23 table 7357\n```"), md);
        assertTrue(md.contains("<summary>Download ranking via enp8s0</summary>"), md);
    }

    @Test
    void lanReport() throws Exception {
        Net.Source eth = new Net.Source("enp8s0", "Ethernet", InetAddress.getByName("192.168.0.10"));
        Net.Source wifi = new Net.Source("wlo1", "Wi-Fi", InetAddress.getByName("192.168.0.23"));
        Report.LanResult wired = new Report.LanResult("nas", 7357, new Latency(List.of(0.4), 0),
                new Throughput(587_500_000, 5_000_000_000L), new Throughput(580_000_000, 5_000_000_000L));
        Engine.LanPlan plan = new Engine.LanPlan("nas", 7357, List.of(eth, wifi), 5, 4);
        String md = Markdown.lan(plan, List.of(new Report.LanRun(eth, wired, null), new Report.LanRun(wifi, null, "timed out")),
                List.of(), WHEN);
        assertTrue(md.startsWith("# PingPlanet LAN report\n"), md);
        assertTrue(md.contains("**Host:** nas:7357"), md);
        assertTrue(md.contains("| **enp8s0** | 192.168.0.10 | 0.4 ms | 0.0 ms | 0% | **940.0 Mbps** | **928.0 Mbps** |"), md);
        assertTrue(md.contains("| **wlo1** | 192.168.0.23 | ✖ timed out |"), md);
    }

    @Test
    void reportFileNames() throws Exception {
        Path folder = Path.of("target", "markdown-test");
        if (Files.isDirectory(folder)) {
            try (var old = Files.list(folder)) {
                for (Path f : old.toList()) Files.delete(f);
            }
        }
        Files.createDirectories(folder);
        assertEquals(Path.of("pingplanet-20261013-082005.md"), Markdown.target("", WHEN));
        assertEquals(Path.of("my.md"), Markdown.target("my.md", WHEN));
        Path first = Markdown.write(Markdown.target(folder.toString(), WHEN), "one");
        Path second = Markdown.write(Markdown.target(folder.toString(), WHEN), "two"); // same second
        assertEquals("pingplanet-20261013-082005.md", first.getFileName().toString());
        assertEquals("pingplanet-20261013-082005-2.md", second.getFileName().toString());
        assertEquals("one", Files.readString(first));
    }

    @Test
    void markdownOptionTakesOnlyAFileOrFolder() {
        assertEquals("r.md", PingPlanet.Options.parse(new String[] {"--markdown", "r.md"}).markdown);
        assertEquals("x", PingPlanet.Options.parse(new String[] {"--markdown=x"}).markdown);
        PingPlanet.Options o = PingPlanet.Options.parse(new String[] {"--markdown", "-c", "BR"});
        assertEquals("", o.markdown); // default name
        assertEquals(java.util.Set.of("BR"), o.countries);
        assertEquals("target", PingPlanet.Options.parse(new String[] {"--md", "target"}).markdown); // an existing folder
        assertEquals(null, PingPlanet.Options.parse(new String[0]).markdown);
    }
}
