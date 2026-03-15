# Plan: Replace google-cloud-logging SDK with direct Cloud Logging REST API

## Problem statement

The `google-cloud-logging` Java client library initialises gRPC at Spring context creation
time (`LoggingOptions.newBuilder().build().getService()`).  The Gravitee gateway bundles its
own `grpc-api` in the parent classloader (used for the management-gateway sync protocol).
Because Gravitee uses parent-first class loading for packages that exist in the parent, the
plugin's copy of `grpc-api` is shadowed.  When `LoadBalancerRegistry` static-initialises
itself it calls `Class.forName("io.grpc.internal.PickFirstLoadBalancerProvider")` using
`LoadBalancerRegistry.class.getClassLoader()` — which is the gateway's classloader, which
does not ship `grpc-core`.  The `pick_first` load balancer therefore cannot be registered and
gRPC channel creation fails with:

```
WARNING: Unable to find pick-first LoadBalancer
java.lang.ClassNotFoundException: io.grpc.internal.PickFirstLoadBalancerProvider
```

The current workaround (maven-shade-plugin relocating `io.grpc.*`) adds ~45 MB to the ZIP
and is brittle.  The permanent fix is to remove the gRPC dependency entirely by talking to
Cloud Logging over plain HTTPS.

## Goal

Replace `google-cloud-logging` (gRPC-backed) with a small hand-written HTTP client that
POSTs to the Cloud Logging REST API v2 (`entries:write` endpoint).  Retain all existing
observable behaviour:

* All five reportable types handled identically (Metrics, Log, MessageMetrics,
  EndpointStatus, Monitor).
* Same JSON payload shape, same labels, same severity rules, same trace/spanId fields.
* Batching, periodic flush, graceful shutdown flush, and best-effort retry on transient
  errors — matching the behaviour users currently get from the SDK.
* Authentication via the same two paths: explicit service-account key file, or Application
  Default Credentials (workload identity / `gcloud auth application-default login`).
* Zero gRPC on the gateway's classpath.

---

## Deep analysis of what the current code does

### GCloudReporter (the coordinator)

* Lifecycle (`doStart` / `doStop`): on start, creates mappers and logs the project ID by
  calling `((LoggingOptions) logging.getOptions()).getProjectId()`.  On stop, calls
  `logging.flush()` then `logging.close()`.  Both need exact equivalents in the new design.
* `canHandle`: pure Gravitee API logic, no SDK references — survives unchanged.
* `report`: dispatches to the four mappers, calls `writeEntry(LogEntry)`.  The type switched
  on (`Metrics`, `Log`, `MessageMetrics`, `EndpointStatus`, `Monitor`) is Gravitee API — no
  SDK references.  Survives unchanged except the entry type changes.
* `writeEntry`: sets `logName` and `resource` on every entry then calls
  `logging.write(List.of(withResource))` — one call per reportable.  Needs to become a call
  to the new writer's `enqueue(entry)` method.

### Mappers

Every mapper depends on `com.google.cloud.logging.{LogEntry, Payload, Severity,
HttpRequest}`.  The only GCL types used are:

| Mapper | GCL types used |
|---|---|
| MetricsToLogEntryMapper | `LogEntry`, `Payload.JsonPayload`, `Severity`, `HttpRequest`, `HttpRequest.RequestMethod` |
| LogToLogEntryMapper | `LogEntry`, `Payload.JsonPayload`, `Severity` |
| EndpointStatusToLogEntryMapper | `LogEntry`, `Payload.JsonPayload`, `Severity` |
| MessageMetricsToLogEntryMapper | `LogEntry`, `Payload.JsonPayload`, `Severity` |

All four mappers must be updated to produce a new internal record instead of `LogEntry`.
The mapping logic itself (field access, null guards, severity rules, trace prefix logic,
body truncation, ID sanitisation, transition filtering) is Gravitee-domain code and carries
over unchanged.

