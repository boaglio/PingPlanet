package pingplanet;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the shared test code needs and Android does not have: virtual threads and
 * java.net.http. The Android app has its own class with this name, in android/src.
 */
final class Platform {

    private Platform() {
    }

    static String version() {
        String v = Platform.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    /** Runs many short, mostly waiting tasks at once. */
    static ExecutorService probes() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Starts a thread that moves data; it never keeps the program from exiting. */
    static Thread worker(String name, Runnable task) {
        return Thread.ofVirtual().name(name).start(task);
    }

    static boolean timedOut(Exception e) {
        return e instanceof HttpConnectTimeoutException || e instanceof java.net.SocketTimeoutException;
    }

    /** {@code source} is the local address to send from, or null to let the system choose. */
    static Fetcher fetcher(URI url, Duration timeout, InetAddress source) {
        // HTTP/1.1 so each stream gets its own TCP connection (HTTP/2 would multiplex them on one).
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (source != null) builder.localAddress(source);
        HttpClient client = builder.build();
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("User-Agent", "PingPlanet")
                .header("Cache-Control", "no-cache")
                .build();
        return new Fetcher() {
            @Override
            public Response get() throws IOException, InterruptedException {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                return new Response(response.statusCode(), response.body());
            }

            @Override
            public void close() {
                client.shutdownNow();
            }
        };
    }
}
