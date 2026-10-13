package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the internet test against a local HTTP server standing in for a real one. */
class InternetTestTest {

    private static final int FILE_SIZE = 4 * 1024 * 1024;

    private HttpServer http;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/file.bin", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, FILE_SIZE);
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = exchange.getResponseBody()) {
                for (int sent = 0; sent < FILE_SIZE; sent += chunk.length) out.write(chunk);
            } catch (IOException ignored) {
                // client stopped reading
            }
        });
        http.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        http.start();
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    @Test
    void latencyUsesTcpConnect() {
        Latency l = InternetTest.latency(server("/file.bin"), 3);
        assertEquals(3, l.samplesMs().size());
        assertEquals(0, l.lost());
    }

    @Test
    void downloadRepeatsTheFileUntilTimeIsUp() throws IOException {
        Duration second = Duration.ofSeconds(1);
        Throughput t = InternetTest.download(server("/file.bin"), 3, second);
        assertTrue(t.mbps() > 10, "mbps " + t.mbps());
        assertTrue(t.nanos() <= second.toNanos(), "measured window " + t.nanos());
        // A 4 MB file over loopback ends long before a second passes, so streams must re-request it.
        assertTrue(t.bytes() > 3L * FILE_SIZE, "bytes " + t.bytes());
        assertTrue(requests.get() > 3, "requests " + requests.get());
    }

    @Test
    void httpErrorsAreReported() {
        IOException e = assertThrows(IOException.class,
                () -> InternetTest.download(server("/missing"), 2, Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
    }

    @Test
    void unreachableServerHasNoLatency() throws IOException {
        int closedPort;
        try (var s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        Server dead = new Server("XX", "Nowhere", "Nowhere", "Test", Double.NaN, Double.NaN, URI.create("http://127.0.0.1:" + closedPort + "/f"));
        Latency l = InternetTest.latency(dead, 2);
        assertEquals(2, l.lost());
        assertThrows(IOException.class, () -> InternetTest.download(dead, 1, Duration.ofSeconds(1)));
    }

    private Server server(String path) {
        return new Server("XX", "Test", "Loopback", "Test", Double.NaN, Double.NaN,
                URI.create("http://127.0.0.1:" + http.getAddress().getPort() + path));
    }
}
