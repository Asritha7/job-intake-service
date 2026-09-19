package dev.asritha.intake;

import java.sql.SQLException;

public interface JobStore {
    record Job(String id, String payload, String status, int attempts, String resultSha256, String lastError) {
        public Job(String id, String payload) { this(id, payload, "accepted", 0, null, null); }
        String json() {
            return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"attempts\":" + attempts
                + ",\"result_sha256\":" + quoted(resultSha256) + ",\"last_error\":" + quoted(lastError) + "}";
        }
        private static String quoted(String text) {
            if (text == null) return "null";
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
    record Submission(int status, Job job) {}
    Submission submit(String key, String payload) throws SQLException;
    Job find(String id) throws SQLException;
    boolean ready() throws SQLException;
}
