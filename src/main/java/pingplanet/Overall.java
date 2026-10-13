package pingplanet;

import java.util.OptionalLong;

/**
 * Progress of the whole run. Every step has a weight in "units", roughly the
 * seconds it takes, so the bar moves evenly: a 5 s download counts more than a
 * quick latency check. The estimate of the time left extrapolates from the pace
 * so far, once enough is done for it to mean something.
 */
final class Overall {

    /** Below this fraction done the time left is too uncertain to show. */
    static final double MIN_FRACTION_FOR_ESTIMATE = 0.03;

    private final double totalUnits;
    private final long startNanos;
    private double doneUnits;
    private double currentUnits;

    Overall(double totalUnits, long startNanos) {
        this.totalUnits = Math.max(totalUnits, 1e-9);
        this.startNanos = startNanos;
    }

    /** How far the step in progress has come, in units (replaces the previous value). */
    void within(double units) {
        currentUnits = Math.max(0, units);
    }

    /** The step in progress is finished; it was worth {@code units}. */
    void step(double units) {
        doneUnits += units;
        currentUnits = 0;
    }

    /** Jumps to {@code units} done in total, e.g. to skip the rest of a connection that failed. */
    void skipTo(double units) {
        doneUnits = Math.max(doneUnits, units);
        currentUnits = 0;
    }

    double doneUnits() {
        return doneUnits;
    }

    double fraction() {
        return Math.clamp((doneUnits + currentUnits) / totalUnits, 0, 1);
    }

    OptionalLong remainingNanos(long nowNanos) {
        double f = fraction();
        if (f < MIN_FRACTION_FOR_ESTIMATE || f >= 1) return OptionalLong.empty();
        long elapsed = nowNanos - startNanos;
        return OptionalLong.of((long) (elapsed / f * (1 - f)));
    }

    /** "about 2m 10s left", "about 40 s left", "a few seconds left". */
    static String remainingText(long nanos) {
        long seconds = Math.round(nanos / 1e9);
        if (seconds < 10) return "a few seconds left";
        if (seconds < 60) return "about " + (seconds + 4) / 5 * 5 + " s left"; // rounded up to 5 s: less jumpy
        return "about " + seconds / 60 + "m " + String.format("%02d", seconds % 60 / 10 * 10) + "s left";
    }
}
