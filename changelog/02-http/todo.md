# TODO: Replace google-cloud-logging SDK with direct Cloud Logging REST API

Executable checklist derived from `plan.md`.  Every step ends with a **Verify** block
that must pass before moving to the next step.  Run all commands from the repo root.

Lint command used throughout: `mvn prettier:write -q`
Test command used throughout: `mvn test -q`
Full verify shorthand: `mvn prettier:write -q && mvn test -q`

---

## Step 1 — Create the new data model records

### 1a — Create `GCloudSeverity.java`

Create file:
```
src/main/java/io/gravitee/reporter/gcloud/writer/GCloudSeverity.java
```

Content: a `public enum` in package `io.gravitee.reporter.gcloud.writer` with values
`DEBUG`, `INFO`, `WARNING`, `ERROR`.  No imports needed.

### 1b — Create `GCloudHttpRequest.java`

Create file:
```
src/main/java/io/gravitee/reporter/gcloud/writer/GCloudHttpRequest.java
```

Content: a `public record` in the same package with components:
```
String requestMethod
String requestUrl
long   requestSize
int    status
long   responseSize
String userAgent
String remoteIp
String serverIp
long   latencyMs
```
No imports needed (all primitives + `String`).

### 1c — Create `GCloudLogEntry.java`

Create file:
```
src/main/java/io/gravitee/reporter/gcloud/writer/GCloudLogEntry.java
```

Content: a `public record` in the same package with components:
```
GCloudSeverity     severity
java.time.Instant  timestamp
String             trace
String             spanId
java.util.Map<String,String>  labels
java.util.Map<String,Object>  jsonPayload
GCloudHttpRequest  httpRequest
```

### Verify step 1

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass**, 0 failures.  The new files add no dependencies so the
build is unaffected.

---

## Step 2 — Update `MetricsToLogEntryMapper`

### Changes

* Remove imports of `com.google.cloud.logging.{HttpRequest, LogEntry, Payload, Severity}`.
* Add imports of `io.gravitee.reporter.gcloud.writer.{GCloudSeverity, GCloudHttpRequest,
  GCloudLogEntry}` and `java.time.Instant`.
* Change return type of `map(Metrics)` from `LogEntry` to `GCloudLogEntry`.
* Replace `Severity` references with `GCloudSeverity`.
* Replace `HttpRequest.newBuilder()...build()` with a `GCloudHttpRequest(...)` record
  constructor call.
* Replace `Payload.JsonPayload.of(payloadFields)` — pass `payloadFields` directly as the
  `jsonPayload` component of `GCloudLogEntry`.
* Replace `LogEntry.newBuilder(...).setSeverity(...).setHttpRequest(...).setLabels(...)
  .setTrace(...).setSpanId(...).setTimestamp(...).build()` with a `GCloudLogEntry(...)`
  record constructor call.
* `buildTrace`, `sanitizePath`, `resolveSeverity`, `buildLabels`, `ID_PATTERN` — keep
  unchanged.
* For `timestamp`: `metrics.getTimestamp() > 0 ? Instant.ofEpochMilli(metrics.getTimestamp()) : null`.
* For `latencyMs` in `GCloudHttpRequest`: pass `metrics.getGatewayResponseTimeMs()` (already `long`).
* For `requestMethod` in `GCloudHttpRequest`: `metrics.getHttpMethod() != null ? metrics.getHttpMethod().name() : null`.

### Verify step 2

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass** (MetricsToLogEntryMapperTest will fail to compile until
step 8a updates it — so do steps 2–5 for all mappers before running the verify, OR
update the corresponding test immediately after each mapper change.  The safer path
is to update the test in the same step).

### Also update `MetricsToLogEntryMapperTest` in this step

* Change `LogEntry entry = mapper.map(...)` to `GCloudLogEntry entry = mapper.map(...)`.
* Replace `entry.getSeverity()` → `entry.severity()`.
* Replace `Severity.INFO` / `Severity.ERROR` / `Severity.WARNING` → `GCloudSeverity.*`.
* Replace `entry.getHttpRequest()` → `entry.httpRequest()`.
* Replace `entry.getHttpRequest().getRequestMethod()` → `entry.httpRequest().requestMethod()`
  (now a `String`, compare with `"GET"` etc. directly).
* Replace `entry.getHttpRequest().getLatencyDuration()` → `entry.httpRequest().latencyMs()`
  (now a `long`, assert `> 0`).
