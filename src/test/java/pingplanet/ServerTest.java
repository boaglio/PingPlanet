package pingplanet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerTest {

    @Test
    void builtInListIsValid() {
        List<Server> servers = Server.builtIn();
        assertTrue(servers.size() >= 20);
        for (Server s : servers) {
            assertTrue(s.code().matches("[A-Z]{2}|ANY"), s.code());
            assertEquals("https", s.url().getScheme(), s.url().toString());
            assertTrue(s.located() || s.code().equals("ANY"), s.label());
        }
        assertFalse(Server.filter(servers, Set.of("BR")).isEmpty());
    }

    @Test
    void parsesCommentsHeaderAndCommasInUrl() throws IOException {
        List<Server> servers = parse("""
                # comment
                code,country,city,provider,lat,lon,url

                br, Brazil , São Paulo,Test, -23.5,-46.6 ,http://example.com:8080/file?a=1,2
                """);
        assertEquals(1, servers.size());
        Server s = servers.getFirst();
        assertEquals("BR", s.code());
        assertEquals("Brazil", s.country());
        assertEquals("São Paulo, Brazil", s.label());
        assertEquals(-23.5, s.lat());
        assertEquals(-46.6, s.lon());
        assertEquals("example.com", s.host());
        assertEquals(8080, s.port());
        assertEquals("http://example.com:8080/file?a=1,2", s.url().toString());
    }

    @Test
    void defaultPortsFollowTheScheme() throws IOException {
        assertEquals(443, parse("XX,X,X,X,,,https://a.example/f").getFirst().port());
        assertEquals(80, parse("XX,X,X,X,,,http://a.example/f").getFirst().port());
    }

    @Test
    void coordinatesAndCityAreOptional() throws IOException {
        Server s = parse("ANY,Nearest edge,,Cloudflare,,,https://a.example/f").getFirst();
        assertFalse(s.located());
        assertEquals("Nearest edge", s.label());
        assertEquals("ANY,Nearest edge,,Cloudflare,,,https://a.example/f", s.toCsv());
    }

    @Test
    void csvRoundTrips() throws IOException {
        for (Server s : Server.builtIn()) {
            assertEquals(s, parse(s.toCsv()).getFirst());
        }
    }

    @Test
    void rejectsBadLines() {
        IOException e = assertThrows(IOException.class, () -> parse("BR,Brazil,São Paulo"));
        assertTrue(e.getMessage().startsWith("line 1"));
        assertThrows(IOException.class, () -> parse("BR,Brazil,City,P,,,ftp://example.com/f"));
        assertThrows(IOException.class, () -> parse("BR,Brazil,City,P,95,0,https://example.com/f"));
        assertThrows(IOException.class, () -> parse("BR,Brazil,City,P,x,0,https://example.com/f"));
    }

    @Test
    void filtersByCode() throws IOException {
        List<Server> servers = parse("""
                BR,Brazil,A,P,,,https://a.example/f
                US,USA,B,P,,,https://b.example/f
                JP,Japan,C,P,,,https://c.example/f
                """);
        assertEquals(List.of("BR", "JP"), Server.filter(servers, Set.of("JP", "BR")).stream().map(Server::code).toList());
        assertEquals(3, Server.filter(servers, Set.of()).size());
    }

    private static List<Server> parse(String csv) throws IOException {
        return Server.parse(new BufferedReader(new StringReader(csv)));
    }
}
