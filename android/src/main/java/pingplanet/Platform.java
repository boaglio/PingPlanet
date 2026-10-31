package pingplanet;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pingplanet.app.BuildConfig;

/** Android's side of what the shared test code needs: plain threads and HttpURLConnection. */
final class Platform {

    private Platform() {
    }

    static String version() {
        return BuildConfig.VERSION_NAME;
    }

    /** Runs many short, mostly waiting tasks at once. */
    static ExecutorService probes() {
        return Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts a thread that moves data; it never keeps the program from exiting. */
    static Thread worker(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static boolean timedOut(Exception e) {
        return e instanceof java.net.SocketTimeoutException;
    }

    /** {@code source} is ignored: the app always tests the connection Android is using. */
    static Fetcher fetcher(URI url, Duration timeout, InetAddress source) {
        Set<HttpURLConnection> open = ConcurrentHashMap.newKeySet();
        return new Fetcher() {
            @Override
            public Response get() throws IOException {
                HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
                open.add(connection);
                try {
                    connection.setConnectTimeout((int) timeout.toMillis());
                    connection.setReadTimeout((int) timeout.toMillis());
                    connection.setRequestProperty("User-Agent", "PingPlanet");
                    connection.setRequestProperty("Cache-Control", "no-cache");
                    connection.setRequestProperty("Accept-Encoding", "identity"); // or Android asks for gzip and unpacks it
                    int status = connection.getResponseCode();
                    InputStream body = status >= 400 ? new ByteArrayInputStream(new byte[0]) : connection.getInputStream();
                    return new Response(status, new FilterInputStream(body) {
                        @Override
                        public void close() {
                            open.remove(connection);
                            connection.disconnect(); // also unblocks a read on another thread
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    open.remove(connection);
                    connection.disconnect();
                    throw e;
                }
            }

            @Override
            public void close() {
                for (HttpURLConnection connection : open) connection.disconnect();
            }
        };
    }
}