* Replace `entry.getPayload(Payload.JsonPayload.class).getDataAsMap().get("status")` →
  `entry.jsonPayload().get("status")`.
* Replace `entry.getTrace()` → `entry.trace()`.
* Replace `entry.getSpanId()` → `entry.spanId()`.
* Replace `entry.getTimestamp()` → `entry.timestamp()` (now `Instant`).

### Verify step 2 (with test update)

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass**.

---

## Step 3 — Update `LogToLogEntryMapper`

### Changes

* Remove imports of `com.google.cloud.logging.{LogEntry, Payload, Severity}`.
* Add imports of `io.gravitee.reporter.gcloud.writer.{GCloudSeverity, GCloudLogEntry}`.
* Change return type of `map(Log)` from `LogEntry` to `GCloudLogEntry`.
* Replace `Severity.DEBUG` → `GCloudSeverity.DEBUG`.
* Replace `Payload.JsonPayload.of(payload)` — pass `payload` map directly.
* Replace `LogEntry.newBuilder(...)...build()` → `GCloudLogEntry(...)` constructor.
* No `httpRequest` field for Log entries → pass `null`.
* `MAX_BODY_LENGTH`, `truncate()`, `requestPayload()`, `responsePayload()`,
  `buildTrace()` — keep unchanged.
* For `timestamp`: `GCloudLogEntry` record — pass `null` (Log doesn't carry a timestamp
  field; Cloud Logging will timestamp on receipt, matching current SDK behaviour).

### Also update `LogToLogEntryMapperTest`

* Change `LogEntry entry = mapper.map(...)` to `GCloudLogEntry entry = mapper.map(...)`.
* Replace `entry.getSeverity()` → `entry.severity()` and compare with `GCloudSeverity.DEBUG`.
* Replace payload inspection via `getPayload(...)` → `entry.jsonPayload().get(...)`.
* Replace `entry.getTrace()` → `entry.trace()`.

### Verify step 3

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass**.

---

## Step 4 — Update `EndpointStatusToLogEntryMapper`

### Changes

* Remove imports of `com.google.cloud.logging.{LogEntry, Payload, Severity}`.
* Add imports of `io.gravitee.reporter.gcloud.writer.{GCloudSeverity, GCloudLogEntry}`.
* Change return type of `map(EndpointStatus)` from `Optional<LogEntry>` to
  `Optional<GCloudLogEntry>`.
* Replace `Severity.INFO` / `Severity.ERROR` → `GCloudSeverity.INFO` / `GCloudSeverity.ERROR`.
* Replace `Payload.JsonPayload.of(payload)` — pass `payload` map directly.
* Replace `LogEntry.newBuilder(...)...build()` → `GCloudLogEntry(...)` constructor.
* No `httpRequest`, no `trace`, no `spanId`, no `timestamp` for health checks →
  pass `null` for each.
* Transition guard, step stream, label building — keep unchanged.

### Also update `EndpointStatusToLogEntryMapperTest`

* Change return type in all assertions from `Optional<LogEntry>` to `Optional<GCloudLogEntry>`.
* Replace `entry.getSeverity()` → `entry.severity()` and compare with `GCloudSeverity.*`.
* Replace payload inspection → `entry.jsonPayload().get(...)`.
* Replace label inspection: `entry.getLabels()` → `entry.labels()`.

### Verify step 4

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass**.

---

## Step 5 — Update `MessageMetricsToLogEntryMapper`

### Changes

* Remove imports of `com.google.cloud.logging.{LogEntry, Payload, Severity}`.
* Add imports of `io.gravitee.reporter.gcloud.writer.{GCloudSeverity, GCloudLogEntry}`.
* Change return type of `map(MessageMetrics)` from `LogEntry` to `GCloudLogEntry`.
* Replace `Severity.INFO` → `GCloudSeverity.INFO`.
* Replace `Payload.JsonPayload.of(payload)` — pass `payload` map directly.
* Replace `LogEntry.newBuilder(...)...build()` → `GCloudLogEntry(...)` constructor.
* No `httpRequest`, no `timestamp` → pass `null` for each.
* `trace`: keep the `metrics.getRequestId()` assignment unchanged; pass as `trace`
  component of the record.
* Connector type, labels — keep unchanged.

### Also update `MessageMetricsToLogEntryMapperTest`

