package dev.asritha.intake;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Explicitly non-durable demo backend. PostgreSQL is the normal application backend. */
public final class InMemoryJobStore implements JobStore {
    private final Map<String, Job> byKey = new HashMap<>();
    private final Map<String, Job> byId = new HashMap<>();
    private final int capacity;
    public InMemoryJobStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
    }
    public synchronized Submission submit(String key, String payload) {
        Job existing = byKey.get(key);
        if (existing != null) {
            if (!existing.payload().equals(payload)) { return new Submission(409, null); }
            return new Submission(200, existing);
        }
        if (byId.size() >= capacity) { return new Submission(503, null); }
        Job job = new Job(UUID.randomUUID().toString(), payload);
        byKey.put(key, job);
        byId.put(job.id(), job);
        return new Submission(201, job);
    }
    public synchronized Job find(String id) { return byId.get(id); }
    public boolean ready() { return true; }
}
