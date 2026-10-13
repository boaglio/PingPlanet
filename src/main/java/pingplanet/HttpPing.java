package pingplanet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Latency the way browser speed tests measure it: the time from sending a tiny
 * HTTP request on an already open connection to the first byte of the answer.
 *
 * <p>Connecting (TCP and TLS) is never timed. Busy speed-test servers often drop
 * new connections and the client retries after 1, 2, 4 seconds, which would show
 * up as seconds of "latency" that has nothing to do with the network.
 */
final class HttpPing implements AutoCloseable {

    /** Bodies up to this size are read so the connection can be reused. */
    private static final int MAX_DRAIN = 64 * 1024;

    private final URI url;
    private final int timeoutMs;
    private final byte[] request;
    private Socket socket;

    HttpPing(URI url, int timeoutMs) {
        this.url = url;
        this.timeoutMs = timeoutMs;
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        if (url.getRawQuery() != null) path += "?" + url.getRawQuery();
        String host = url.getPort() == -1 ? url.getHost() : url.getHost() + ":" + url.getPort();
        // Ask for one byte; servers that ignore Range get their connection closed after the headers.
        this.request = ("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: PingPlanet\r\n"
                + "Range: bytes=0-0\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** One timed round trip in milliseconds. Opens (or reopens) the connection first, untimed. */
    double ping() throws IOException {
        if (socket == null || socket.isClosed()) open();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        long start = System.nanoTime();
        out.write(request);
        out.flush();
        int first = in.read();
        long elapsed = System.nanoTime() - start;
        if (first == -1) {
            close();
            throw new IOException("connection closed by " + url.getHost());
        }
        finishResponse(first, in);
        return elapsed / 1e6;
    }

    private void open() throws IOException {
        Socket plain = new Socket();
        try {
            int port = url.getPort() != -1 ? url.getPort() : "http".equalsIgnoreCase(url.getScheme()) ? 80 : 443;
            plain.connect(new InetSocketAddress(url.getHost(), port), timeoutMs);
            plain.setTcpNoDelay(true);
            plain.setSoTimeout(timeoutMs);
            if ("https".equalsIgnoreCase(url.getScheme())) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, url.getHost(), port, true);
                SSLParameters params = tls.getSSLParameters();
                params.setServerNames(java.util.List.of(new SNIHostName(url.getHost())));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(params);
                tls.startHandshake();
                socket = tls;
            } else {
                socket = plain;
            }
        } catch (IOException e) {
            plain.close();
            throw e;
        }
    }

    /** Reads the rest of the headers and a small body, or closes the connection if the body is big. */
    private void finishResponse(int first, InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.write(first);
        int matched = 0; // progress through "\r\n\r\n"
        byte[] end = {'\r', '\n', '\r', '\n'};
        if (first == end[0]) matched = 1;
        while (matched < 4) {
            int b = in.read();
            if (b == -1) {
                close();
                return;
            }
            head.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            if (head.size() > 32 * 1024) { // not HTTP as we know it
                close();
                return;
            }
        }
        long length = -1;
        boolean keepAlive = true;
        for (String line : head.toString(StandardCharsets.ISO_8859_1).split("\r\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try {
                    length = Long.parseLong(line.substring(15).strip());
                } catch (NumberFormatException e) {
                    length = -1;
                }
            } else if (lower.startsWith("transfer-encoding:") || (lower.startsWith("connection:") && lower.contains("close"))) {
                keepAlive = false; // chunked bodies and closing servers: just reconnect next time
            }
        }
        if (!keepAlive || length < 0 || length > MAX_DRAIN) {
            close();
            return;
        }
        long left = length;
        byte[] buf = new byte[(int) Math.min(Math.max(length, 1), 8192)];
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n == -1) {
                close();
                return;
            }
            left -= n;
        }
    }

    @Override
    public void close() {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        socket = null;
    }
}