* Change `LogEntry entry = mapper.map(...)` to `GCloudLogEntry entry = mapper.map(...)`.
* Replace `entry.getSeverity()` → `entry.severity()` and compare with `GCloudSeverity.INFO`.
* Replace payload inspection → `entry.jsonPayload().get(...)`.
* Replace `entry.getTrace()` → `entry.trace()`.
* Replace label inspection → `entry.labels()`.

### Verify step 5

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass**.

---

## Step 6 — Update `GCloudReporter`

### Changes

* Remove import of `com.google.cloud.logging.{LogEntry, Logging, LoggingOptions}` and
  `com.google.cloud.MonitoredResource`.
* Add import of `io.gravitee.reporter.gcloud.writer.{GCloudLogEntry, GCloudLogWriter}`.
* Remove the `MonitoredResource resource` field and `String logName` field (these are now
  owned by `GCloudLogWriter`).
* Remove `@Autowired private Logging logging` — replace with
  `@Autowired private GCloudLogWriter writer`.
* In `doStart()`:
  * Remove `MonitoredResource.newBuilder(...)` block.
  * Remove `logName = cfg.getLogName()`.
  * Replace `((LoggingOptions) logging.getOptions()).getProjectId()` with
    `writer.getProjectId()`.
* In `doStop()`:
  * Replace `logging.flush()` with `writer.flush()`.
  * Replace `logging.close()` with `writer.close()`.
* Rename `writeEntry(LogEntry entry)` to `writeEntry(GCloudLogEntry entry)`.
  * Remove the `entry.toBuilder().setLogName(logName).setResource(resource).build()` block.
  * Replace `logging.write(List.of(withResource))` with `writer.write(entry)`.
  * Remove `import java.util.List`.
* In `report()`:
  * Change `Optional<LogEntry>` to `Optional<GCloudLogEntry>` for the EndpointStatus arm.
  * Change `LogEntry` references to `GCloudLogEntry` in the other arms.

### Also update `GCloudReporterTest`

* Remove import of `com.google.cloud.logging.{Logging, LoggingOptions}`.
* Add import of `io.gravitee.reporter.gcloud.writer.{GCloudLogEntry, GCloudLogWriter}`.
* Replace `@Mock private Logging logging` with `@Mock private GCloudLogWriter writer`.
* Replace `inject(reporter, "logging", logging)` with `inject(reporter, "writer", writer)`.
* Remove the `LoggingOptions opts = mock(...)` block in `setUp()`.
* Add `when(writer.getProjectId()).thenReturn("test-project")` in `setUp()`.
* Replace all `verify(logging, times(1)).write(anyCollection())` with
  `verify(writer, times(1)).write(any(GCloudLogEntry.class))`.
* Replace all `verify(logging, never()).write(anyCollection())` with
  `verify(writer, never()).write(any(GCloudLogEntry.class))`.

### Verify step 6

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass**.  `GCloudReporter` still compiles because `GCloudLogWriter`
is not yet a real class — this step will only compile once Step 7 (writer implementation)
is done.  **Perform steps 6 and 7 together before running the verify.**

---

## Step 7 — Implement `GCloudEntrySerializer` and `GCloudLogWriter`

### 7a — Add `jackson-databind` to `pom.xml` (compile scope)

In `pom.xml`, in the `<dependencies>` section under `<!-- BUNDLED -->`, add:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <!-- version managed by gravitee-apim-bom -->
</dependency>
```

Do NOT add a `<scope>` element (defaults to compile).

### 7b — Create `GCloudEntrySerializer.java`

Create file:
```
src/main/java/io/gravitee/reporter/gcloud/writer/GCloudEntrySerializer.java
```

Package-private class.  Single public static method:

```java
static String toJson(
    List<GCloudLogEntry> batch,
    String projectId,
    String logName,      // already the full "projects/P/logs/N" string
    String resourceType,
    Map<String, String> resourceLabels,
    ObjectMapper mapper) throws JsonProcessingException
