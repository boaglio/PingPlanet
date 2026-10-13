package pingplanet;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Measures latency and download speed against public servers on the internet. */
final class InternetTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private InternetTest() {
    }

    /**
     * Times TCP handshakes to the server's port. A connect takes one round trip and,
     * unlike ICMP ping, needs no root privileges and passes most firewalls.
     * DNS is resolved once up front so it does not count.
     */
    static Latency latency(Server server, int samples) {
        InetAddress address;
        try {
            address = InetAddress.getByName(server.host());
        } catch (IOException e) {
            return new Latency(List.of(), samples);
        }
        List<Double> times = new ArrayList<>();
        int lost = 0;
        for (int i = 0; i < samples; i++) {
            try (Socket socket = new Socket()) {
                long start = System.nanoTime();
                socket.connect(new InetSocketAddress(address, server.port()), (int) TIMEOUT.toMillis());
                times.add((System.nanoTime() - start) / 1e6);
            } catch (IOException e) {
                lost++;
            }
            pause(100);
        }
        return new Latency(times, lost);
    }

    /**
     * Downloads the server's file over {@code streams} parallel connections for
     * {@code duration}, counted from the first byte so connection setup is not
     * measured. A stream that reaches the end of the file starts it again.
     * Several streams are needed to fill a fast link to a distant server.
     */
    static Throughput download(Server server, int streams, Duration duration) throws IOException {
        return download(server, streams, duration, Progress.NONE);
    }

    static Throughput download(Server server, int streams, Duration duration, Progress progress) throws IOException {
        // HTTP/1.1 so each stream gets its own TCP connection (HTTP/2 would multiplex them on one).
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(server.url())
                .timeout(TIMEOUT)
                .header("User-Agent", "PingPlanet")
                .header("Cache-Control", "no-cache")
                .build();

        LongAdder bytes = new LongAdder();
        AtomicLong firstByte = new AtomicLong();
        AtomicInteger running = new AtomicInteger(streams);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Set<InputStream> open = ConcurrentHashMap.newKeySet();
        Stopper stop = new Stopper();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < streams; i++) {
            threads.add(Thread.ofVirtual().name("download-" + i).start(() -> {
                try {
                    pump(client, request, bytes, firstByte, open, stop);
                } catch (Exception e) {
                    if (!stop.stopped) failure.compareAndSet(null, e);
                } finally {
                    running.decrementAndGet();
                }
            }));
        }

        try {
            long waitUntil = System.nanoTime() + TIMEOUT.toNanos();
            long lastUpdate = 0;
            while (firstByte.get() == 0 && running.get() > 0 && System.nanoTime() < waitUntil) {
                if (System.nanoTime() - lastUpdate > Progress.INTERVAL_NANOS) {
                    progress.update(0, 0);
                    lastUpdate = System.nanoTime();
                }
                pause(10);
            }
            long start = firstByte.get();
            if (start == 0) {
                Exception e = failure.get();
                throw e != null ? asIOException(e) : new IOException("no data from " + server.host());
            }
            long end = start + duration.toNanos();
            while (System.nanoTime() < end && running.get() > 0) {
                long now = System.nanoTime();
                if (now - lastUpdate > Progress.INTERVAL_NANOS) {
                    progress.update(bytes.sum(), now - start);
                    lastUpdate = now;
                }
                pause(10);
            }
            stop.stopped = true;
            long elapsed = Math.min(System.nanoTime(), end) - start;
            long total = bytes.sum();
            if (running.get() == 0 && failure.get() != null && total == 0) throw asIOException(failure.get());
            return new Throughput(total, elapsed);
        } finally {
            stop.stopped = true;
            for (InputStream in : open) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // closing only to unblock the reader
                }
            }
            client.shutdownNow();
            for (Thread t : threads) {
                try {
                    t.join(1_000); // a stalled stream is abandoned; virtual threads never block exit
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void pump(HttpClient client, HttpRequest request, LongAdder bytes, AtomicLong firstByte,
                             Set<InputStream> open, Stopper stop) throws IOException, InterruptedException {
        byte[] buffer = new byte[64 * 1024];
        while (!stop.stopped) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() >= 400) {
                    throw new IOException("HTTP " + response.statusCode() + " from " + request.uri().getHost());
                }
                open.add(in);
                for (int n; !stop.stopped && (n = in.read(buffer)) != -1; ) {
                    firstByte.compareAndSet(0, System.nanoTime());
                    bytes.add(n);
                }
            } finally {
                open.remove(response.body());
            }
        }
    }

    private static IOException asIOException(Exception e) {
        return e instanceof IOException io ? io : new IOException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Stopper {
        volatile boolean stopped;
    }
}
