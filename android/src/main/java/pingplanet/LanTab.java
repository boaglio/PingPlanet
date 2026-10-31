package pingplanet;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

/** The LAN test against "pingplanet serve" on another machine, or the Server tab on another phone. */
final class LanTab extends LinearLayout {

    private static final int SECONDS = 5;
    private static final int STREAMS = 4;
    private static final String LAST_HOST = "lanHost";

    private final Activity activity;
    private final SharedPreferences preferences;
    private final EditText host;
    private final EditText port;
    private final Button start;
    private final Button share;
    private final ProgressBar bar;
    private final TextView status;
    private final LinearLayout rows;
    private Thread worker;
    private String report;

    LanTab(Activity activity) {
        super(activity);
        this.activity = activity;
        preferences = activity.getPreferences(Activity.MODE_PRIVATE);
        setOrientation(VERTICAL);
        addView(Ui.caption(activity, "Start a server on another machine with \"pingplanet serve\" or on another phone's Server tab, then enter its address."));

        LinearLayout target = new LinearLayout(activity);
        host = Ui.field(activity, "Address, like 192.168.0.10", false);
        host.setText(preferences.getString(LAST_HOST, ""));
        port = Ui.field(activity, "Port", true);
        port.setText(String.valueOf(Lan.DEFAULT_PORT));
        target.addView(host, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 3));
        target.addView(port, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(target);

        LinearLayout buttons = new LinearLayout(activity);
        start = Ui.button(activity, "Start test", v -> toggle());
        share = Ui.button(activity, "Share report", v -> Ui.share(activity, "PingPlanet LAN report", report));
        share.setEnabled(false);
        buttons.addView(start, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(share, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(buttons);

        bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        status = Ui.caption(activity, "");
        addView(status);

        rows = new LinearLayout(activity);
        rows.setOrientation(VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void toggle() {
        if (worker != null) {
            stop();
            return;
        }
        String target = host.getText().toString().strip();
        int number = MainActivity.parsePort(port.getText().toString());
        if (target.isEmpty() || number < 0) {
            status.setText(target.isEmpty() ? "Enter the server's address" : "Port must be between 1 and 65535");
            return;
        }
        preferences.edit().putString(LAST_HOST, target).apply();
        run(new Engine.LanPlan(target, number, Collections.singletonList(null), SECONDS, STREAMS));
    }

    void stop() {
        if (worker != null) worker.interrupt();
    }

    private void run(Engine.LanPlan plan) {
        RecordingView view = new RecordingView(new TabView(plan));
        rows.removeAllViews();
        report = null;
        share.setEnabled(false);
        bar.setProgress(0);
        setRunning(true);
        worker = Platform.worker("pingplanet-lan", () -> {
            String outcome;
            String done = null;
            try {
                List<Report.LanRun> runs = Engine.lan(plan, view);
                Report.LanRun run = runs.get(0);
                if (run.result() != null) done = Markdown.lan(plan, runs, view.warnings(), LocalDateTime.now());
                outcome = run.result() != null ? "Done" : "Failed: " + run.error();
            } catch (CancellationException e) {
                outcome = "Stopped";
            } catch (RuntimeException e) {
                outcome = "Failed: " + e.getMessage();
            }
            String message = outcome;
            String finished = done;
            post(() -> {
                worker = null;
                report = finished;
                share.setEnabled(finished != null);
                status.setText(message);
                if (finished != null) bar.setProgress(bar.getMax());
                setRunning(false);
            });
        });
    }

    private void setRunning(boolean running) {
        start.setText(running ? "Stop" : "Start test");
        host.setEnabled(!running);
        port.setEnabled(!running);
        Ui.keepScreenOn(activity, running);
    }

    /** Called on the test's thread: everything that touches the screen is posted. */
    private final class TabView extends ViewAdapter {

        private final Overall overall;

        TabView(Engine.LanPlan plan) {
            overall = new Overall(1 + 2.0 * plan.seconds(), System.nanoTime());
        }

        private void show(String text) {
            int progress = (int) (overall.fraction() * bar.getMax());
            post(() -> {
                bar.setProgress(progress);
                status.setText(text);
            });
        }

        @Override
        public Progress lanLatencyProgress(int samples) {
            return (done, nanos) -> {
                overall.within((double) done / samples);
                show("Measuring latency: " + done + " of " + samples);
            };
        }

        @Override
        public void lanLatency(Latency latency) {
            overall.step(1);
            String value = latency.reachable() ? Report.ms(latency.medianMs()) : "no answer";
            int color = latency.reachable() ? Ui.latencyColor(latency.medianMs()) : Ui.RED;
            String jitter = latency.reachable() ? " · jitter " + Report.ms(latency.jitterMs()) : "";
            post(() -> rows.addView(Ui.row(getContext(), "Latency" + jitter, value, color)));
        }

        @Override
        public Progress lanTransferProgress(String label, int seconds) {
            return (bytes, nanos) -> {
                overall.within(nanos / 1e9);
                show(label + ": " + Report.mbps(new Throughput(bytes, nanos).mbps()));
            };
        }

        @Override
        public void lanTransfer(String label, Throughput throughput) {
            overall.step(SECONDS);
            post(() -> rows.addView(Ui.row(getContext(), label, Report.mbps(throughput.mbps()), Ui.SKY)));
        }
    }
}
