package dev.asritha.intake;

/** Pure in-memory checks of test-only phase recorder, no database mutations. */
public final class AdmissionPhaseProbeTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var recorder = new AdmissionPhaseProbe.Recorder();
        recorder.accept("capacity_lock", 2_000_000L);
        recorder.accept("capacity_lock", 3_000_000L);
        recorder.accept("jdbc_connect", 1_000_000L);
        String measurements = AdmissionPhaseProbe.metrics(recorder, 2, 5_000_000L);
        check(measurements.contains("\"capacity_lock\""), "Includes stage names only");
        check(measurements.contains("\"count\":2"), "Counts stage samples");
        check(measurements.contains("\"p95_ms\":3.0"), "Uses nearest-rank percentile");
        check(!measurements.contains("idempotency_key"), "Never exports keys or SQL");
        recorder.clear();
        check(AdmissionPhaseProbe.metrics(recorder, 0, 1000).contains("\"stages\":{}"),
                "Phase reset isolates measurements");
        System.out.println("Passed " + checks + " phase recorder assertions.");
    }
}
