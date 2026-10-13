package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverallTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void fractionCountsFinishedStepsAndTheOneInProgress() {
        Overall o = new Overall(30, 0);
        assertEquals(0, o.fraction());
        o.within(1.5);
        assertEquals(0.05, o.fraction(), 1e-9);
        o.step(3);
        assertEquals(0.1, o.fraction(), 1e-9);
        o.within(3);
        assertEquals(0.2, o.fraction(), 1e-9);
        o.skipTo(30);
        assertEquals(1, o.fraction());
    }

    @Test
    void estimatesTimeLeftFromThePaceSoFar() {
        Overall o = new Overall(100, 0);
        o.step(1);
        assertTrue(o.remainingNanos(10 * SECOND).isEmpty(), "1% done is too early to guess");
        o.step(24);
        assertEquals(30 * SECOND, o.remainingNanos(10 * SECOND).orElseThrow()); // 25% in 10 s: 30 s to go
    }

    @Test
    void remainingTextIsRoundedSoItDoesNotJitter() {
        assertEquals("a few seconds left", Overall.remainingText(4 * SECOND));
        assertEquals("about 35 s left", Overall.remainingText(31 * SECOND));
        assertEquals("about 2m 10s left", Overall.remainingText(134 * SECOND));
    }
}