```

Internal helpers:
* `entryToMap(GCloudLogEntry e, String projectId)` — builds a `LinkedHashMap<String,Object>`:
  * `"severity"` → `e.severity().name()`
  * `"timestamp"` → `e.timestamp() != null ? e.timestamp().toString() : absent` (use
    `put` only when non-null; never put a null value).
  * `"trace"` → if `e.trace()` is non-blank: if it already starts with `"projects/"` use
    as-is, else prepend `"projects/" + projectId + "/traces/"`.
  * `"spanId"` → if `e.spanId()` is non-blank.
  * `"labels"` → `e.labels()` if non-empty map.
  * `"jsonPayload"` → `e.jsonPayload()`.
  * `"httpRequest"` → `httpRequestToMap(e.httpRequest())` if non-null.
* `httpRequestToMap(GCloudHttpRequest r)` — builds a `LinkedHashMap<String,Object>`:
  * `"requestMethod"` → `r.requestMethod()` if non-null.
  * `"requestUrl"` → `r.requestUrl()` if non-null.
  * `"requestSize"` → `String.valueOf(r.requestSize())` only if `r.requestSize() > 0`.
  * `"status"` → `r.status()`.
  * `"responseSize"` → `String.valueOf(r.responseSize())` only if `r.responseSize() > 0`.
  * `"userAgent"` → if non-blank.
  * `"remoteIp"` → if non-blank.
  * `"serverIp"` → if non-blank.
  * `"latency"` → `String.format("%.9fs", r.latencyMs() / 1000.0)` only if
    `r.latencyMs() > 0`.

Top-level body map:
```
{ "logName": logName,
  "resource": { "type": resourceType, "labels": resourceLabels },
  "entries": [ ...entryToMap results... ] }
```

### 7c — Create `GCloudLogWriter.java`

Create file:
```
src/main/java/io/gravitee/reporter/gcloud/writer/GCloudLogWriter.java
```

Public class.  Fields:
```java
private final GoogleCredentials credentials;
private final String projectId;
private final String logName;        // "projects/P/logs/N"
private final String resourceType;
private final Map<String, String> resourceLabels;
private final int batchSize;
private final ArrayBlockingQueue<GCloudLogEntry> queue;
private final HttpClient httpClient;
private final ScheduledExecutorService scheduler;
private final ObjectMapper mapper = new ObjectMapper();
private static final String WRITE_URL =
    "https://logging.googleapis.com/v2/entries:write";
private static final Logger log = LoggerFactory.getLogger(GCloudLogWriter.class);
```

Constructor:
```java
public GCloudLogWriter(
    GoogleCredentials credentials,
    String projectId,
    String logName,
    String resourceType,
    Map<String, String> resourceLabels,
    int batchSize,
    int flushIntervalSeconds)
