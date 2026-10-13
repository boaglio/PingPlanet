package pingplanet;

import java.util.List;

/** A {@link View} that ignores everything; GUI panels override the events they show. */
class ViewAdapter implements View {

    @Override
    public void worldPlan(int connections, int servers, boolean downloads, int seconds) {
    }

    @Override
    public void lanPlan(int connections, int seconds) {
    }

    @Override
    public void worldStart(int servers, boolean downloads, int seconds, int streams, Net.Source source) {
    }

    @Override
    public Progress latencyProgress(int servers) {
        return Progress.NONE;
    }

    @Override
    public void latencyDone(int servers, long nanos) {
    }

    @Override
    public Progress downloading(Server server, Latency latency) {
        return Progress.NONE;
    }

    @Override
    public void row(Report.ServerResult result) {
    }

    @Override
    public void worldEnd(List<Report.ServerResult> results, long nanos) {
    }

    @Override
    public void lanStart(String host, int port, Net.Source source) {
    }

    @Override
    public Progress lanLatencyProgress(int samples) {
        return Progress.NONE;
    }

    @Override
    public void lanLatency(Latency latency) {
    }

    @Override
    public Progress lanTransferProgress(String label, int seconds) {
        return Progress.NONE;
    }

    @Override
    public void lanTransfer(String label, Throughput throughput) {
    }

    @Override
    public void lanEnd(Report.LanResult result) {
    }

    @Override
    public void lanError(String message) {
    }

    @Override
    public void lanCompare(List<Report.LanRun> runs) {
    }

    @Override
    public void connections(List<Net.Source> sources, String defaultName) {
    }

    @Override
    public void compare(List<Report.Run> runs) {
    }

    @Override
    public void warning(String message, List<String> commands) {
    }

    @Override
    public void interfaces(List<Net.Source> sources, String defaultName) {
    }

    @Override
    public void serveStart(int port, List<String> addresses) {
    }

    @Override
    public void serveTest(String peer) {
    }

    @Override
    public void list(List<Server> servers) {
    }
}
