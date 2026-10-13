package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pingplanet.PingPlanet.Options;
import pingplanet.PingPlanet.UsageException;

class OptionsTest {

    @Test
    void defaultsToWorld() {
        Options o = Options.parse(new String[0]);
        assertEquals("world", o.command);
        assertEquals(5, o.seconds);
        assertEquals(4, o.streams);
        assertEquals(Lan.DEFAULT_PORT, o.port);
    }

    @Test
    void parsesWorldOptions() {
        Options o = Options.parse(new String[] {"-c", "br, us,jp", "--latency-only", "--seconds=10", "-n", "8", "--json"});
        assertEquals(List.of("BR", "US", "JP"), List.copyOf(o.countries));
        assertTrue(o.latencyOnly);
        assertEquals(10, o.seconds);
        assertEquals(8, o.streams);
        assertTrue(o.json);
    }

    @Test
    void parsesInterfaces() {
        assertEquals(List.of("eth0", "wlan0"), Options.parse(new String[] {"-i", "eth0, wlan0"}).interfaces);
        assertEquals(List.of("192.168.0.23"), Options.parse(new String[] {"--interface=192.168.0.23"}).interfaces);
        assertEquals("interfaces", Options.parse(new String[] {"interfaces"}).command);
    }

    @Test
    void defaultMeansTheSystemChooses() throws Exception {
        assertEquals(java.util.Collections.singletonList(null), PingPlanet.sources(Options.parse(new String[] {"-i", "default"})));
        assertThrows(UsageException.class, () -> PingPlanet.sources(Options.parse(new String[] {"-i", "default,eth0"})));
    }

    @Test
    void parsesLanTarget() {
        Options o = Options.parse(new String[] {"lan", "192.168.0.10:9000"});
        assertEquals("lan", o.command);
        assertEquals("192.168.0.10:9000", o.target);
    }

    @Test
    void rejectsBadInput() {
        assertThrows(UsageException.class, () -> Options.parse(new String[] {"--bogus"}));
        assertThrows(UsageException.class, () -> Options.parse(new String[] {"--seconds"}));
        assertThrows(UsageException.class, () -> Options.parse(new String[] {"--seconds", "abc"}));
        assertThrows(UsageException.class, () -> Options.parse(new String[] {"--streams", "0"}));
        assertThrows(UsageException.class, () -> Options.parse(new String[] {"--port", "70000"}));
        assertThrows(UsageException.class, () -> Options.parse(new String[] {"world", "extra"}));
    }
}
