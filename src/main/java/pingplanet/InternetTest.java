package pingplanet;

import java.io.IOException;
import java.io.InputStream;
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
    /** Busy speed-test servers can take a while to answer; streams keep retrying until this passes. */
    static final Duration FIRST_BYTE_WAIT = Duration.ofSeconds(20);

    private InternetTest() {
    }

    /**
     * Times small HTTP requests on an open connection (see {@link HttpPing}).
     * The first request is a warm-up and is not counted: it pays for the server
     * waking up and caches filling.
     */
    static Latency latency(Server server, int samples) {
        List<Double> times = new ArrayList<>();
        int lost = 0;
        try (HttpPing ping = new HttpPing(server.pingUrl(), (int) TIMEOUT.toMillis())) {
            try {
                ping.ping();
            } catch (IOException e) {
                return new Latency(List.of(), samples); // cannot even connect
            }
            for (int i = 0; i < samples; i++) {
                pause(100);
                try {
                    times.add(ping.ping());
                } catch (IOException e) {
                    lost++;
                    ping.close();
                }
            }
        }
        return new Latency(times, lost);
    }

    /**
     * Downloads the server's file over {@code streams} parallel connections for
     * {@code duration}, counted from the first byte so connection setup is not
     * measured. A stream that reaches the end of the file starts it again.
     * Several streams are needed to fill a fast link to a distant server.
     * A stream whose connection fails or times out tries again, because busy
     * servers often drop some connections; HTTP errors end the stream.
     */
    static Throughput download(Server server, int streams, Duration duration) throws IOException {
        return download(server, streams, duration, Progress.NONE);
    }

    static Throughput download(Server server, int streams, Duration duration, Progress progress) throws IOException {
        return download(server, streams, duration, progress, FIRST_BYTE_WAIT);
    }

    static Throughput download(Server server, int streams, Duration duration, Progress progress, Duration firstByteWait)
            throws IOException {
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
                    pump(client, request, bytes, firstByte, open, stop, failure);
                } catch (Exception e) {
                    if (!stop.stopped) failure.set(e);
                } finally {
                    running.decrementAndGet();
                }
            }));
        }

        try {
            long waitUntil = System.nanoTime() + firstByteWait.toNanos();
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
                throw e != null ? asIOException(e)
                        : new IOException("no data from " + server.host() + " in " + firstByteWait.toSeconds() + " s");
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
                             Set<InputStream> open, Stopper stop, AtomicReference<Exception> failure)
            throws IOException, InterruptedException {
        byte[] buffer = new byte[64 * 1024];
        while (!stop.stopped) {
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                if (stop.stopped) return;
                failure.set(e); // remembered in case no stream ever gets data
                pause(500);
                continue;
            }
            try (InputStream in = response.body()) {
                if (response.statusCode() >= 400) {
                    throw new IOException("HTTP " + response.statusCode() + " from " + request.uri().getHost());
                }
                open.add(in);
                for (int n; !stop.stopped && (n = in.read(buffer)) != -1; ) {
                    firstByte.compareAndSet(0, System.nanoTime());
                    bytes.add(n);
                }
            } catch (IOException e) {
                if (stop.stopped || response.statusCode() >= 400) throw e;
                failure.set(e); // the connection broke mid-transfer: start a new one
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
