package pingplanet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colors and small view helpers shared by the screens, which are built in code like the desktop panels. */
final class Ui {

    static final int GREEN = 0xff22c55e;
    static final int LIME = 0xff84cc16;
    static final int YELLOW = 0xffeab308;
    static final int ORANGE = 0xfff97316;
    static final int RED = 0xffef4444;
    static final int SKY = 0xff0ea5e9;

    private Ui() {
    }

    static boolean dark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static int muted(Context context) {
        return dark(context) ? Color.rgb(148, 163, 184) : Color.rgb(100, 116, 139);
    }

    /** Same bands as the desktop map and its legend. */
    static int latencyColor(double ms) {
        return ms < 30 ? GREEN : ms < 80 ? LIME : ms < 150 ? YELLOW : ms < 250 ? ORANGE : RED;
    }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    static TextView text(Context context, String text, float sp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        return view;
    }

    static TextView caption(Context context, String text) {
        TextView view = text(context, text, 13);
        view.setTextColor(muted(context));
        return view;
    }

    static Button button(Context context, String text, View.OnClickListener onClick) {
        Button button = new Button(context);
        button.setText(text);
        button.setOnClickListener(onClick);
        return button;
    }

    static EditText field(Context context, String hint, boolean number) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return field;
    }

    /** A result line: a label on the left and a colored value on the right. */
    static LinearLayout row(Context context, String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 6), 0, dp(context, 6));
        TextView left = text(context, label, 14);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView right = text(context, value, 14);
        right.setTypeface(Typeface.DEFAULT_BOLD);
        right.setGravity(Gravity.END);
        if (valueColor != 0) right.setTextColor(valueColor);
        row.addView(right);
        return row;
    }

    /** The screen stays on while a test or the server runs: a sleeping phone drops its Wi-Fi speed or the connection. */
    static void keepScreenOn(Activity activity, boolean on) {
        if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static void share(Activity activity, String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(send, "Share report"));
    }
}
