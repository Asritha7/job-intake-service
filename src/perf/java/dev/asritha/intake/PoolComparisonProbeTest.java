package dev.asritha.intake;

import java.util.List;

/** Offline assertions: no real PostgreSQL or Hikari pool needed. */
public final class PoolComparisonProbeTest {
    private static int checks;
    private static void verify(boolean condition, String explanation) {
        checks++;
        if (!condition) throw new AssertionError(explanation);
    }
    public static void main(String[] args) {
        for (String name : List.of("fresh", "pool4", "pool8")) {
            verify(name.equals(PoolComparisonProbe.modeName(name)), "Known pool mode");
        }
        for (String name : List.of("pool0", "pool16", "remote", "")) {
            boolean rejected = false;
            try { PoolComparisonProbe.modeName(name); }
            catch (IllegalArgumentException expected) { rejected = true; }
            verify(rejected, "Reject unsupported connection mode");
        }
        var recorder = new AdmissionPhaseProbe.Recorder();
        recorder.accept("jdbc_connect", 2_000_000L);
        String json = PoolComparisonProbe.metrics(recorder, List.of(3_000_000L),
                5_000_000L, 1);
        verify(json.contains("\"operations\":1"), "Operation count");
        verify(json.contains("\"throughput_per_second\":200.0"), "Throughput calculation");
        verify(json.contains("\"jdbc_connect\""), "Stage capture");
        verify(!json.contains("password"), "Report contains no credential");
        System.out.println("Passed " + checks + " comparison probe assertions.");
    }
}
