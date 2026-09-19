package dev.asritha.intake;

import java.sql.SQLException;

public interface JobStore {
    record Job(String id, String payload) {
        String json() { return "{\"id\":\"" + id + "\",\"status\":\"accepted\"}"; }
    }
    record Submission(int status, Job job) {}
    Submission submit(String key, String payload) throws SQLException;
    Job find(String id) throws SQLException;
    boolean ready() throws SQLException;
}
