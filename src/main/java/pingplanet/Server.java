package pingplanet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A public internet test server: a large file to download, hosted in one country.
 * {@code lat} and {@code lon} place it on the world map; they are NaN when unknown.
 */
record Server(String code, String country, String city, String provider, double lat, double lon, URI url) {

    static final String HEADER = "code,country,city,provider,lat,lon,url";
    private static final String BUILT_IN = "/pingplanet/servers.csv";

    String host() {
        return url.getHost();
    }

    /** "City, Country", or just the country when there is no city. */
    String label() {
        return city.isEmpty() ? country : city + ", " + country;
    }

    boolean located() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    int port() {
        if (url.getPort() != -1) return url.getPort();
        return "http".equalsIgnoreCase(url.getScheme()) ? 80 : 443;
    }

    /** The server list shipped inside the jar. */
    static List<Server> builtIn() {
        try (InputStream in = Server.class.getResourceAsStream(BUILT_IN)) {
            if (in == null) throw new IllegalStateException(BUILT_IN + " is missing from the jar");
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Server> load(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    /**
     * Reads "code,country,city,provider,lat,lon,url" lines. Blank lines, # comments and the
     * header line are skipped. The url goes last so it may contain commas.
     */
    static List<Server> parse(BufferedReader reader) throws IOException {
        List<Server> servers = new ArrayList<>();
        int lineNo = 0;
        for (String line; (line = reader.readLine()) != null; ) {
            lineNo++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("code,")) continue;
            String[] f = line.split(",", 7);
            if (f.length < 7) throw new IOException("line " + lineNo + ": expected " + HEADER);
            URI url;
            try {
                url = URI.create(f[6].strip());
            } catch (IllegalArgumentException e) {
                throw new IOException("line " + lineNo + ": bad url: " + f[6].strip());
            }
            if (url.getHost() == null || !(url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https"))) {
                throw new IOException("line " + lineNo + ": url must be http or https: " + url);
            }
            double lat = coordinate(f[4], 90, lineNo);
            double lon = coordinate(f[5], 180, lineNo);
            servers.add(new Server(f[0].strip().toUpperCase(Locale.ROOT), f[1].strip(), f[2].strip(), f[3].strip(),
                    lat, lon, url));
        }
        return servers;
    }

    private static double coordinate(String text, double limit, int lineNo) throws IOException {
        if (text.isBlank()) return Double.NaN;
        try {
            double value = Double.parseDouble(text.strip());
            if (Math.abs(value) > limit) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IOException("line " + lineNo + ": bad coordinate: " + text.strip());
        }
    }

    /** One CSV line, in the same format {@link #parse} reads. */
    String toCsv() {
        return String.join(",", code, country, city, provider, located() ? fmt(lat) : "", located() ? fmt(lon) : "",
                url.toString());
    }

    private static String fmt(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Keeps the servers whose country code is in {@code codes} (all of them when it is empty). */
    static List<Server> filter(List<Server> servers, Set<String> codes) {
        if (codes.isEmpty()) return servers;
        return servers.stream().filter(s -> codes.contains(s.code())).toList();
    }
}
