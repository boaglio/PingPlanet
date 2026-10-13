package pingplanet;

/** Bytes moved in a measured time window. */
record Throughput(long bytes, long nanos) {

    double mbps() {
        return nanos <= 0 ? 0 : bytes * 8.0 / nanos * 1_000; // bits per ns -> megabits per s
    }
}
