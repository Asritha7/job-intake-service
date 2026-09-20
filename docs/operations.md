# Operating and understanding the HTTP service

## Resource budget

| Resource | Default limit |
|---|---|
| Executor threads | 8 |
| Queued executor tasks | 64 |
| Open connections (active and idle) | 128 |
| Idle connections | 16 |
| Idle duration | 15 seconds, with periodic enforcement |
| Request headers | 32 fields and a 16 KiB section, including JDK accounting overhead |
| Request body | 8,192 bytes |
| Unread-body drain | 8,192-byte budget, also subject to the request timer |
| Request receive time | 10 seconds, with periodic enforcement |
| Response time | 15 seconds, with periodic enforcement |
| Shutdown grace period | 5 seconds |

The accept backlog of 64 is an OS hint, separate from the executor queue. A task may still be reading headers before an application handler starts. Queuing time and handler delays can affect the transport timers. Closing a timed-out connection does not cancel an already running database commit. Retry an ambiguous submission with its original idempotency key and exact payload.

The server uses the built-in OpenJDK HTTP provider. Limits are set process-wide before its first initialization. Embedding this application after another HTTP server has initialized the provider is unsupported. `-Dintake.http.requestSeconds=N` allows a request budget of 1–60 seconds when launching Java directly; other transport settings are fixed by the application. The socket tests use two seconds.

The [JDK module reference](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/module-summary.html) lists the properties. Its request/response time unit description differs from the implementation: [OpenJDK 21 ServerImpl](https://github.com/openjdk/jdk21u/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/ServerImpl.java) converts those property values from seconds. The real incomplete-header/body tests verify the behavior on the runtime used in CI.

## Why close overloaded connections?

A bounded `ThreadPoolExecutor` rejects excess work without running it on the HTTP dispatcher. At that stage there is no application `HttpExchange` to send a reliable 503 response through. The JDK closes that connection. This differs from the existing HTTP 503 responses for unavailable storage or exhausted job capacity.

A rejected executor task increments `http_executor_rejections_total`. It never reaches the job store. Other transport refusals (connection/header limits, malformed protocol, or timeout before the handler) do not increment this counter. Clients should use capped exponential backoff with jitter and retain the same submission key. Do not treat a missing response as proof that a previous request was not committed.

Health, readiness and metrics use the same listener and executor. During overload their absence is itself an operational signal; there is no reserved management port.

## Read the metrics

```sh
curl --fail http://127.0.0.1:8080/metrics
```

The endpoint emits Prometheus-compatible numeric text with a fixed set of metric names and response-class labels. It includes no job IDs, payloads, keys, raw paths, credentials, or exception messages.

| Metric | Meaning |
|---|---|
| `jobs_created_total`, `jobs_replayed_total`, `jobs_conflicts_total`, `jobs_capacity_rejections_total` | HTTP submission outcomes observed by this API process |
| `http_executor_active`, `http_executor_queued` | Approximate current executor occupancy, including pre-handler header work |
| `http_executor_threads_limit`, `http_executor_queue_limit` | Fixed executor bounds |
| `http_executor_rejections_total` | Tasks refused because the executor is full or stopping |
| `http_requests_in_flight` | Entered handlers that have not finished cleanup |
| `http_handler_duration_seconds_count`, `http_handler_duration_seconds_sum` | Finished handler count and cumulative duration, including body reads and cleanup, excluding pre-handler queuing/header parsing |
| `http_responses_total{class="2xx"}`, `4xx`, `5xx` | Response headers successfully sent; does not guarantee delivery of the complete body |
| `http_io_errors_total` | IO exceptions observed by an entered handler, including disconnects and body timeouts; not exclusively timeouts |
| `http_storage_errors_total` | Unavailable-store results observed by handlers; excludes job capacity exhaustion |

All these values reset when the API process restarts. Snapshots are not transactional, and a metrics scrape observes itself as an in-flight request. Duration count/sum are cumulative totals, not latency percentiles. Worker attempts and states live in PostgreSQL but are not scraped here. Do not interpret HTTP creation counts as successful worker executions.

## Shutdown and verification

Ctrl+C invokes the shutdown hook. The listener stops accepting connections, active exchanges get up to five seconds to finish, and remaining executor tasks are interrupted. There is no promise that queued work finishes, that interruption cancels JDBC, or that arbitrary custom store implementations stop immediately. Retry ambiguous requests using their original keys after restarting.

`./test.sh` checks normal API behavior plus real-socket saturation, recovery, partial headers, incomplete bodies, bounded invalid-body cleanup, safe metrics, and completion of an in-flight request during shutdown. Latches hold the store deliberately so the saturation test measures actual queue bounds rather than depending on machine speed. `./test.sh --postgres` additionally runs persistence and worker recovery suites.

## Learning questions

1. Why is a fixed thread count insufficient? Its default queue can still grow without a bound.
2. Why can overload have no HTTP status? Rejection happens before an application handler exists.
3. Why can a timed-out client still have a job? The database may commit even though the transport closes.
4. Why distinguish executor occupancy from handler occupancy? Header parsing consumes an executor thread before entering the handler.
5. Why keep metric labels fixed? Client-controlled IDs and paths would create an ever-growing set of time series and could expose private input.

A next milestone should measure throughput and queue delay under reproducible load before tuning limits, then consider connection pooling and durable worker-state metrics. These tests establish specific behavior, not a production capacity claim.

Error responses use a small unread-body drain because immediately closing a socket containing unread bytes can cause a reset that prevents the client from receiving the response. A client that withholds body bytes can delay cleanup until the request timer fires. Transport failure can still prevent delivery; a response is never guaranteed for an invalid or oversized stream.