```
1. Store all fields.
2. `this.logName = "projects/" + projectId + "/logs/" + logName`.
3. `this.queue = new ArrayBlockingQueue<>(Math.max(500, batchSize * 5))`.
4. `this.httpClient = HttpClient.newBuilder()
       .executor(Executors.newVirtualThreadPerTaskExecutor())
       .connectTimeout(Duration.ofSeconds(10))
       .build()`.
5. `this.scheduler = Executors.newSingleThreadScheduledExecutor(
       r -> Thread.ofVirtual().name("gcloud-reporter-flush").unstarted(r))`.
6. `scheduler.scheduleAtFixedRate(this::flushSafe, flushIntervalSeconds,
       flushIntervalSeconds, TimeUnit.SECONDS)`.

`public String getProjectId()`: return `projectId`.

`public void write(GCloudLogEntry entry)`:
1. `if (!queue.offer(entry)) { log.warn("GCloud log queue full, dropping entry"); return; }`.
2. `if (queue.size() >= batchSize) { scheduler.submit(this::flushSafe); }`.

`private void flushSafe()`:
```java
try { flush(); } catch (Exception e) { log.warn("Error flushing GCloud log entries", e); }
```

`public void flush()`:
1. Loop while queue is non-empty:
   a. Drain up to `batchSize` entries into `List<GCloudLogEntry> batch = new ArrayList<>()`
      via `queue.drainTo(batch, batchSize)`.
   b. If `batch.isEmpty()` break.
   c. `sendWithRetry(batch)`.

`private void sendWithRetry(List<GCloudLogEntry> batch)`:
1. `credentials.refreshIfExpired()`.
2. `String token = credentials.getAccessToken().getTokenValue()`.
3. `String body = GCloudEntrySerializer.toJson(batch, projectId, logName, resourceType,
       resourceLabels, mapper)`.
4. `HttpRequest request = HttpRequest.newBuilder()
       .uri(URI.create(WRITE_URL))
       .header("Authorization", "Bearer " + token)
       .header("Content-Type", "application/json")
       .POST(HttpRequest.BodyPublishers.ofString(body))
       .timeout(Duration.ofSeconds(30))
       .build()`.
5. Retry loop (max 3 attempts, `int attempt = 0`):
   a. `HttpResponse<String> resp = httpClient.send(request, BodyHandlers.ofString())`.
   b. `int status = resp.statusCode()`.
   c. If `status == 200`: return.
   d. If `status == 429`:
      * Extract `Retry-After` header; parse as seconds (default to `2 << attempt` if absent).
      * `Thread.sleep(Duration.ofSeconds(retryAfter))`.
      * Increment attempt; continue loop.
   e. If `status == 500 || status == 503`:
      * `long backoffMs = (1000L << attempt) + ThreadLocalRandom.current().nextLong(-250, 250)`.
      * `Thread.sleep(Duration.ofMillis(backoffMs))`.
      * Increment attempt; continue loop.
   f. Otherwise (4xx other than 429):
      * `log.warn("GCloud Logging write rejected (HTTP {}): {} — dropping {} entries",
            status, resp.body().substring(0, Math.min(200, resp.body().length())),
            batch.size())`.
      * Return (non-retryable).
6. If loop exhausted: `log.warn("Failed to send {} log entries after 3 retries", batch.size())`.

`public void close()`:
1. `scheduler.shutdown()`.
2. Try `scheduler.awaitTermination(5, TimeUnit.SECONDS)`.
3. `flush()` — final drain.

### Verify step 7

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass** (the new classes are not yet exercised by unit tests —
those come in step 8).

---

## Step 8 — Update `GCloudReporterSpringConfiguration`

### Changes

* Remove imports of `com.google.cloud.logging.{Logging, LoggingOptions}`.
* Add imports of `com.google.auth.oauth2.GoogleCredentials`,
  `io.gravitee.reporter.gcloud.writer.GCloudLogWriter`,
  `java.net.http.HttpClient`, `java.net.http.HttpRequest`, `java.net.http.HttpResponse`,
  `java.net.URI`, `java.time.Duration`.
* Remove the `loggingClient(GCloudReporterConfiguration cfg)` bean method.
* Add bean method `gCloudLogWriter(GCloudReporterConfiguration cfg) throws IOException`:

```
1. Load credentials:
   if cfg.getCredentialsFile() is non-blank:
     GoogleCredentials.fromStream(new FileInputStream(cfg.getCredentialsFile()))
       .createScoped("https://www.googleapis.com/auth/logging.write")
   else:
     GoogleCredentials.getApplicationDefault()
       .createScoped("https://www.googleapis.com/auth/logging.write")

2. Resolve project ID:
   String projectId = cfg.getProjectId();
   if blank: projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
   if blank: projectId = System.getenv("GCLOUD_PROJECT");
   if blank: attempt GCE metadata HTTP call:
     HttpRequest metaReq = HttpRequest.newBuilder()
         .uri(URI.create(
             "http://metadata.google.internal/computeMetadata/v1/project/project-id"))
         .header("Metadata-Flavor", "Google")
         .timeout(Duration.ofSeconds(2))
         .build();
     try {
       HttpResponse<String> metaResp = HttpClient.newBuilder()
           .connectTimeout(Duration.ofSeconds(2)).build()
           .send(metaReq, HttpResponse.BodyHandlers.ofString());
       if (metaResp.statusCode() == 200) projectId = metaResp.body().trim();
     } catch (Exception ignored) {}
   if still blank: throw new IOException(
     "GCloud reporter: project ID not configured and could not be determined " +
     "from environment or GCE metadata")

3. return new GCloudLogWriter(
       credentials, projectId, cfg.getLogName(),
       cfg.getResourceType(), cfg.getResourceLabels(),
       cfg.getBatchSize(), cfg.getFlushIntervalSeconds())
```

### Also add two new fields to `GCloudReporterConfiguration`

```java
@Value("${reporters.gcloud.batchSize:${reporters.gcloud.batchsize:100}}")
private int batchSize;

