package pingplanet;

/** Live updates from a running test, a few times per second. */
@FunctionalInterface
interface Progress {

    Progress NONE = (done, nanos) -> {
    };

    /**
     * @param done  how much is done: bytes for transfers, samples for latency
     * @param nanos time since measuring started, or 0 while still connecting
     */
    void update(long done, long nanos);

    /** Minimum time between updates, so the terminal is not flooded. */
    long INTERVAL_NANOS = 80_000_000;
}
