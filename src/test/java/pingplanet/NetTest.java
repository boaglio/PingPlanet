package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NetTest {

    @Test
    void parsesLinuxDefaultRoutes() {
        List<Net.Route> routes = Net.parseDefaultRoutes(List.of(
                "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT",
                "wlan0\t00000000\t0100A8C0\t0003\t0\t0\t600\t00000000\t0\t0\t0",
                "eth0\t00000000\t0100A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0",
                "eth0\t0000A8C0\t00000000\t0001\t0\t0\t100\t00FFFFFF\t0\t0\t0"));
        assertEquals(2, routes.size());
        assertEquals(new Net.Route("eth0", "192.168.0.1", 100), routes.getFirst()); // lowest metric wins
        assertEquals(new Net.Route("wlan0", "192.168.0.1", 600), routes.get(1));
    }

    @Test
    void unknownInterfaceListsTheAvailableOnes() {
        IOException e = assertThrows(IOException.class, () -> Net.resolve("no-such-nic-42"));
        assertTrue(e.getMessage().contains("available"), e.getMessage());
    }

    @Test
    void knownInterfacesResolveByNameAndAddress() throws IOException {
        for (Net.Source s : Net.sources()) {
            assertEquals(s, Net.resolve(s.name()));
            assertEquals(s.address(), Net.resolve(s.address().getHostAddress()).address());
        }
    }

    @Test
    void connectionsComeFromTheChosenAddress() throws Exception {
        // 127.0.0.2 is a different loopback address than the default 127.0.0.1 on Linux;
        // the server sees which one the client bound to.
        InetAddress source = InetAddress.getByName("127.0.0.2");
        try (Socket probe = new Socket()) {
            probe.bind(new java.net.InetSocketAddress(source, 0));
        } catch (IOException e) {
            Assumptions.abort("127.0.0.2 is not a local address here (macOS)");
        }
        List<String> seen = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        seen.add(s.getInetAddress().getHostAddress());
                        InputStream in = s.getInputStream();
                        OutputStream out = s.getOutputStream();
                        for (int m = 0; m < 4; ) { // read the request headers
                            int b = in.read();
                            if (b == -1) break;
                            m = b == "\r\n\r\n".charAt(m) ? m + 1 : (b == '\r' ? 1 : 0);
                        }
                        out.write("HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx"
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException ignored) {
                        // next
                    }
                }
            });
            URI url = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/f");
            try (HttpPing ping = new HttpPing(url, 2_000, source)) {
                ping.ping();
            }
            try (HttpPing ping = new HttpPing(url, 2_000)) {
                ping.ping();
            }
        }
        assertEquals(List.of("127.0.0.2", "127.0.0.1"), seen);
    }
}
