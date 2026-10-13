package pingplanet;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Result records, JSON output and the number formats shared by the views. */
final class Report {

    /** One internet server's results. Fields are null when that part was skipped or failed. */
    record ServerResult(Server server, Latency latency, Throughput download, String error) {
    }

    record LanResult(String host, int port, Latency latency, Throughput download, Throughput upload) {
    }

    private Report() {
    }

    static Optional<ServerResult> lowestLatency(List<ServerResult> results) {
        return results.stream()
                .filter(r -> r.latency() != null && r.latency().reachable())
                .min(Comparator.comparingDouble(r -> r.latency().medianMs()));
    }

    static Optional<ServerResult> fastest(List<ServerResult> results) {
        return results.stream()
                .filter(r -> r.download() != null)
                .max(Comparator.comparingDouble(r -> r.download().mbps()));
    }

    // ---- json --------------------------------------------------------------

    static void json(PrintStream out, List<ServerResult> results) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < results.size(); i++) {
            ServerResult r = results.get(i);
            Server s = r.server();
            sb.append("  {");
            field(sb, "code", s.code()).append(", ");
            field(sb, "country", s.country()).append(", ");
            field(sb, "city", s.city()).append(", ");
            field(sb, "provider", s.provider()).append(", ");
            field(sb, "url", s.url().toString()).append(", ");
            latencyFields(sb, r.latency()).append(", ");
            field(sb, "downloadMbps", r.download() == null ? null : r.download().mbps()).append(", ");
            field(sb, "error", r.error());
            sb.append(i < results.size() - 1 ? "},\n" : "}\n");
        }
        out.print(sb.append("]\n"));
    }

    // ---- lan ---------------------------------------------------------------

    static void lanJson(PrintStream out, LanResult r) {
        StringBuilder sb = new StringBuilder("{");
        field(sb, "host", r.host()).append(", ");
        sb.append("\"port\": ").append(r.port()).append(", ");
        latencyFields(sb, r.latency()).append(", ");
        field(sb, "lossPercent", r.latency().lossPercent()).append(", ");
        field(sb, "downloadMbps", r.download().mbps()).append(", ");
        field(sb, "uploadMbps", r.upload().mbps());
        out.println(sb.append("}"));
    }

    // ---- helpers -----------------------------------------------------------

    static String ms(double value) {
        return value < 10 ? String.format(Locale.ROOT, "%.1f ms", value) : String.format(Locale.ROOT, "%.0f ms", value);
    }

    static String mbps(double value) {
        return value >= 1000 ? String.format(Locale.ROOT, "%.2f Gbps", value / 1000)
                : String.format(Locale.ROOT, "%.1f Mbps", value);
    }

    static String bytes(long bytes) {
        if (bytes >= 1_000_000_000) return String.format(Locale.ROOT, "%.2f GB", bytes / 1e9);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
    }

    static String duration(long nanos) {
        long seconds = Math.round(nanos / 1e9);
        if (seconds < 60) return String.format(Locale.ROOT, "%.1f s", nanos / 1e9);
        return seconds / 60 + "m " + seconds % 60 + "s";
    }

    private static StringBuilder latencyFields(StringBuilder sb, Latency l) {
        boolean up = l != null && l.reachable();
        field(sb, "latencyMs", up ? l.medianMs() : null).append(", ");
        return field(sb, "jitterMs", up ? l.jitterMs() : null);
    }

    private static StringBuilder field(StringBuilder sb, String name, Double value) {
        sb.append('"').append(name).append("\": ");
        return value == null ? sb.append("null") : sb.append(String.format(Locale.ROOT, "%.2f", value));
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\": ");
        if (value == null) return sb.append("null");
        sb.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"');
    }
}
