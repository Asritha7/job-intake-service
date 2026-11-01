package dev.asritha.intake;

import java.util.Map;

/** Offline classification checks; no PostgreSQL connection is required. */
public final class PgWaitSamplerTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        String lockSql = "SELECT capacity FROM intake_config WHERE singleton = TRUE FOR UPDATE";
        check("capacity_lock_wait".equals(PgWaitSampler.category("Lock", "transactionid", lockSql)),
                "Classify observed lock wait on admission statement");
        check("other_lock_wait".equals(PgWaitSampler.category("Lock", "tuple", "SELECT * FROM jobs")),
                "Keep unrelated lock waits separate");
        check("not_waiting".equals(PgWaitSampler.category(null, null, lockSql)),
                "No wait event must not be called blocked");
        check("io_wait".equals(PgWaitSampler.category("IO", "DataFileRead", lockSql)),
                "Do not call IO waits capacity lock contention");
        check("client_wait".equals(PgWaitSampler.category("Client", "ClientRead", lockSql)),
                "Do not confuse idle clients and lock waits");
        String report = PgWaitSampler.report(Map.of("capacity_lock_wait", 2L), 10, 20, 0, 100, 0);
        check(report.contains("\"capacity_lock_wait\": 2"), "Report stores category counts");
        check(report.contains("\"sample_rounds\": 10"), "Report stores sample denominator");
        check(!report.contains(lockSql), "Do not serialize SQL or payload-bearing queries");
        System.out.println("Passed " + checks + " wait sampler assertions.");
    }
}
