package pingplanet;

import android.app.Activity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Waits for LAN tests from other machines, until stopped or the app closes. */
final class ServerTab extends LinearLayout {

    private final Activity activity;
    private final EditText port;
    private final Button toggle;
    private final TextView state;
    private final TextView commands;
    private ServerSocket socket;

    ServerTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        addView(Ui.caption(activity, "Lets another machine on this network test its speed to this phone. Keep the app open while it runs."));
        port = Ui.field(activity, "Port", true);
        port.setText(String.valueOf(Lan.DEFAULT_PORT));
        addView(port);
        toggle = Ui.button(activity, "Start server", v -> toggle());
        addView(toggle);
        state = Ui.text(activity, "Stopped", 16);
        addView(state);
        commands = Ui.text(activity, "", 14);
        commands.setTextIsSelectable(true);
        addView(commands);
    }

    private void toggle() {
        if (socket != null) {
            stop();
            return;
        }
        int number = MainActivity.parsePort(port.getText().toString());
        if (number < 0) {
            state.setText("Port must be between 1 and 65535");
            return;
        }
        toggle.setText("Stop server");
        port.setEnabled(false);
        state.setText("Starting…");
        Ui.keepScreenOn(activity, true);
        Platform.worker("pingplanet-serve", () -> serve(number));
    }

    private void serve(int number) {
        String problem = null;
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(number));
            StringBuilder lines = new StringBuilder("On the other machine, run:\n");
            for (Net.Source s : Net.sources()) {
                lines.append("\npingplanet lan ").append(s.address().getHostAddress())
                        .append(number == Lan.DEFAULT_PORT ? "" : ":" + number);
            }
            post(() -> {
                socket = server;
                state.setText("Waiting for tests on port " + number);
                commands.setText(lines);
            });
            Lan.serve(server, peer -> post(() -> state.setText("Testing with " + peer)));
        } catch (IOException e) {
            problem = "Server stopped: " + Engine.message(e);
        }
        String message = problem;
        post(() -> {
            socket = null;
            toggle.setText("Start server");
            port.setEnabled(true);
            state.setText(message != null ? message : "Stopped");
            commands.setText("");
            Ui.keepScreenOn(activity, false);
        });
    }

    void stop() {
        if (socket == null) return;
        try {
            socket.close(); // ends Lan.serve
        } catch (IOException e) {
            // already closed
        }
    }
}
