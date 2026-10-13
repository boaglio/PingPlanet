package pingplanet;

import java.util.List;

/**
 * Everything PingPlanet shows while it runs. {@link FancyView} animates in an
 * interactive terminal; {@link PlainView} prints simple lines for pipes and logs.
 */
interface View {

    static View create(java.io.PrintStream out, Term term) {
        return term.fancy ? new FancyView(out, term) : new PlainView(out);
    }

    // ---- world -------------------------------------------------------------

    /** {@code source} is the interface the test goes out through, or null for the system default. */
    void worldStart(int servers, boolean downloads, int seconds, int streams, Net.Source source);

    /** Progress counts servers whose latency is done. */
    Progress latencyProgress(int servers);

    void latencyDone(int servers, long nanos);

    /** Progress counts bytes downloaded from this server so far. */
    Progress downloading(Server server, Latency latency);

    void row(Report.ServerResult result);

    void worldEnd(List<Report.ServerResult> results, long nanos);

    // ---- lan ---------------------------------------------------------------

    void lanStart(String host, int port, Net.Source source);

    /** Progress counts latency samples done. */
    Progress lanLatencyProgress(int samples);

    void lanLatency(Latency latency);

    /** Progress counts bytes moved; {@code label} is "Download" or "Upload". */
    Progress lanTransferProgress(String label, int seconds);

    void lanTransfer(String label, Throughput throughput);

    void lanEnd(Report.LanResult result);

    /** The LAN test through one interface failed; the next one still runs. */
    void lanError(String message);

    /** Side by side LAN results when several interfaces were tested. */
    void lanCompare(List<Report.LanRun> runs);

    /** Before the first run: every connection that will be tested; {@code defaultName} may be null. */
    void connections(List<Net.Source> sources, String defaultName);

    /** Side by side results when the same test ran through several interfaces. */
    void compare(List<Report.Run> runs);

    // ---- other -------------------------------------------------------------

    /** Something the user should fix for accurate results, with commands to run. */
    void warning(String message, List<String> commands);

    /** The interfaces "--interface" accepts; {@code defaultName} carries the default route, or null. */
    void interfaces(List<Net.Source> sources, String defaultName);

    void serveStart(int port, List<String> addresses);

    void serveTest(String peer);

    void list(List<Server> servers);
}
