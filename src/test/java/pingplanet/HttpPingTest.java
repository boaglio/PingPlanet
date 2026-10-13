package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** HttpPing against a tiny hand-written HTTP server that counts connections. */
class HttpPingTest {

    private ServerSocket server;
    private final AtomicInteger connections = new AtomicInteger();

    @AfterEach
    void stop() throws IOException {
        if (server != null) server.close();
    }

    @Test
    void reusesTheConnectionWhenTheBodyIsSmall() throws IOException {
        URI url = start("HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\n\r\nx", 0);
        try (HttpPing ping = new HttpPing(url, 2_000)) {
            for (int i = 0; i < 5; i++) assertTrue(ping.ping() < 100);
        }
        assertEquals(1, connections.get());
    }

    @Test
    void reconnectsWhenTheServerIgnoresRange() throws IOException {
        URI url = start("HTTP/1.1 200 OK\r\nContent-Length: 25000000\r\n\r\n" + "x".repeat(1000), 0);
        try (HttpPing ping = new HttpPing(url, 2_000)) {
            for (int i = 0; i < 3; i++) assertTrue(ping.ping() < 100);
        }
        assertEquals(3, connections.get());
    }

    @Test
    void slowFirstAnswerIsLeftToTheWarmUp() throws IOException {
        // Like a busy server: the first request on a connection takes long, later ones are quick.
        URI url = start("HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\n\r\nx", 400);
        Server s = new Server("XX", "Test", "Test", "Test", Double.NaN, Double.NaN, url);
        Latency l = InternetTest.latency(s, 3);
        assertEquals(3, l.samplesMs().size());
        assertTrue(l.medianMs() < 100, "median " + l.medianMs());
    }

    /** Answers every request with {@code response}; the first answer per connection waits {@code firstDelayMs}. */
    private URI start(String response, long firstDelayMs) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        byte[] bytes = response.getBytes(StandardCharsets.US_ASCII);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException e) {
                    return;
                }
                connections.incrementAndGet();
                Thread.ofVirtual().start(() -> serve(socket, bytes, firstDelayMs));
            }
        });
        return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/file.bin");
    }

    private static void serve(Socket socket, byte[] response, long firstDelayMs) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            boolean first = true;
            while (readRequest(in)) {
                if (first && firstDelayMs > 0) InternetTest.pause(firstDelayMs);
                first = false;
                out.write(response);
                out.flush();
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static boolean readRequest(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            int b = in.read();
            if (b == -1) return false;
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        return true;
    }
}
