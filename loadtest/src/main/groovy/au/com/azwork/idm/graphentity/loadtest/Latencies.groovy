package au.com.azwork.idm.graphentity.loadtest

/** Timings of one kind of call, safe to record from many threads; summarised as count, mean and percentiles in ms. */
class Latencies {
    final String name
    private final List<Long> nanos = Collections.synchronizedList(new ArrayList<Long>())
    private final Map<String, Integer> errors = Collections.synchronizedMap(new TreeMap<String, Integer>())

    Latencies(String name) { this.name = name }

    void record(long elapsedNanos) { nanos << elapsedNanos }

    /** Counts a failed call under a short label (an HTTP status or exception class); never a message body. */
    void error(String label) {
        synchronized (errors) { errors[label] = (errors[label] ?: 0) + 1 }
    }

    /** Runs {@code call}, recording its time on success and its failure label otherwise; rethrows the failure. */
    def <T> T time(Closure<T> call) {
        long t0 = System.nanoTime()
        try {
            T result = call.call()
            record(System.nanoTime() - t0)
            result
        } catch (Exception e) {
            error(e instanceof IdmRest.IdmException ? "HTTP ${(e as IdmRest.IdmException).status}" : e.class.simpleName)
            throw e
        }
    }

    int getCount() { nanos.size() }

    int getErrorCount() {
        synchronized (errors) { return errors.values().sum(0) as int }
    }

    Map<String, Object> summary() {
        List<Long> sorted
        synchronized (nanos) { sorted = new ArrayList<>(nanos).sort() }
        Map<String, Integer> errorCounts
        synchronized (errors) { errorCounts = new TreeMap<>(errors) }
        Map<String, Object> s = [name: name, count: sorted.size(), errors: errorCounts] as Map<String, Object>
        if (sorted) {
            s.meanMs = round(sorted.sum(0L) / sorted.size() / 1e6)
            s.p50Ms = round(percentile(sorted, 50) / 1e6)
            s.p95Ms = round(percentile(sorted, 95) / 1e6)
            s.p99Ms = round(percentile(sorted, 99) / 1e6)
            s.maxMs = round(sorted[-1] / 1e6)
        }
        s
    }

    /** Nearest-rank percentile of a sorted, non-empty list. */
    static long percentile(List<Long> sorted, double p) {
        int rank = Math.max(1, Math.ceil(p / 100d * sorted.size()) as int)
        sorted[rank - 1]
    }

    private static double round(Number n) { Math.round(n.doubleValue() * 10) / 10d }
}
