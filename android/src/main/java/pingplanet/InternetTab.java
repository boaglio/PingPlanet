package pingplanet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

/** The internet test: a world map, the servers' results, and a report to share. */
final class InternetTab extends LinearLayout {

    private static final int SECONDS = 5;
    private static final int STREAMS = 4;
    /** The latency pass, in the units of {@link Overall}: about as long as this many seconds. */
    private static final double LATENCY_UNITS = 3;

    private final Activity activity;
    private final List<Server> servers = Server.builtIn();
    private final MapView map;
    private final CheckBox downloads;
    private final Button start;
    private final Button share;
    private final ProgressBar bar;
    private final TextView status;
    private final LinearLayout rows;
    private Thread worker;
    private String report;

    InternetTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        map = new MapView(activity);
        map.reset(servers);
        addView(map, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        downloads = new CheckBox(activity);
        downloads.setText("Test download speed");
        downloads.setChecked(!metered());
        addView(downloads);

        LinearLayout buttons = new LinearLayout(activity);
        start = Ui.button(activity, "Start test", v -> toggle());
        share = Ui.button(activity, "Share report", v -> Ui.share(activity, "PingPlanet report", report));
        share.setEnabled(false);
        buttons.addView(start, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(share, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(buttons);

        bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        status = Ui.caption(activity, servers.size() + " servers. Downloads can use a few hundred MB each on a fast link.");
        addView(status);

        rows = new LinearLayout(activity);
        rows.setOrientation(VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
    }

    private boolean metered() {
        return activity.getSystemService(ConnectivityManager.class).isActiveNetworkMetered();
    }

    private void toggle() {
        if (worker != null) {
            stop();
        } else if (downloads.isChecked() && metered()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Use mobile data?")
                    .setMessage("This connection is metered. Testing downloads from " + servers.size()
                            + " servers can use several GB. To use almost nothing, turn off \"Test download speed\".")
                    .setPositiveButton("Test anyway", (d, w) -> run())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            run();
        }
    }

    void stop() {
        if (worker != null) worker.interrupt();
    }

    private void run() {
        Engine.WorldPlan plan = new Engine.WorldPlan(servers, Collections.singletonList(null), downloads.isChecked(), SECONDS, STREAMS);
        RecordingView view = new RecordingView(new TabView(plan));
        rows.removeAllViews();
        map.reset(servers);
        report = null;
        share.setEnabled(false);
        bar.setProgress(0);
        setRunning(true);
        worker = Platform.worker("pingplanet-world", () -> {
            String outcome;
            String done = null;
            try {
                List<Report.Run> runs = Engine.world(plan, view);
                done = Markdown.world(plan, runs, view.warnings(), LocalDateTime.now());
                outcome = "Done: " + runs.get(0).reached() + " of " + servers.size() + " servers reached";
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
                map.active(null);
                status.setText(message);
                if (finished != null) bar.setProgress(bar.getMax());
                setRunning(false);
            });
        });
    }

    private void setRunning(boolean running) {
        start.setText(running ? "Stop" : "Start test");
        downloads.setEnabled(!running);
        Ui.keepScreenOn(activity, running);
    }

    /** Called on the test's thread: everything that touches the screen is posted. */
    private final class TabView extends ViewAdapter {

        private final Engine.WorldPlan plan;
        private final Overall overall;

        TabView(Engine.WorldPlan plan) {
            this.plan = plan;
            double units = LATENCY_UNITS + (plan.downloads() ? plan.servers().size() * (double) plan.seconds() : 0);
            overall = new Overall(units, System.nanoTime());
        }

        private void show(String text) {
            int progress = (int) (overall.fraction() * bar.getMax());
            String left = overall.remainingNanos(System.nanoTime()).stream()
                    .mapToObj(n -> " · " + Overall.remainingText(n)).findFirst().orElse("");
            post(() -> {
                bar.setProgress(progress);
                status.setText(text + left);
            });
        }

        @Override
        public Progress latencyProgress(int count) {
            return (done, nanos) -> {
                overall.within(LATENCY_UNITS * done / count);
                show("Measuring latency: " + done + " of " + count);
            };
        }

        @Override
        public void latencyDone(int count, long nanos) {
            overall.step(LATENCY_UNITS);
        }

        @Override
        public Progress downloading(Server server, Latency latency) {
            post(() -> {
                map.measured(server, latency.reachable() ? latency.medianMs() : -1);
                map.active(server);
            });
            return (bytes, nanos) -> {
                overall.within(nanos / 1e9);
                show(nanos == 0 ? server.label() + ": connecting"
                        : server.label() + ": " + Report.mbps(new Throughput(bytes, nanos).mbps()));
            };
        }

        @Override
        public void row(Report.ServerResult r) {
            if (plan.downloads()) overall.step(plan.seconds());
            Context context = getContext();
            Latency l = r.latency();
            boolean up = l != null && l.reachable();
            String value = r.download() != null ? Report.mbps(r.download().mbps())
                    : !plan.downloads() && up ? "" : r.error() != null ? r.error() : "failed";
            String label = r.server().label() + (up ? " · " + Report.ms(l.medianMs()) : "");
            post(() -> {
                map.measured(r.server(), up ? l.medianMs() : -1);
                View row = Ui.row(context, label, value, r.download() == null && r.error() != null ? Ui.RED
                        : up ? Ui.latencyColor(l.medianMs()) : 0);
                rows.addView(row);
            });
        }
    }
}