@Value("${reporters.gcloud.flushIntervalSeconds:${reporters.gcloud.flushintervalseconds:5}}")
private int flushIntervalSeconds;
```

Add getters `getBatchSize()` and `getFlushIntervalSeconds()`.

### Verify step 8

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **59 tests pass**.  The Spring configuration compiles; the `Logging` bean is
gone and replaced by `GCloudLogWriter`.

---

## Step 9 — Add new unit tests

### 9a — Create `GCloudEntrySerializerTest.java`

Create file:
```
src/test/java/io/gravitee/reporter/gcloud/writer/GCloudEntrySerializerTest.java
```

Tests (use JUnit 5 + AssertJ + Jackson `ObjectMapper` already on test classpath):

1. `severityIsSerializedAsUppercaseString` — entry with `GCloudSeverity.INFO`, assert
   parsed JSON `entries[0].severity == "INFO"`.
2. `timestampIsRfc3339` — entry with a known `Instant`, assert `entries[0].timestamp`
   parses back to the same instant via `Instant.parse(...)`.
3. `requestSizeIsJsonString` — entry with `httpRequest.requestSize = 128`, assert the
   JSON value at `entries[0].httpRequest.requestSize` is a JSON string `"128"`, not the
   number `128`.
4. `latencyIsFormattedAsProtoducationString` — entry with `latencyMs = 42`, assert
   `entries[0].httpRequest.latency == "0.042000000s"`.
5. `traceGetsProjectPrefixWhenMissing` — entry with `trace = "txn-abc"`, assert
   `entries[0].trace == "projects/my-proj/traces/txn-abc"`.
6. `traceIsNotPrefixedWhenAlreadyFullPath` — entry with
   `trace = "projects/my-proj/traces/txn-abc"`, assert value is unchanged.
7. `jsonPayloadFieldsAreInlined` — entry with `jsonPayload = Map.of("api_id", "api-123")`,
   assert `entries[0].jsonPayload.api_id == "api-123"`.
8. `nullFieldsAreOmitted` — entry with `timestamp = null`, `httpRequest = null`,
   assert the serialised JSON contains neither `"timestamp"` nor `"httpRequest"` key.
9. `emptyLabelsAreOmitted` — entry with `labels = Map.of()`, assert no `"labels"` key in
   the entry JSON.

### 9b — Create `GCloudLogWriterTest.java`

Create file:
```
src/test/java/io/gravitee/reporter/gcloud/writer/GCloudLogWriterTest.java
```

Strategy: do NOT mock `java.net.http.HttpClient` (it is `final`).  Instead, inject a
custom `HttpClient` subclass or use a local `HttpServer` (Java built-in
`com.sun.net.httpserver.HttpServer`) to capture requests.  Alternatively, extract
the HTTP-send logic into a `@FunctionalInterface HttpSender` that is injected at
construction time (a single package-private constructor overload used only in tests).

Tests:

1. `writtenEntryIsQueuedAndFlushedToHttpEndpoint` — create a writer with an in-process
   HTTP server on a random port, `write(entry)`, call `flush()`, assert the server
   received exactly one POST to `/v2/entries:write` with `Authorization: Bearer test-token`.
2. `retryOn503ThenSucceed` — configure the in-process server to return 503 on the first
   call and 200 on the second; assert the server received two requests.
3. `noRetryOn400` — configure server to return 400; assert only one request received and
   no exception thrown.
4. `queueFullDropsEntry` — create a writer with `batchSize=1` and queue capacity 1;
   fill the queue, then `write()` one more entry; assert WARN is logged (use a Logback
   `ListAppender`) and queue size is still 1.
5. `closeFlushesRemainingEntries` — write 2 entries, call `close()`, assert the server
   received exactly one POST containing both entries.
6. `flushIsNoOpWhenQueueIsEmpty` — call `flush()` on an empty writer; assert server
   received zero requests.

### Verify step 9

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **all tests pass** (the exact count increases from 59 by however many new
tests are added).

---

## Step 10 — Update `pom.xml` (remove shade, remove compile SDK, add auth + jackson)

### Changes to `pom.xml`

1. **Remove** the `maven-shade-plugin` `<plugin>` block entirely.

2. **Remove** the `google-cloud-logging` compile-scope dependency:
   ```xml
   <!-- DELETE this block -->
   <dependency>
       <groupId>com.google.cloud</groupId>
       <artifactId>google-cloud-logging</artifactId>
       <version>${google-cloud-logging.version}</version>
   </dependency>
   ```

3. **Add** the following two compile-scope dependencies in the `<!-- BUNDLED -->` section:
   ```xml
   <dependency>
       <groupId>com.google.auth</groupId>
       <artifactId>google-auth-library-oauth2-http</artifactId>
       <!-- version managed by gravitee-apim-bom or google-cloud BOM -->
   </dependency>

   <dependency>
       <groupId>com.fasterxml.jackson.core</groupId>
       <artifactId>jackson-databind</artifactId>
       <!-- version managed by gravitee-apim-bom -->
   </dependency>
   ```
   (The `jackson-databind` compile entry from Step 7a is already present; do not duplicate it.)

4. **Add** the `google-cloud-logging` dependency in **test scope** (for
   `GCloudLoggingClient`):
   ```xml
   <dependency>
       <groupId>com.google.cloud</groupId>
       <artifactId>google-cloud-logging</artifactId>
       <version>${google-cloud-logging.version}</version>
       <scope>test</scope>
   </dependency>
   ```

5. **Restore** the `maven-dependency-plugin` execution (removed when shade was added):
   ```xml
   <plugin>
       <groupId>org.apache.maven.plugins</groupId>
       <artifactId>maven-dependency-plugin</artifactId>
       <executions>
           <execution>
               <id>copy-dependencies</id>
               <phase>prepare-package</phase>
               <goals>
                   <goal>copy-dependencies</goal>
               </goals>
               <configuration>
                   <outputDirectory>${project.build.directory}/dependencies</outputDirectory>
                   <includeScope>runtime</includeScope>
                   <excludeScope>provided</excludeScope>
               </configuration>
           </execution>
       </executions>
   </plugin>
   ```

### Update `plugin-assembly.xml`

Restore the `<fileSets>` block:
```xml
<!-- Bundled runtime dependencies under lib/ -->
<fileSets>
    <fileSet>
        <directory>${project.build.directory}/dependencies</directory>
        <outputDirectory>lib</outputDirectory>
    </fileSet>
