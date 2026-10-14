package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs a real client against a real server over loopback. */
class LanTest {

    private ServerSocket server;
    private final List<String> peers = new CopyOnWriteArrayList<>();
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = server.getLocalPort();
        Thread.ofVirtual().start(() -> {
            try {
                Lan.serve(server, peers::add);
            } catch (IOException ignored) {
                // closed by stop()
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    @Test
    void measuresLatency() throws IOException {
        Latency l = Lan.latency("127.0.0.1", port, 5);
        assertEquals(5, l.samplesMs().size());
        assertEquals(0, l.lost());
        assertTrue(l.medianMs() < 100, "loopback latency " + l.medianMs());
        assertEquals(List.of("127.0.0.1"), peers);
    }

    @Test
    void measuresDownloadAndUpload() throws IOException {
        Duration second = Duration.ofSeconds(1);
        Throughput down = Lan.download("127.0.0.1", port, 2, second);
        Throughput up = Lan.upload("127.0.0.1", port, 2, second);
        // Data moved both ways. No speed threshold: CI machines can be slow even over loopback.
        assertTrue(down.bytes() > 0 && down.mbps() > 0, "download " + down.mbps());
        assertTrue(up.bytes() > 0 && up.mbps() > 0, "upload " + up.mbps());
        assertTrue(down.nanos() >= second.toNanos());
    }

    @Test
    void rejectsServersThatAreNotPingPlanet() throws IOException {
        try (ServerSocket other = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket s = other.accept()) {
                    s.getOutputStream().write("SSH-2.0-OpenSSH\n".getBytes());
                } catch (IOException ignored) {
                    // test over
                }
            });
            IOException e = assertThrows(IOException.class, () -> Lan.latency("127.0.0.1", other.getLocalPort(), 1));
            assertTrue(e.getMessage().contains("not a PingPlanet server"), e.getMessage());
        }
    }

    @Test
    void refusedConnectionFails() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            closedPort = s.getLocalPort();
        }
        assertThrows(IOException.class, () -> Lan.download("127.0.0.1", closedPort, 1, Duration.ofSeconds(1)));
    }
}