Notable invariants to preserve:
* `resolveSeverity`: 5xx → `ERROR` if `captureErrors` else `INFO`; 4xx → `WARNING`;
  otherwise `INFO`.
* `sanitizePath`: `ID_PATTERN` strips numeric/UUID path segments.
* `LogToLogEntryMapper.truncate`: 4096-char cap on request/response bodies.
* `EndpointStatusToLogEntryMapper`: emits only on `isTransition() == true`.
* `MetricsToLogEntryMapper.buildHttpRequest`: populates `latency` from
  `getGatewayResponseTimeMs()`.

### GCloudReporterSpringConfiguration

Creates the `Logging` bean — this is where gRPC is bootstrapped.  It must be replaced by a
bean that builds a `GCloudLogWriter` (credential loading logic is identical, just the target
object changes).

### GCloudReporterConfiguration

Pure Spring `@Value` wiring — no SDK references.  Survives as-is, but two new optional
configuration fields will be added:

* `batchSize` (default 100) — max entries per REST call.
* `flushIntervalSeconds` (default 5) — how often the background flusher runs.

---

## Cloud Logging REST API contract

**Endpoint**

```
POST https://logging.googleapis.com/v2/entries:write
Authorization: Bearer <oauth2-access-token>
Content-Type: application/json
```

**Request JSON shape** (all fields that the current SDK populates):

```json
{
  "logName":  "projects/{project}/logs/{logId}",
  "resource": { "type": "global", "labels": {} },
  "entries": [
    {
      "severity":  "INFO",
      "timestamp": "2024-01-15T12:00:00.000000000Z",
      "trace":     "projects/{project}/traces/{transactionId}",
      "spanId":    "{requestId}",
      "labels": {
        "gravitee.api_id": "api-123"
      },
      "jsonPayload": {
        "api_id": "api-123",
        "status": 200,
        "method": "GET",
        "uri":    "/api/v1/users/42",
        "path":   "/api/v1/users/{id}",
        "gateway_response_ms": 42,
        "gateway_latency_ms":  5,
        "endpoint_response_ms": 37
      },
      "httpRequest": {
        "requestMethod": "GET",
        "requestUrl":    "http://gateway.example.com/api/v1/users/42",
        "requestSize":   "128",
        "status":        200,
        "responseSize":  "512",
        "userAgent":     "gravitee-test-client/1.0",
        "remoteIp":      "10.0.0.1",
        "serverIp":      "10.0.0.100",
        "latency":       "0.042s"
      }
    }
  ]
}
```

Important format details:
* `timestamp` — RFC 3339 nanosecond precision: `Instant.ofEpochMilli(ts).toString()` produces
  the correct format.
* `requestSize` / `responseSize` — Cloud Logging expects these as **string** (int64 on the
  wire).
* `latency` — a protobuf Duration string: `"<seconds>.<nanos>s"`, e.g. `"0.042000000s"`.
  Produce with `String.format("%.9fs", millis / 1000.0)`.
* `trace` — must be the full resource path `projects/{project}/traces/{id}` for the Cloud
  Trace correlation to work. If `tracePrefix` is configured it already contains the full
  prefix; if blank, prepend `projects/{projectId}/traces/` automatically.
* `severity` — allowed string values: `DEFAULT`, `DEBUG`, `INFO`, `NOTICE`, `WARNING`,
  `ERROR`, `CRITICAL`, `ALERT`, `EMERGENCY`.

**Success response**: HTTP 200 with `{}` body.

**Retryable errors**: 429 (honour `Retry-After` header if present), 500, 503.

**Non-retryable errors**: 400, 401, 403 — log at WARN and drop the batch.

---

## New data model (Java 21 records)

Create package `io.gravitee.reporter.gcloud.writer` with the following types.  Using records
preserves immutability and eliminates boilerplate.

### `GCloudSeverity` — enum

```
DEBUG, INFO, WARNING, ERROR
```

Maps directly from GCL string values.

### `GCloudHttpRequest` — record

