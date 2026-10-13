package pingplanet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Local network test between two PingPlanet instances: one runs {@link #serve},
 * the other connects with {@link #latency}, {@link #download} and {@link #upload}.
 *
 * <p>Protocol: the client opens a TCP connection and sends one command line.
 * <ul>
 *   <li>{@code PING} - the server echoes every byte back, for round-trip times</li>
 *   <li>{@code DOWN} - the server sends data until the client closes</li>
 *   <li>{@code UP} - the server reads and discards data until the client closes</li>
 * </ul>
 */
final class Lan {

    static final int DEFAULT_PORT = 7357;
    private static final String HELLO = "PINGPLANET 1";
    private static final int BUFFER = 128 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int WARM_UP_PINGS = 5;

    private Lan() {
    }

    // ---- server ------------------------------------------------------------

    /** Accepts test connections until the process is stopped. */
    static void serve(int port, Consumer<String> onTest) throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            serve(server, onTest);
        }
    }

    /** Serves until the socket is closed. {@code onTest} gets the client address when a test starts. */
    static void serve(ServerSocket server, Consumer<String> onTest) throws IOException {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (server.isClosed()) return;
                throw e;
            }
            // Platform threads: a virtual thread waking from a socket read can add a millisecond, which would
            // swamp LAN round trips that are often well under that.
            Thread.ofPlatform().daemon().name("lan-" + socket.getRemoteSocketAddress()).start(() -> handle(socket, onTest));
        }
    }

    private static void handle(Socket socket, Consumer<String> onTest) {
        try (socket) {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String command = readLine(in);
            out.write((HELLO + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String peer = socket.getInetAddress().getHostAddress();
            switch (command) {
                case "PING" -> { // every client run starts with the latency test
                    onTest.accept(peer);
                    echo(in, out);
                }
                case "DOWN" -> {
                    byte[] data = new byte[BUFFER];
                    while (true) out.write(data); // ends when the client closes the connection
                }
                case "UP" -> {
                    byte[] data = new byte[BUFFER];
                    while (in.read(data) != -1) {
                        // discard
                    }
                }
                default -> {
                    // not a PingPlanet client; just hang up
                }
            }
        } catch (IOException e) {
            // the client closing mid-transfer is how every test ends
        }
    }

    private static void echo(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64];
        for (int n; (n = in.read(buf)) != -1; ) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    // ---- client ------------------------------------------------------------

    static Latency latency(String host, int port, int samples) throws IOException {
        return latency(host, port, samples, Progress.NONE, null);
    }

    /** Progress reports how many samples are done. */
    static Latency latency(String host, int port, int samples, Progress progress, InetAddress source) throws IOException {
        long begin = System.nanoTime();
        try (Socket socket = open(host, port, "PING", source)) {
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            for (int i = 0; i < WARM_UP_PINGS; i++) { // lets the JIT compile the path before timing it
                out.write('p');
                out.flush();
                if (in.read() == -1) throw new IOException("server closed the connection");
            }
            List<Double> times = new ArrayList<>();
            int lost = 0;
            for (int i = 0; i < samples; i++) {
                long start = System.nanoTime();
                out.write('p');
                out.flush();
                try {
                    if (in.read() == -1) throw new IOException("server closed the connection");
                    times.add((System.nanoTime() - start) / 1e6);
                } catch (SocketTimeoutException e) {
                    lost++;
                }
                progress.update(i + 1, System.nanoTime() - begin);
                InternetTest.pause(50);
            }
            return new Latency(times, lost);
        }
    }

    static Throughput download(String host, int port, int streams, Duration duration) throws IOException {
        return transfer(host, port, "DOWN", streams, duration, Progress.NONE, null);
    }

    static Throughput download(String host, int port, int streams, Duration duration, Progress progress,
                               InetAddress source) throws IOException {
        return transfer(host, port, "DOWN", streams, duration, progress, source);
    }

    static Throughput upload(String host, int port, int streams, Duration duration) throws IOException {
        return transfer(host, port, "UP", streams, duration, Progress.NONE, null);
    }

    static Throughput upload(String host, int port, int streams, Duration duration, Progress progress,
                             InetAddress source) throws IOException {
        return transfer(host, port, "UP", streams, duration, progress, source);
    }

    private static Throughput transfer(String host, int port, String command, int streams, Duration duration,
                                       Progress progress, InetAddress source) throws IOException {
        // Connect all streams first so the timed window only covers data transfer.
        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < streams; i++) sockets.add(open(host, port, command, source));
        } catch (IOException e) {
            closeAll(sockets);
            throw e;
        }

        boolean down = command.equals("DOWN");
        LongAdder bytes = new LongAdder();
        AtomicReference<IOException> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        long start = System.nanoTime();
        long deadline = start + duration.toNanos();
        for (Socket socket : sockets) {
            threads.add(Thread.ofVirtual().start(() -> {
                byte[] buf = new byte[BUFFER];
                try {
                    if (down) {
                        InputStream in = socket.getInputStream();
                        for (int n; System.nanoTime() < deadline && (n = in.read(buf)) != -1; ) bytes.add(n);
                    } else {
                        OutputStream out = socket.getOutputStream();
                        while (System.nanoTime() < deadline) {
                            out.write(buf);
                            bytes.add(buf.length);
                        }
                    }
                } catch (IOException e) {
                    if (System.nanoTime() < deadline) failure.compareAndSet(null, e);
                }
            }));
        }
        long giveUp = deadline + CONNECT_TIMEOUT_MS * 1_000_000L;
        long lastUpdate = 0;
        while (threads.stream().anyMatch(Thread::isAlive) && System.nanoTime() < giveUp && !InternetTest.stopRequested()) {
            long now = System.nanoTime();
            if (now < deadline && now - lastUpdate > Progress.INTERVAL_NANOS) {
                progress.update(bytes.sum(), now - start);
                lastUpdate = now;
            }
            InternetTest.pause(10);
        }
        long elapsed = Math.min(System.nanoTime(), deadline) - start;
        closeAll(sockets);
        if (failure.get() != null && bytes.sum() == 0) throw failure.get();
        return new Throughput(bytes.sum(), elapsed);
    }

    /** Connects, sends the command and checks the server answers as a PingPlanet server. */
    private static Socket open(String host, int port, String command, InetAddress source) throws IOException {
        Socket socket = new Socket();
        try {
            if (source != null) socket.bind(new InetSocketAddress(source, 0));
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String hello = readLine(socket.getInputStream());
            if (!hello.equals(HELLO)) {
                throw new IOException(host + ":" + port + " is not a PingPlanet server (got \"" + hello + "\")");
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** Reads one short ASCII line byte by byte, so nothing after it is buffered away. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c == -1) throw new IOException("connection closed");
            if (sb.length() > 64) throw new IOException("unexpected data");
            sb.append((char) c);
        }
        return sb.toString().strip();
    }

    private static void closeAll(List<Socket> sockets) {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
