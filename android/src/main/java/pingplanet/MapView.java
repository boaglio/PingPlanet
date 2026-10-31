package pingplanet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * The world as dots, with a marker per server colored by latency and a pulsing ring
 * on the server being tested, like the desktop map. Tap a marker for its details.
 */
final class MapView extends View {

    /** A place on the map. {@code ms} is NaN for "not measured yet" and negative for "unreachable". */
    private record Marker(Server server, double ms) {
    }

    private static final WorldMap WORLD = WorldMap.load();
    private static final boolean[][] DOTS = WORLD.dots();

    private final List<Marker> markers = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long started = System.nanoTime();
    private Server active;
    private double spacing;
    private double left;

    MapView(Context context) {
        super(context);
    }

    /** Shows every server as "not measured yet". */
    void reset(List<Server> servers) {
        markers.clear();
        for (Server s : servers) if (s.located()) markers.add(new Marker(s, Double.NaN));
        active = null;
        invalidate();
    }

    void measured(Server server, double ms) {
        markers.removeIf(m -> m.server().equals(server));
        if (server.located()) markers.add(new Marker(server, ms));
        invalidate();
    }

    /** Pulses this server's marker; null stops the pulse. */
    void active(Server server) {
        active = server;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int wanted = width * DOTS.length / DOTS[0].length;
        setMeasuredDimension(width, resolveSize(wanted, heightSpec));
    }

    private void layoutGrid() {
        spacing = Math.min(getWidth() / (double) DOTS[0].length, getHeight() / (double) DOTS.length);
        left = (getWidth() - spacing * DOTS[0].length) / 2;
    }

    private float[] point(Server s) {
        double[] d = WORLD.dot(s.lat(), s.lon());
        return new float[] {(float) (left + (d[1] + 0.5) * spacing), (float) ((d[0] + 0.5) * spacing)};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        layoutGrid();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.dark(getContext()) ? Color.argb(90, 148, 163, 184) : Color.argb(70, 71, 85, 105));
        float r = (float) (spacing * 0.34);
        for (int row = 0; row < DOTS.length; row++) {
            for (int col = 0; col < DOTS[row].length; col++) {
                if (DOTS[row][col]) canvas.drawCircle((float) (left + (col + 0.5) * spacing), (float) ((row + 0.5) * spacing), r, paint);
            }
        }

        float size = (float) Math.max(Ui.dp(getContext(), 5), spacing * 1.5);
        for (Marker m : markers) {
            float[] p = point(m.server());
            int c = color(m);
            if (!Double.isNaN(m.ms()) && m.ms() >= 0) { // soft glow behind measured markers
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(60, Color.red(c), Color.green(c), Color.blue(c)));
                canvas.drawCircle(p[0], p[1], size, paint);
            }
            paint.setColor(c);
            paint.setStyle(m.ms() < 0 ? Paint.Style.STROKE : Paint.Style.FILL);
            paint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            canvas.drawCircle(p[0], p[1], size / 2, paint);
        }

        if (active != null && active.located()) {
            float[] p = point(active);
            double phase = ((System.nanoTime() - started) / 1e9 % 1.2) / 1.2; // one ring every 1.2 s
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.argb((int) (220 * (1 - phase)), Color.red(Ui.SKY), Color.green(Ui.SKY), Color.blue(Ui.SKY)));
            canvas.drawCircle(p[0], p[1], (float) (size * (0.8 + phase * 2.6) / 2), paint);
            postInvalidateDelayed(40);
        }
    }

    private int color(Marker m) {
        if (Double.isNaN(m.ms())) return Ui.dark(getContext()) ? Color.rgb(100, 116, 139) : Color.rgb(148, 163, 184);
        return m.ms() < 0 ? Ui.RED : Ui.latencyColor(m.ms());
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP) return false;
        performClick();
        double best = Ui.dp(getContext(), 24);
        Marker hit = null;
        for (Marker m : markers) {
            float[] p = point(m.server());
            double d = Math.hypot(p[0] - e.getX(), p[1] - e.getY());
            if (d < best) {
                best = d;
                hit = m;
            }
        }
        if (hit != null) {
            String state = Double.isNaN(hit.ms()) ? "not measured yet" : hit.ms() < 0 ? "unreachable" : Report.ms(hit.ms());
            Toast.makeText(getContext(), hit.server().label() + " · " + hit.server().provider() + " · " + state, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