```
String requestMethod   // "GET", "POST", …
String requestUrl
long   requestSize     // 0 = omit
int    status
long   responseSize    // 0 = omit
String userAgent       // null = omit
String remoteIp        // null = omit
String serverIp        // null = omit
long   latencyMs       // 0 = omit
```

### `GCloudLogEntry` — record

```
GCloudSeverity        severity
Instant               timestamp      // null → omit (Cloud Logging timestamps on receipt)
String                trace          // null = omit
String                spanId         // null = omit
Map<String,String>    labels
Map<String,Object>    jsonPayload
GCloudHttpRequest     httpRequest    // null = omit
```

`logName` and `resource` are NOT per-entry fields in this model; they are set as top-level
defaults in the `entries:write` request body, exactly as the SDK does via
`LogEntry.toBuilder().setLogName(...).setResource(...)`.

### `GCloudLogWriter` — class

This is the central new class.  It replaces the `Logging` Spring bean.  Responsibilities:

1. **Credential management** — holds a `GoogleCredentials` instance; calls
   `refreshIfExpired()` before each flush.
2. **Queuing** — `ArrayBlockingQueue<GCloudLogEntry>` with configurable capacity (default
   500; always ≥ 2× `batchSize`).
3. **Batching** — `write(GCloudLogEntry)` adds to the queue.  If the queue reaches
   `batchSize`, immediately triggers a flush on the background executor.
4. **Periodic flush** — single-thread `ScheduledExecutorService` (or virtual-thread-based
   scheduled executor) fires every `flushIntervalSeconds`.
5. **HTTP** — Java 21 `java.net.http.HttpClient` (one shared instance, created with
   `.executor(Executors.newVirtualThreadPerTaskExecutor())` for non-blocking I/O).
6. **Retry** — exponential back-off: `1s → 2s → 4s` with ±25 % jitter, max 3 attempts, only
   for HTTP 429/500/503.  Permanent failures are logged at WARN and the batch is discarded
   (never blocks gateway threads).
7. **flush()** — public, called on `doStop()`; drains the entire queue and sends in batches
   of `batchSize`.
8. **close()** — cancels the scheduler, calls `flush()`, shuts down the HTTP client executor.

---

## Step-by-step implementation plan

### Step 1 — Define the new data model records

Create three files in `src/main/java/io/gravitee/reporter/gcloud/writer/`:

1. `GCloudSeverity.java` — a simple `enum`.
2. `GCloudHttpRequest.java` — a `record` with the fields listed above.
3. `GCloudLogEntry.java` — a `record` with severity, timestamp, trace, spanId, labels,
   jsonPayload, httpRequest.

These types have zero external dependencies — no SDK, no gRPC.

### Step 2 — Update all four mappers

Each mapper's return type changes from `com.google.cloud.logging.LogEntry` to
`GCloudLogEntry`.  The mapping logic is unchanged; only the builder calls differ.

Concrete changes per mapper:

**MetricsToLogEntryMapper**:
* Replace `Severity` enum references with `GCloudSeverity`.
* Replace `HttpRequest.newBuilder()` + setters with a `GCloudHttpRequest` record
  constructor. `RequestMethod.valueOf(method.name())` becomes just `method.name()` since
  `GCloudHttpRequest.requestMethod` is a plain `String`.
* Replace `Payload.JsonPayload.of(map)` with just passing the `Map<String,Object>` directly.
* Replace `LogEntry.newBuilder(...).setSeverity(...).setHttpRequest(...).setLabels(...)
  .setTrace(...).setSpanId(...).setTimestamp(...).build()` with a `GCloudLogEntry` record
  constructor call.
* Preserve: ID_PATTERN, sanitizePath, resolveSeverity, buildTrace, null guards — all stay
  identical.

**LogToLogEntryMapper**:
* Replace `Severity.DEBUG` with `GCloudSeverity.DEBUG`.
* Replace `Payload.JsonPayload.of(payload)` with the raw map.
* Replace `LogEntry.newBuilder(...)` with `GCloudLogEntry` record constructor.
* Preserve: MAX_BODY_LENGTH constant, truncate(), requestPayload(), responsePayload(),
  buildTrace() — all stay identical.

