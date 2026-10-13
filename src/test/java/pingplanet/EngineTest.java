package pingplanet;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EngineTest {

    @Test
    void interruptingStopsATestPromptly() throws Exception {
        // A server that accepts connections and never answers: a download would wait 20 s for its first byte.
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Server s = new Server("XX", "Nowhere", "Silent", "Test", Double.NaN, Double.NaN,
                    URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/f"));
            Engine.WorldPlan plan = new Engine.WorldPlan(List.of(s), Collections.singletonList(null), true, 5, 2);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread worker = Thread.ofPlatform().start(() -> {
                try {
                    Engine.world(plan, new ViewAdapter());
                } catch (Throwable t) {
                    outcome.set(t);
                }
            });
            Thread.sleep(1_500);
            long stopAt = System.nanoTime();
            worker.interrupt(); // what the window's Stop button does
            worker.join(5_000);
            assertTrue(!worker.isAlive(), "the test ended");
            assertTrue(System.nanoTime() - stopAt < 3_000_000_000L, "within a few seconds of Stop");
            assertInstanceOf(CancellationException.class, outcome.get());
        }
    }
}
