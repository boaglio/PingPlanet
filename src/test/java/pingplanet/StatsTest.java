package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    void medianOfOddAndEvenSamples() {
        assertEquals(20, new Latency(List.of(30.0, 10.0, 20.0), 0).medianMs());
        assertEquals(15, new Latency(List.of(10.0, 40.0, 20.0, 5.0), 0).medianMs());
    }

    @Test
    void jitterIsMeanOfConsecutiveDifferences() {
        // |12-10| + |9-12| + |13-9| = 9, over 3 gaps
        assertEquals(3, new Latency(List.of(10.0, 12.0, 9.0, 13.0), 0).jitterMs(), 1e-9);
        assertEquals(0, new Latency(List.of(10.0), 0).jitterMs());
    }

    @Test
    void lossAndReachability() {
        Latency l = new Latency(List.of(1.0, 2.0, 3.0), 1);
        assertTrue(l.reachable());
        assertEquals(25, l.lossPercent());
        Latency none = new Latency(List.of(), 5);
        assertFalse(none.reachable());
        assertEquals(100, none.lossPercent());
        assertTrue(Double.isNaN(none.medianMs()));
    }

    @Test
    void megabitsPerSecond() {
        assertEquals(100, new Throughput(12_500_000, 1_000_000_000).mbps(), 1e-9); // 12.5 MB in 1 s
        assertEquals(0, new Throughput(1, 0).mbps());
    }

    @Test
    void formatsUnits() {
        assertEquals("4.2 ms", Report.ms(4.2));
        assertEquals("123 ms", Report.ms(123.4));
        assertEquals("940.5 Mbps", Report.mbps(940.5));
        assertEquals("2.35 Gbps", Report.mbps(2350));
    }
}