**EndpointStatusToLogEntryMapper**:
* Replace `Severity.INFO` / `Severity.ERROR` with `GCloudSeverity` equivalents.
* Replace `LogEntry.newBuilder(...)` with `GCloudLogEntry` record constructor; return type
  stays `Optional<GCloudLogEntry>`.
* Preserve: transition guard, step serialisation, label building — all stay identical.

**MessageMetricsToLogEntryMapper**:
* Replace `Severity.INFO` with `GCloudSeverity.INFO`.
* Replace `LogEntry.newBuilder(...)` with `GCloudLogEntry` record constructor.
* Preserve: connector type via `getLabel()`, all field mappings — all stay identical.

### Step 3 — Implement GCloudLogWriter

Create `src/main/java/io/gravitee/reporter/gcloud/writer/GCloudLogWriter.java`.

**Constructor parameters**:
* `GoogleCredentials credentials` — already scoped to `logging.write`.
* `String projectId`
* `String logName`
* `String resourceType`
* `Map<String, String> resourceLabels`
* `int batchSize` (default 100)
* `int flushIntervalSeconds` (default 5)

**Constructor body**:
1. Store all parameters.
2. Build the constant `logNameResource` string: `"projects/" + projectId + "/logs/" + logName`.
3. Create `ArrayBlockingQueue<GCloudLogEntry>(Math.max(500, batchSize * 5))`.
4. Create `httpClient = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build()`.
5. Create `scheduler = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().name("gcloud-flush").unstarted(r))`.
6. Schedule `this::flushSafe` at `flushIntervalSeconds` fixed-rate.