</fileSets>
```

### Verify step 10 — compile and test

```bash
mvn prettier:write -q && mvn test -q
```

Expected: **all tests pass**.  The `google-cloud-logging` classes are still on the
test classpath (test scope), so `GCloudLoggingClient` compiles.  The main source tree
has no more `com.google.cloud.logging.*` imports.

### Verify step 10 — check no SDK classes leak into compile scope

```bash
mvn dependency:list | grep "google-cloud-logging"
```

Expected output shows `google-cloud-logging:...:test` (test scope only).

### Verify step 10 — build the ZIP

```bash
mvn package -DskipTests
ls -lh target/gravitee-reporter-gcloud-*.zip
unzip -l target/gravitee-reporter-gcloud-*.zip | grep "grpc"
```

Expected:
* ZIP exists and is **≤ 15 MB** (down from ~45 MB).
* The `unzip -l | grep grpc` output is **empty** — no gRPC JARs in the ZIP.

### Verify step 10 — check for leftover SDK imports in main sources

```bash
grep -r "com.google.cloud.logging" src/main/java
```

Expected: **no output** (zero matches).

---

## Step 11 — Final full verification

```bash
mvn clean verify -DskipTests=false
```

Expected: `BUILD SUCCESS`, all unit tests green.

Then confirm the ZIP size and contents one final time:

```bash
unzip -l target/gravitee-reporter-gcloud-*.zip
```

Expected structure:
```
gravitee-reporter-gcloud-1.0.0-SNAPSHOT.jar   (plugin classes only, ~30 KB)
lib/
  google-auth-library-oauth2-http-*.jar
  google-auth-library-credentials-*.jar
  google-http-client-*.jar
  jackson-databind-*.jar
  jackson-core-*.jar
  jackson-annotations-*.jar
  (a handful of small transitive deps from google-auth)
```

No `grpc-*.jar`, no `google-cloud-core*.jar`, no `proto-google-*.jar`.

---

## Quick-reference command cheatsheet

| Action | Command |
|---|---|
| Format + test | `mvn prettier:write -q && mvn test -q` |
| Just test | `mvn test -q` |
| Build ZIP (skip tests) | `mvn package -DskipTests` |
| List ZIP contents | `unzip -l target/gravitee-reporter-gcloud-*.zip` |
| Check for gRPC in ZIP | `unzip -l target/gravitee-reporter-gcloud-*.zip \| grep grpc` |
| Check for SDK imports | `grep -r "com.google.cloud.logging" src/main/java` |
| Integration test | `mvn verify -Pintegration-test` |
