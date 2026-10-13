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

    @Test
    void subtractsServerProcessingTime() throws IOException {
        // The server says it spent 300 ms; the network part is what is left.
        URI url = start("HTTP/1.1 200 OK\r\nServer-Timing: edge;dur=100, worker;dur=200\r\nContent-Length: 0\r\n\r\n", 0, 300);
        try (HttpPing ping = new HttpPing(url, 2_000)) {
            ping.ping();
            double ms = ping.ping();
            assertTrue(ms < 100, "ping " + ms);
        }
    }

    @Test
    void parsesServerTiming() {
        assertEquals(23, HttpPing.serverTiming("cfspeededge;dur=3, cfspeedworker;dur=20"));
        assertEquals(0, HttpPing.serverTiming("cfl4;desc=\"?proto=tcp&rtt=3743\""));
        assertEquals(1.5, HttpPing.serverTiming("db;dur=\"1.5\""));
    }

    @Test
    void cloudflareIsPingedForZeroBytes() {
        Server cf = new Server("ANY", "Nearest edge", "", "Cloudflare", Double.NaN, Double.NaN,
                URI.create("https://speed.cloudflare.com/__down?bytes=25000000"));
        assertEquals("https://speed.cloudflare.com/__down?bytes=0", cf.pingUrl().toString());
        Server plain = new Server("BR", "Brazil", "São Paulo", "Vultr", 0, 0, URI.create("https://a.example/100MB.bin"));
        assertEquals(plain.url(), plain.pingUrl());
    }

    /** Answers every request with {@code response}; the first answer per connection waits {@code firstDelayMs}. */
    private URI start(String response, long firstDelayMs) throws IOException {
        return start(response, firstDelayMs, 0);
    }

    /** Like {@link #start(String, long)}, and every answer waits {@code delayMs}. */
    private URI start(String response, long firstDelayMs, long delayMs) throws IOException {
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
                Thread.ofVirtual().start(() -> serve(socket, bytes, firstDelayMs, delayMs));
            }
        });
        return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/file.bin");
    }

    private static void serve(Socket socket, byte[] response, long firstDelayMs, long delayMs) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            boolean first = true;
            while (readRequest(in)) {
                if (first && firstDelayMs > 0) InternetTest.pause(firstDelayMs);
                first = false;
                if (delayMs > 0) InternetTest.pause(delayMs);
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