**`write(GCloudLogEntry entry)`**:
* `queue.offer(entry)` — non-blocking.  If `false` (queue full), log a WARN ("GCloud log
  queue full, dropping entry") and discard.
* If `queue.size() >= batchSize`, submit a one-shot flush to the scheduler executor.

**`flush()`** (called by the scheduler and by `close()`):
1. If `queue.isEmpty()` return immediately.
2. Drain up to `batchSize` entries into a `List<GCloudLogEntry> batch`.
3. Call `sendWithRetry(batch)`.
4. If more remain in queue and called from `close()`, loop until empty.

**`flushSafe()`** — wraps `flush()` in a try/catch that logs at WARN but never propagates.

**`sendWithRetry(List<GCloudLogEntry> batch)`**:
1. `credentials.refreshIfExpired()`; get token: `credentials.getAccessToken().getTokenValue()`.
2. Build JSON body string (see Step 4 below).
3. Build `HttpRequest`:
   ```
   POST https://logging.googleapis.com/v2/entries:write
   Authorization: Bearer <token>
   Content-Type: application/json
   ```
4. `httpClient.send(request, BodyHandlers.ofString())` — synchronous (we're already on the
   background executor thread; using virtual threads keeps this cheap).
5. On response:
   * 200: return (success).
   * 429: extract `Retry-After` header (seconds); wait that duration then retry.
   * 500, 503: exponential backoff (`1s * 2^attempt + jitter`); retry up to 3 times.
   * Other 4xx: log WARN with status + truncated body; drop batch (non-retryable).
   * All retries exhausted: log WARN "Failed to send X entries after N retries"; discard.

**`close()`**:
1. `scheduler.shutdown()`; `scheduler.awaitTermination(5, SECONDS)`.
2. Call `flush()` one final time (drains remaining queue entries).
3. `httpClient` does not require explicit close in Java 21 (it is resource-managed).

**`getProjectId()`** — accessor used by `GCloudReporter.doStart()` for the startup log line.

### Step 4 — JSON serialisation without the SDK

Introduce a single `GCloudEntrySerializer.java` utility class (package-private or package
`writer`) responsible for building the `entries:write` request JSON.  Use Jackson
`ObjectMapper` (compile-scope dep, see Step 6) with a `Map<String, Object>` structure.  This
is straightforward:

```
Map<String, Object> body = new LinkedHashMap<>();
body.put("logName",  "projects/{project}/logs/{logId}");
body.put("resource", Map.of("type", resourceType, "labels", resourceLabels));

List<Map<String, Object>> entries = new ArrayList<>();
for (GCloudLogEntry e : batch) {
    entries.add(entryToMap(e, projectId));
}
body.put("entries", entries);

return objectMapper.writeValueAsString(body);
```

**`entryToMap(GCloudLogEntry e, String projectId)`**:
* `"severity"` → `e.severity().name()` (DEBUG/INFO/WARNING/ERROR)
* `"timestamp"` → `e.timestamp() != null ? e.timestamp().toString() : omit`
* `"trace"` → if non-blank: ensure it is `"projects/{project}/traces/{id}"` — if it doesn't
  start with `"projects/"` prepend `"projects/" + projectId + "/traces/"` automatically.
  This makes the trace prefix config optional when the project ID is known.
* `"spanId"` → `e.spanId()` if non-blank
* `"labels"` → `e.labels()` if non-empty
* `"jsonPayload"` → `e.jsonPayload()`
* `"httpRequest"` → if non-null, build a map via `httpRequestToMap(e.httpRequest())`

**`httpRequestToMap(GCloudHttpRequest r)`**:
* `"requestMethod"` → `r.requestMethod()`
* `"requestUrl"` → `r.requestUrl()`
* `"requestSize"` → `String.valueOf(r.requestSize())` **only if > 0** (string per API spec)
* `"status"` → `r.status()`
* `"responseSize"` → `String.valueOf(r.responseSize())` **only if > 0**
* `"userAgent"` → if non-blank
* `"remoteIp"` → if non-blank
* `"serverIp"` → if non-blank
* `"latency"` → if `r.latencyMs() > 0`: `String.format("%.9fs", r.latencyMs() / 1000.0)`

### Step 5 — Update GCloudReporter

**Fields**: replace `Logging logging` with `GCloudLogWriter writer`; add `@Autowired`.

**`doStart()`**:
* Keep all existing logic (mappers, resource, log name).
* Replace `((LoggingOptions) logging.getOptions()).getProjectId()` with
  `writer.getProjectId()`.

**`doStop()`**:
* Replace `logging.flush()` + `logging.close()` with `writer.flush()` + `writer.close()`.
* Keep the same double try/catch warning pattern.

**`writeEntry(GCloudLogEntry entry)`** (signature change):
* Remove `entry.toBuilder().setLogName(...).setResource(...).build()` — these are now set
  at the writer level (in the JSON serialiser), not per-entry.
* Call `writer.write(entry)`.

**`report()`**: change the mappers' return types from `LogEntry` to `GCloudLogEntry`.
`Optional<LogEntry>` becomes `Optional<GCloudLogEntry>`.  All switch arms are unchanged.

### Step 6 — Update GCloudReporterSpringConfiguration

**Remove**:
* Import of `Logging`, `LoggingOptions`.
* The `loggingClient` bean method.

**Add**:
* Bean method `gCloudLogWriter(GCloudReporterConfiguration cfg)` that:
  1. Loads credentials (same logic as the existing `loggingClient` method: file path → ADC).
  2. Resolves the project ID: use `cfg.getProjectId()` if non-blank, otherwise fall back to
     `System.getenv("GOOGLE_CLOUD_PROJECT")`, then `System.getenv("GCLOUD_PROJECT")`, then
     attempt the GCE metadata endpoint
     `http://metadata.google.internal/computeMetadata/v1/project/project-id` with
     `Metadata-Flavor: Google` header and a 2-second connect timeout.  Throws `IOException`
     with a clear message if still unresolved.
  3. Constructs and returns a `GCloudLogWriter`.

Project ID resolution must happen here (not lazily) so that the startup log line
`"writing to project='...' logName='...'"` is correct, and a misconfigured plugin fails
fast at startup rather than silently at first write.

### Step 7 — Update pom.xml

**Remove**:
* `com.google.cloud:google-cloud-logging` from `compile` scope.
* `maven-shade-plugin` entirely (no longer needed — no gRPC on compile classpath).

**Add**:
* `com.google.auth:google-auth-library-oauth2-http` in `compile` scope (was previously
  transitive via `google-cloud-logging`; must now be explicit).
* `com.fasterxml.jackson.core:jackson-databind` in `compile` scope (was test-only; needed by
  `GCloudEntrySerializer`; version is already managed by the APIM BOM).

**Keep**:
* `com.google.cloud:google-cloud-logging` in **`test` scope** — the integration test's
  `GCloudLoggingClient` still uses the SDK to _read_ entries back from Cloud Logging for
  verification.  It runs in the test JVM (not the gateway container) so gRPC works fine.

**Restore**:
* `maven-dependency-plugin` `copy-dependencies` execution (was removed when shade was
  added).  Reverts to the original assembly where runtime JARs go into `lib/` in the ZIP.

**Assembly descriptor** (`plugin-assembly.xml`):
* Restore the `<fileSets>` block for `target/dependencies → lib/`.

**Net effect on ZIP size**: the current shaded uber-jar is ~45 MB.  With direct HTTP the
bundled dependencies shrink dramatically:
* `google-auth-library-oauth2-http` + `google-auth-library-credentials`
* `google-http-client` (needed by google-auth at runtime for token refresh)
* `jackson-databind` + `jackson-core` + `jackson-annotations`
* Expected total: ~8–10 MB.

### Step 8 — Update unit tests

**GCloudReporterTest**:
* Replace `@Mock Logging logging` with `@Mock GCloudLogWriter writer`.
* The `setUp()` mock of `logging.getOptions()` and `LoggingOptions.getProjectId()` is
  replaced by `when(writer.getProjectId()).thenReturn("test-project")`.
* `verify(logging, times(1)).write(anyCollection())` becomes
  `verify(writer, times(1)).write(any(GCloudLogEntry.class))`.
* `verify(logging, never()).write(anyCollection())` becomes
  `verify(writer, never()).write(any(GCloudLogEntry.class))`.
* All 14 tests survive with these substitutions; no new tests needed here.

**Mapper tests** (MetricsToLogEntryMapperTest, LogToLogEntryMapperTest,
EndpointStatusToLogEntryMapperTest, MessageMetricsToLogEntryMapperTest):
* Return type changes from `LogEntry` to `GCloudLogEntry`.
* Assertions against `entry.getSeverity()`, `entry.getHttpRequest()`, etc. change to
  `entry.severity()`, `entry.httpRequest()` (record accessors).
* Assertions against `Severity.INFO` / `Severity.ERROR` become `GCloudSeverity.INFO` /
  `GCloudSeverity.ERROR`.
* Where tests currently use `entry.getPayload(Payload.JsonPayload.class).getDataAsMap()` to
  inspect payload fields, they now access `entry.jsonPayload()` directly (it's already a
  `Map<String, Object>`).
* All mapper tests survive; no new tests needed for mapping logic.

**New unit test — GCloudEntrySerializerTest**:
* Creates a `GCloudLogEntry` with all fields populated.
* Calls `GCloudEntrySerializer.toJson(List.of(entry), projectId, logName, resourceType, ...)`.
* Parses the resulting JSON string with Jackson and asserts:
  * `entries[0].severity == "INFO"`.
  * `entries[0].timestamp` is a valid RFC 3339 string.
  * `entries[0].httpRequest.requestSize` is a JSON string (not a number).
  * `entries[0].httpRequest.latency` matches `"0.042000000s"`.
  * `entries[0].trace` starts with `"projects/"`.
  * `entries[0].jsonPayload.api_id == "api-123"`.

**New unit test — GCloudLogWriterTest**:
* Mocks the `java.net.http.HttpClient` by injecting a test double that captures requests.
* Tests the retry path: first response is 503, second is 200; verifies two HTTP calls were
  made.
* Tests the queue-full drop path: fill the queue beyond capacity; verify a WARN is logged.
* Tests `close()`: adds entries, calls `close()`, verifies the mock received an HTTP call.

**GCloudLoggingClient** (integration test helper):
* No changes.  It keeps using the `google-cloud-logging` SDK for reading (`test` scope).

### Step 9 — Update GCloudReporterIT

The integration test asserts on `LogEntry` objects returned by `GCloudLoggingClient`.  These
come from the SDK's _read_ path (the test JVM, not the gateway) and are unaffected by the
plugin's transport change.

One assertion needs updating: `assertThat(entry.getTrace()).contains(transactionId)` — this
should still pass because the new serialiser prepends `projects/{project}/traces/` to the
trace value, which contains the transaction ID.  Verify by inspection; no change expected.

---

## Files changed summary

| File | Action |
|---|---|
| `pom.xml` | Remove shade plugin, remove `google-cloud-logging:compile`, add `google-auth-library-oauth2-http:compile`, add `jackson-databind:compile`, add `google-cloud-logging:test`, restore dependency-copy plugin |
| `plugin-assembly.xml` | Restore `lib/` fileSet |
| `writer/GCloudSeverity.java` | **New** — enum |
| `writer/GCloudHttpRequest.java` | **New** — record |
| `writer/GCloudLogEntry.java` | **New** — record |
| `writer/GCloudEntrySerializer.java` | **New** — JSON builder (Jackson) |
| `writer/GCloudLogWriter.java` | **New** — batching + HTTP + retry |
| `spring/GCloudReporterSpringConfiguration.java` | Replace `Logging` bean with `GCloudLogWriter` bean; add project-ID resolution |
| `GCloudReporter.java` | Wire to `GCloudLogWriter`; update `doStart`/`doStop`/`writeEntry` |
| `mapper/MetricsToLogEntryMapper.java` | Return `GCloudLogEntry`; remove SDK imports |
| `mapper/LogToLogEntryMapper.java` | Return `GCloudLogEntry`; remove SDK imports |
| `mapper/EndpointStatusToLogEntryMapper.java` | Return `Optional<GCloudLogEntry>`; remove SDK imports |
| `mapper/MessageMetricsToLogEntryMapper.java` | Return `GCloudLogEntry`; remove SDK imports |
| `GCloudReporterTest.java` | Mock `GCloudLogWriter` instead of `Logging` |
| `mapper/*Test.java` (×4) | Assert on records instead of `LogEntry` |
| `writer/GCloudEntrySerializerTest.java` | **New** — JSON shape tests |
| `writer/GCloudLogWriterTest.java` | **New** — retry + queue tests |
| `integration/GCloudLoggingClient.java` | No change |
| `integration/GCloudReporterIT.java` | No change expected |

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Token refresh blocks gateway threads | Refresh happens on the background flush executor thread (virtual thread), never on the reporter `report()` caller thread.  `report()` only does `queue.offer()`. |
| Queue overflow loses log entries | Bounded queue with `offer()` (non-blocking drop) is intentional; the current SDK also drops entries when the internal buffer is full.  Log at WARN. |
| Credential file unavailable at startup | Fail fast in Spring context creation (throws `IOException`), same as today. |
| Clock skew on `timestamp` | Use `Instant.ofEpochMilli(metrics.getTimestamp())` directly — same source as the SDK today. |
| JSON escape / character encoding | Jackson handles this correctly for all unicode.  The SDK currently uses protobuf JSON encoding; Jackson is equivalent for the subset used here. |
| Integration test `GCloudLoggingClient` needs gRPC | Runs in the test JVM (not the gateway container), so gRPC is available.  Declared `test` scope — never bundled in the plugin ZIP. |
| Latency format precision | Cloud Logging accepts `"0.042s"` and `"0.042000000s"` interchangeably; `String.format("%.9fs", ...)` produces the canonical nanosecond form. |
