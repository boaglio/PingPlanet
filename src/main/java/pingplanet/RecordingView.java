package pingplanet;

import java.util.ArrayList;
import java.util.List;

/** Passes everything on to another {@link View}, and keeps the warnings for a report. */
final class RecordingView implements View {

    record Warning(String message, List<String> commands) {
    }

    private final View view;
    private final List<Warning> warnings = new ArrayList<>();

    RecordingView(View view) {
        this.view = view;
    }

    /** Each different warning once, in the order they came. */
    List<Warning> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public void warning(String message, List<String> commands) {
        if (warnings.stream().noneMatch(w -> w.message().equals(message))) warnings.add(new Warning(message, List.copyOf(commands)));
        view.warning(message, commands);
    }

    // ---- everything else is passed on --------------------------------------

    @Override
    public void worldPlan(int connections, int servers, boolean downloads, int seconds) {
        view.worldPlan(connections, servers, downloads, seconds);
    }

    @Override
    public void lanPlan(int connections, int seconds) {
        view.lanPlan(connections, seconds);
    }

    @Override
    public void worldStart(int servers, boolean downloads, int seconds, int streams, Net.Source source) {
        view.worldStart(servers, downloads, seconds, streams, source);
    }

    @Override
    public Progress latencyProgress(int servers) {
        return view.latencyProgress(servers);
    }

    @Override
    public void latencyDone(int servers, long nanos) {
        view.latencyDone(servers, nanos);
    }

    @Override
    public Progress downloading(Server server, Latency latency) {
        return view.downloading(server, latency);
    }

    @Override
    public void row(Report.ServerResult result) {
        view.row(result);
    }

    @Override
    public void worldEnd(List<Report.ServerResult> results, long nanos) {
        view.worldEnd(results, nanos);
    }

    @Override
    public void lanStart(String host, int port, Net.Source source) {
        view.lanStart(host, port, source);
    }

    @Override
    public Progress lanLatencyProgress(int samples) {
        return view.lanLatencyProgress(samples);
    }

    @Override
    public void lanLatency(Latency latency) {
        view.lanLatency(latency);
    }

    @Override
    public Progress lanTransferProgress(String label, int seconds) {
        return view.lanTransferProgress(label, seconds);
    }

    @Override
    public void lanTransfer(String label, Throughput throughput) {
        view.lanTransfer(label, throughput);
    }

    @Override
    public void lanEnd(Report.LanResult result) {
        view.lanEnd(result);
    }

    @Override
    public void lanError(String message) {
        view.lanError(message);
    }

    @Override
    public void lanCompare(List<Report.LanRun> runs) {
        view.lanCompare(runs);
    }

    @Override
    public void connections(List<Net.Source> sources, String defaultName) {
        view.connections(sources, defaultName);
    }

    @Override
    public void compare(List<Report.Run> runs) {
        view.compare(runs);
    }

    @Override
    public void interfaces(List<Net.Source> sources, String defaultName) {
        view.interfaces(sources, defaultName);
    }

    @Override
    public void serveStart(int port, List<String> addresses) {
        view.serveStart(port, addresses);
    }

    @Override
    public void serveTest(String peer) {
        view.serveTest(peer);
    }

    @Override
    public void list(List<Server> servers) {
        view.list(servers);
    }
}
