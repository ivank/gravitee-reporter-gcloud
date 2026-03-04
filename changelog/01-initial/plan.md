# gravitee-reporter-gcloud — Implementation Plan

A Gravitee APIM reporter plugin that forwards gateway telemetry to **Google Cloud Logging** as structured log entries, populating native GCL fields: `HttpRequest`, `trace`, `spanId`, `severity`, `labels`, and `resource`.

Modelled after [gravitee-reporter-sentry](https://github.com/ivank/gravitee-reporter-sentry) (APIM 4.9, Java 21, Testcontainers integration tests).

---

## 1. Repository Layout

```
gravitee-reporter-gcloud/
├── pom.xml
├── local.properties.template          # GCP project / credentials for local IT runs
├── CHANGELOG.md
├── README.md
├── changelog/
│   └── 01-initial/
│       └── plan.md                    # this file
└── src/
    ├── main/
    │   ├── assembly/
    │   │   └── plugin-assembly.xml    # produces the deployable ZIP
    │   ├── java/io/gravitee/reporter/gcloud/
    │   │   ├── GCloudReporter.java
    │   │   ├── config/
    │   │   │   └── GCloudReporterConfiguration.java
    │   │   ├── mapper/
    │   │   │   ├── MetricsToLogEntryMapper.java
    │   │   │   ├── LogToLogEntryMapper.java
    │   │   │   ├── EndpointStatusToLogEntryMapper.java
    │   │   │   ├── MessageMetricsToLogEntryMapper.java
    │   │   │   └── GCloudLabels.java
    │   │   └── spring/
    │   │       └── GCloudReporterSpringConfiguration.java
    │   └── resources/
    │       ├── plugin.properties
    │       ├── gravitee.json          # JSON Schema for the plugin UI
    │       └── assembly/
    │           └── plugin-assembly.xml
    └── test/
        ├── java/io/gravitee/reporter/gcloud/
        │   ├── GCloudReporterTest.java
        │   ├── mapper/
        │   │   ├── MetricsToLogEntryMapperTest.java
        │   │   ├── LogToLogEntryMapperTest.java
        │   │   ├── EndpointStatusToLogEntryMapperTest.java
        │   │   ├── MessageMetricsToLogEntryMapperTest.java
        │   │   └── GCloudTestSupport.java
        │   └── integration/
        │       ├── GCloudLoggingClient.java
        │       ├── GraviteeManagementApi.java
        │       ├── ManagementApiHelper.java
        │       └── GCloudReporterIT.java
        └── resources/
            ├── gravitee-embedded.yml
            └── gravitee.yml
```

---

## 2. Maven Build (`pom.xml`)

### Coordinates & Parent

```xml
<groupId>io.gravitee.reporter</groupId>
<artifactId>gravitee-reporter-gcloud</artifactId>
<version>1.0.0-SNAPSHOT</version>
```

Target **Gravitee APIM 4.9+**, **Java 21**.

### Key dependencies

| Scope | Artifact | Notes |
|---|---|---|
| provided | `io.gravitee.reporter:gravitee-reporter-api:2.1.0` | Reporter + Reportable interfaces |
| provided | `io.gravitee.common:gravitee-common` | AbstractService |
| provided | `io.gravitee.node:gravitee-node-api` | Monitor reportable |
| provided | `org.springframework:spring-context` | @Autowired, @Configuration |
| provided | `org.slf4j:slf4j-api` | Logging facade |
| **bundled** | `com.google.cloud:google-cloud-logging:3.x` | GCL Java client |
| **bundled** | `com.google.auth:google-auth-library-oauth2-http` | ADC / service account |
| test | `org.junit.jupiter:junit-jupiter:5.x` | |
| test | `org.mockito:mockito-core:5.x` | |
| test | `org.testcontainers:testcontainers` | Docker-based IT |
| test | `org.testcontainers:mongodb` | APIM dependency |
| test | `com.squareup.retrofit2:retrofit:2.x` | Management API HTTP client |
| test | `org.awaitility:awaitility` | Async polling in IT |
| test | `com.fasterxml.jackson.core:jackson-databind` | JSON in tests |

### Build plugins

- `maven-compiler-plugin` — Java 21
- `maven-assembly-plugin` — produces `gravitee-reporter-gcloud-<version>.zip` with bundled deps
- `maven-surefire-plugin` — standard unit tests
- `maven-failsafe-plugin` — integration tests activated by `-Pintegration-test`
- `maven-resources-plugin` — filter `plugin.properties` to inject `${project.version}` etc.

---

## 3. Plugin Descriptor (`plugin.properties`)

```properties
id=gcloud
name=${project.name}
version=${project.version}
description=${project.description}
class=io.gravitee.reporter.gcloud.GCloudReporter
type=reporter
```

---

## 4. Configuration Schema (`gravitee.json`)

JSON Schema (draft-07) exposing the following fields for the APIM console UI:

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `true` | Activates the reporter |
| `projectId` | string | — | **Required.** GCP project ID |
| `logName` | string | `gravitee-gateway` | Cloud Logging log name |
| `resource.type` | string | `global` | Monitored resource type (`global`, `gce_instance`, `k8s_container`, …) |
| `resource.labels` | object | `{}` | Key/value pairs added to the MonitoredResource |
| `credentialsFile` | string | — | Path to service-account JSON key (omit to use ADC) |
| `captureErrors` | boolean | `true` | HTTP 5xx → `Severity.ERROR`; otherwise `Severity.INFO` |
| `reportHealthChecks` | boolean | `true` | Report endpoint health-check transitions |
| `reportLogs` | boolean | `false` | Report full request/response bodies (high volume) |
| `reportMessageMetrics` | boolean | `true` | Report async message metrics |
| `reportMonitor` | boolean | `false` | Report gateway node monitor events |
| `tracePrefix` | string | — | Optional prefix prepended to `trace` field (e.g. GCP project path) |

---

## 5. `GCloudReporterConfiguration.java`

A plain Spring `@Configuration` bean bound to `reporters.gcloud.*` in `gravitee.yml`.
Provides typed getters for all fields listed in §4.

---

## 6. `GCloudReporterSpringConfiguration.java`

Creates the Spring application context for the plugin:

```java
@Configuration
public class GCloudReporterSpringConfiguration {
    @Bean
    public GCloudReporterConfiguration configuration() { ... }

    @Bean
    public Logging loggingClient(GCloudReporterConfiguration cfg) {
        // builds com.google.cloud.logging.Logging via LoggingOptions
        // uses credentialsFile if set, else ADC
    }
}
```

---

## 7. `GCloudReporter.java`

```
extends AbstractService implements Reporter
```

### Lifecycle

- `doStart()` — obtain `Logging` bean; build shared `MonitoredResource`; construct mappers
- `doStop()` — call `logging.flush()` then `logging.close()`

### `canHandle(Reportable r)`

| Reportable type | Condition |
|---|---|
| `Metrics` | always |
| `v4.log.Log` | `isReportLogs()` |
| `v4.metric.MessageMetrics` (or `MessageMetrics`) | `isReportMessageMetrics()` |
| `EndpointStatus` | `isReportHealthChecks()` |
| `Monitor` | `isReportMonitor()` |
| anything else | `false` |
| — | `false` when reporter is disabled |

### `report(Reportable r)`

Pattern-match on the type and delegate to the appropriate mapper, then call `logging.write(List.of(entry))`.
Wrap in a try-catch; log a warning on failure — never throw.

### Async flushing

Use the GCL client's built-in background write batching (it buffers in memory and flushes automatically). Call `logging.flush()` during `doStop()` to drain pending entries.

---

## 8. Mapper Classes

All mappers receive `GCloudReporterConfiguration` and produce a `LogEntry`.

### 8.1 `MetricsToLogEntryMapper`

Maps `io.gravitee.reporter.api.v4.metric.Metrics` (or v2 `Metrics`):

**`LogEntry` fields set:**

| GCL field | Source |
|---|---|
| `severity` | `DEFAULT` → `INFO`; 5xx → `ERROR` (if `captureErrors`); 4xx → `WARNING` |
| `timestamp` | `metrics.getTimestamp()` |
| `httpRequest.requestMethod` | `metrics.getHttpMethod()` |
| `httpRequest.requestUrl` | scheme + host + `metrics.getUri()` |
| `httpRequest.requestSize` | `metrics.getRequestContentLength()` |
| `httpRequest.status` | `metrics.getStatus()` |
| `httpRequest.responseSize` | `metrics.getResponseContentLength()` |
| `httpRequest.userAgent` | `metrics.getUserAgent()` |
| `httpRequest.remoteIp` | `metrics.getRemoteAddress()` |
| `httpRequest.serverIp` | `metrics.getLocalAddress()` |
| `httpRequest.latency` | `Duration.ofMillis(metrics.getProxyResponseTimeMs())` |
| `trace` | `tracePrefix + metrics.getTransactionId()` |
| `spanId` | `metrics.getRequestId()` |
| `labels["gravitee.api_id"]` | `metrics.getApi()` |
| `labels["gravitee.api_name"]` | `metrics.getApiName()` |
| `labels["gravitee.application"]` | `metrics.getApplication()` |
| `labels["gravitee.plan"]` | `metrics.getPlan()` (if present) |
| `labels["gravitee.endpoint"]` | `metrics.getEndpoint()` |
| `labels["gravitee.gateway_ms"]` | `metrics.getProxyResponseTimeMs()` |
| `labels["gravitee.latency_ms"]` | `metrics.getProxyLatencyMs()` |
| `labels["gravitee.endpoint_ms"]` | `metrics.getApiResponseTimeMs()` |
| `payload` (JSON) | Structured JSON payload with all above for full-text search |

Path sanitization (same pattern as sentry reporter): replace numeric IDs and UUIDs in `uri` with `{id}` to reduce cardinality in log-based metrics.

### 8.2 `LogToLogEntryMapper`

Maps `io.gravitee.reporter.api.v4.log.Log`:

- Severity: `DEBUG`
- Payload: structured JSON with `entrypointRequest` (method, uri, headers, body truncated to 4096 chars), `entrypointResponse` (status, headers, body truncated), `endpointRequest`, `endpointResponse`
- Labels: `gravitee.api_id`, `gravitee.request_id`, `gravitee.client_id`
- `trace` from `log.getRequestId()`

### 8.3 `EndpointStatusToLogEntryMapper`

Maps `io.gravitee.reporter.api.health.EndpointStatus`:

- Only emits a log entry when `endpointStatus.isTransition()` is `true` (avoids log spam on repeated healthy checks)
- Severity: `ERROR` when `!isAvailable()`, `INFO` when `isAvailable()`
- Payload: JSON with endpoint URL, API ID, response time, step details
- Labels: `gravitee.api_id`, `gravitee.api_name`, `gravitee.endpoint`, `gravitee.available`

### 8.4 `MessageMetricsToLogEntryMapper`

Maps `io.gravitee.reporter.api.v4.metric.MessageMetrics`:

- Severity: `INFO`
- Payload: JSON with message count, error count, latency percentiles
- Labels: `gravitee.api_id`, `gravitee.connector_id`, `gravitee.connector_type`

### 8.5 `GCloudLabels`

Utility class with a single static method:

```java
static void ifPresent(String value, String key, Map<String, String> labels)
```

Null-safe label insertion (mirrors `SentryTags.ifPresent`).

---

## 9. Unit Tests

### `GCloudReporterTest`

Tests `canHandle()` and `report()` routing using Mockito-mocked `GCloudReporterConfiguration` and `Logging`:

- Metrics are always handled when enabled
- Log handling respects `isReportLogs()`
- MessageMetrics handling respects `isReportMessageMetrics()`
- EndpointStatus handling respects `isReportHealthChecks()`
- When disabled, `canHandle()` returns false for all types
- When disabled, `report()` does not invoke `logging.write()`
- Metrics trigger `logging.write()` with a single `LogEntry`
- 5xx status sets severity `ERROR`; 2xx sets severity `INFO`

### `mapper/MetricsToLogEntryMapperTest`

- 200 response → severity INFO, HttpRequest fields populated correctly
- 500 response → severity ERROR
- 404 response → severity WARNING
- `transactionId` mapped to `trace`, `requestId` to `spanId`
- URI with numeric segments sanitised to `{id}`
- Null fields produce no labels (no NPE)
- Latency duration set correctly from `proxyResponseTimeMs`

### `mapper/LogToLogEntryMapperTest`

- Body truncated at 4096 characters
- Null body handled gracefully
- Severity is DEBUG
- `requestId` mapped to `trace`

### `mapper/EndpointStatusToLogEntryMapperTest`

- Non-transition emits no entry (returns `Optional.empty()`)
- Transition to unavailable → severity ERROR
- Transition to available → severity INFO
- Labels populated correctly

### `mapper/MessageMetricsToLogEntryMapperTest`

- Severity INFO
- Payload contains message count and error count

### `GCloudTestSupport`

Shared factory methods for building test fixtures: `Metrics`, `Log`, `EndpointStatus`, `MessageMetrics` with realistic values.

---

## 10. Integration Tests

### Infrastructure (Testcontainers)

Same pattern as `SentryReporterIT`:

| Container | Image | Role |
|---|---|---|
| MongoDB | `mongo:7.0` | APIM database |
| APIM Management API | `graviteeio/apim-management-api:4.9.x` | REST API for setup |
| APIM Gateway | `graviteeio/apim-gateway:4.9.x` | Executes plugin under test |
| Mock backend | `mccutchen/go-httpbin` | Returns controlled responses |

The plugin ZIP (built by Maven before IT phase) is bind-mounted into the Gateway container's plugin directory.

The Gateway container is configured via environment variable `gravitee_reporters_gcloud_*`:
- `projectId` — real GCP project (from env / `local.properties`)
- `credentialsFile` — mounted service-account JSON
- Authentication uses a GCP service account with **Logs Writer** (`roles/logging.logWriter`) IAM role

### `GCloudLoggingClient`

Mirrors `SentryApiClient` but queries the **Cloud Logging API** instead:

```java
// Uses google-cloud-logging library with the same credentials
// pollForEntries(String filter, Duration timeout) — wraps Logging.listLogEntries()
// Implements Awaitility-based polling with 5-second intervals
```

The `filter` uses Cloud Logging filter syntax, e.g.:
```
logName="projects/<project>/logs/gravitee-gateway"
labels."gravitee.api_id"="<apiId>"
httpRequest.status=200
```

### `GraviteeManagementApi` / `ManagementApiHelper`

Identical to the Sentry reporter: Retrofit2 client that creates, publishes, starts, and deploys a V4 HTTP proxy API against the Management REST API.

### `GCloudReporterIT` Test Methods

**`@BeforeAll startInfrastructure()`**
1. Validate plugin ZIP exists
2. Start Docker network
3. Start MongoDB
4. Start Management API (wait for health endpoint)
5. Start Gateway with plugin (wait for health endpoint)
6. Start go-httpbin mock backend
7. Create two test APIs via `ManagementApiHelper`:
   - `gcloud-it-ok` → proxies to `go-httpbin /status/200`
   - `gcloud-it-error` → proxies to `go-httpbin /status/500`

**`@AfterAll stopInfrastructure()`**
Shut down containers in reverse order; close GCL client.

**`@Test shouldWriteLogEntryForSuccessfulRequest()`**
1. Send `GET /gcloud-it-ok` through the gateway (port 8082)
2. Assert HTTP 200 received
3. Poll `GCloudLoggingClient` with filter:
   `labels."gravitee.api_id"="<apiId>" AND httpRequest.status=200`
4. Within 60 seconds, assert at least one entry is found
5. Assert `severity = INFO`
6. Assert `httpRequest.requestMethod = "GET"`
7. Assert `trace` field is non-null and non-empty

**`@Test shouldWriteErrorSeverityFor5xxResponse()`**
1. Send `GET /gcloud-it-error` through the gateway
2. Assert HTTP 500 received
3. Poll with filter:
   `labels."gravitee.api_id"="<apiId>" AND httpRequest.status=500`
4. Within 60 seconds, assert at least one entry is found
5. Assert `severity = ERROR`

**`@Test shouldPopulateHttpRequestFields()`**
1. Send `GET /gcloud-it-ok` with custom `User-Agent: gravitee-it-test`
2. Poll for the entry
3. Assert `httpRequest.userAgent = "gravitee-it-test"`
4. Assert `httpRequest.latency` is set (> 0)
5. Assert `httpRequest.remoteIp` is set

**`@Test shouldSetTraceAndSpanFields()`**
1. Send request, capture the `X-Gravitee-Transaction-Id` response header
2. Poll for entry where `trace` contains that transaction ID
3. Assert `spanId` is non-null

**Credentials / local development**

`local.properties.template`:
```
gcp.project.id=my-project
gcp.credentials.file=/path/to/sa-key.json
```

CI reads `GCP_PROJECT_ID` and `GCP_CREDENTIALS_JSON` environment variables; the JSON content is written to a temp file and the path is passed to the gateway container.

---

## 11. `gravitee.yml` Example Configuration

```yaml
reporters:
  gcloud:
    enabled: true
    projectId: my-gcp-project
    logName: gravitee-gateway
    resource:
      type: global
    # credentialsFile: /opt/gravitee/sa-key.json  # omit to use ADC
    captureErrors: true
    reportHealthChecks: true
    reportLogs: false
    reportMessageMetrics: true
    reportMonitor: false
    tracePrefix: projects/my-gcp-project/traces/
```

---

## 12. Implementation Order

1. **`pom.xml`** — coordinates, dependencies, assembly, failsafe profile
2. **`plugin.properties`** + **`gravitee.json`** — descriptor and schema
3. **`GCloudReporterConfiguration`** — configuration bean
4. **`GCloudReporterSpringConfiguration`** — Spring wiring, `Logging` bean factory
5. **`GCloudLabels`** utility
6. **`MetricsToLogEntryMapper`** + unit test
7. **`LogToLogEntryMapper`** + unit test
8. **`EndpointStatusToLogEntryMapper`** + unit test
9. **`MessageMetricsToLogEntryMapper`** + unit test
10. **`GCloudReporter`** + `GCloudReporterTest`
11. **`plugin-assembly.xml`** — ZIP packaging
12. **`GCloudLoggingClient`** + **`ManagementApiHelper`** + **`GCloudReporterIT`**
13. **`local.properties.template`** + **README.md**
