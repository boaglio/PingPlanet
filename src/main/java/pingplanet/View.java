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

    void worldStart(int servers, boolean downloads, int seconds, int streams);

    /** Progress counts servers whose latency is done. */
    Progress latencyProgress(int servers);

    void latencyDone(int servers, long nanos);

    /** Progress counts bytes downloaded from this server so far. */
    Progress downloading(Server server, Latency latency);

    void row(Report.ServerResult result);

    void worldEnd(List<Report.ServerResult> results, long nanos);

    // ---- lan ---------------------------------------------------------------

    void lanStart(String host, int port);

    /** Progress counts latency samples done. */
    Progress lanLatencyProgress(int samples);

    void lanLatency(Latency latency);

    /** Progress counts bytes moved; {@code label} is "Download" or "Upload". */
    Progress lanTransferProgress(String label, int seconds);

    void lanTransfer(String label, Throughput throughput);

    void lanEnd(Report.LanResult result);

    // ---- serve and list ----------------------------------------------------

    void serveStart(int port, List<String> addresses);

    void serveTest(String peer);

    void list(List<Server> servers);
}
