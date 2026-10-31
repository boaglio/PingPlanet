package pingplanet;

import java.io.IOException;
import java.io.InputStream;

/** Sends the same GET again and again to one URL, each on its own connection: a download stream's source of data. */
interface Fetcher extends AutoCloseable {

    record Response(int status, InputStream body) {
    }

    Response get() throws IOException, InterruptedException;

    @Override
    void close();
}
