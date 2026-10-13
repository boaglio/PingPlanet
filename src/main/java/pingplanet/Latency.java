package pingplanet;

import java.util.ArrayList;
import java.util.List;

/** Round-trip samples in milliseconds, plus how many probes got no answer. */
record Latency(List<Double> samplesMs, int lost) {

    Latency {
        samplesMs = List.copyOf(samplesMs);
    }

    boolean reachable() {
        return !samplesMs.isEmpty();
    }

    double medianMs() {
        if (samplesMs.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(samplesMs);
        sorted.sort(null);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /** Mean difference between consecutive samples, the way RFC 3550 and most speed tests report it. */
    double jitterMs() {
        if (samplesMs.size() < 2) return 0;
        double sum = 0;
        for (int i = 1; i < samplesMs.size(); i++) {
            sum += Math.abs(samplesMs.get(i) - samplesMs.get(i - 1));
        }
        return sum / (samplesMs.size() - 1);
    }

    double lossPercent() {
        int total = samplesMs.size() + lost;
        return total == 0 ? 0 : 100.0 * lost / total;
    }
}
