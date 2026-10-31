package pingplanet;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The app's one screen: a title, the three tabs of the desktop window, and the tab showing. */
public final class MainActivity extends Activity {

    private InternetTab internet;
    private LanTab lan;
    private ServerTab server;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int margin = Ui.dp(this, 12);
        root.setPadding(margin, margin, margin, margin);
        root.setOnApplyWindowInsetsListener((v, insets) -> { // the app draws behind the system bars on Android 15+
            v.setPadding(margin + insets.getSystemWindowInsetLeft(), margin + insets.getSystemWindowInsetTop(),
                    margin + insets.getSystemWindowInsetRight(), margin + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = Ui.text(this, "PingPlanet", 22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        internet = new InternetTab(this);
        lan = new LanTab(this);
        server = new ServerTab(this);
        FrameLayout content = new FrameLayout(this);
        View[] tabs = {internet, lan, server};
        for (View tab : tabs) content.addView(tab);

        LinearLayout bar = new LinearLayout(this);
        String[] names = {"Internet", "LAN test", "Server"};
        Button[] buttons = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            int index = i;
            buttons[i] = Ui.button(this, names[i], v -> show(tabs, buttons, index));
            bar.addView(buttons[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(bar);
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        show(tabs, buttons, 0);
        setContentView(root);
    }

    private static void show(View[] tabs, Button[] buttons, int index) {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            buttons[i].setEnabled(i != index);
        }
    }

    /** The port typed in a field, or -1 when it is not one. */
    static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.strip());
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    protected void onDestroy() {
        internet.stop();
        lan.stop();
        server.stop();
        super.onDestroy();
    }
}
