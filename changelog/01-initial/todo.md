# Implementation Todo

Each step ends with a verification command. All steps must pass before moving to the next.

---

## Step 1 — Maven skeleton

Create `pom.xml` with:
- coordinates (`io.gravitee.reporter:gravitee-reporter-gcloud:1.0.0-SNAPSHOT`)
- Java 21 compiler plugin
- all provided/bundled/test dependencies listed in the plan
- `maven-assembly-plugin` referencing `src/main/assembly/plugin-assembly.xml`
- `maven-failsafe-plugin` bound to `integration-test` + `verify` in a `integration-test` profile
- `maven-resources-plugin` filtering `src/main/resources`

Create `src/main/assembly/plugin-assembly.xml` producing the plugin ZIP.

**Verify:**
```
mvn validate
```
Expected: `BUILD SUCCESS`, no missing-dependency or plugin errors.

---

## Step 2 — Plugin descriptor and JSON schema

Create:
- `src/main/resources/plugin.properties` (id=gcloud, class=…GCloudReporter, type=reporter, name/version/description via Maven filters)
- `src/main/resources/gravitee.json` (JSON Schema draft-07 for all config fields: projectId, logName, resource.type, resource.labels, credentialsFile, captureErrors, reportHealthChecks, reportLogs, reportMessageMetrics, reportMonitor, tracePrefix)

**Verify:**
```
mvn process-resources
cat target/classes/plugin.properties   # must contain resolved version, not literal ${project.version}
python3 -c "import json,sys; json.load(open('src/main/resources/gravitee.json'))"
```
Expected: properties file has real values; JSON is valid.

---

## Step 3 — Configuration bean

Create `GCloudReporterConfiguration` bound to `reporters.gcloud.*` in `gravitee.yml`, with typed getters for every field in the schema.

**Verify:**
```
mvn compile
```
Expected: `BUILD SUCCESS`, zero compiler warnings on the new class.

---

## Step 4 — Spring wiring + `Logging` bean factory

Create `GCloudReporterSpringConfiguration`:
- `@Bean GCloudReporterConfiguration`
- `@Bean Logging` — builds `LoggingOptions` from `credentialsFile` (if set) else ADC; returns `logging`

**Verify:**
```
mvn compile
```
Expected: `BUILD SUCCESS`.

---

## Step 5 — `GCloudLabels` utility + unit test

Create `GCloudLabels.ifPresent(String value, String key, Map<String,String> labels)`.

Create `GCloudLabelsTest` asserting:
- non-null value inserts the key
- null value does not insert the key
- blank value does not insert the key

**Verify:**
```
mvn test -pl . -Dtest=GCloudLabelsTest
```
Expected: 3 tests pass.

---

## Step 6 — `MetricsToLogEntryMapper` + unit test

Create `MetricsToLogEntryMapper` mapping `Metrics` → `LogEntry` as described in the plan (HttpRequest, trace, spanId, severity, labels, path sanitization).

Create `MetricsToLogEntryMapperTest` with `GCloudTestSupport` fixture helper:
- 200 → severity INFO, HttpRequest populated
- 500 → severity ERROR (when captureErrors=true)
- 404 → severity WARNING
- `transactionId` → `trace`, `requestId` → `spanId`
- numeric/UUID path segments sanitised to `{id}`
- null optional fields produce no labels, no NPE
- latency Duration set from `proxyResponseTimeMs`

**Verify:**
```
mvn test -pl . -Dtest=MetricsToLogEntryMapperTest
```
Expected: all tests pass.

---

## Step 7 — `LogToLogEntryMapper` + unit test

Create `LogToLogEntryMapper` mapping `v4.log.Log` → `LogEntry` (severity DEBUG, truncated bodies, headers, trace from requestId).

Create `LogToLogEntryMapperTest`:
- body truncated at 4096 chars
- null body handled without NPE
- severity is DEBUG
- `requestId` → `trace`
- labels contain `gravitee.api_id`, `gravitee.request_id`

**Verify:**
```
mvn test -pl . -Dtest=LogToLogEntryMapperTest
```
Expected: all tests pass.

---

## Step 8 — `EndpointStatusToLogEntryMapper` + unit test

Create `EndpointStatusToLogEntryMapper` returning `Optional<LogEntry>` (empty when not a transition).

Create `EndpointStatusToLogEntryMapperTest`:
- non-transition → `Optional.empty()`
- transition to unavailable → severity ERROR
- transition to available → severity INFO
- labels: `gravitee.api_id`, `gravitee.api_name`, `gravitee.endpoint`, `gravitee.available`

**Verify:**
```
mvn test -pl . -Dtest=EndpointStatusToLogEntryMapperTest
```
Expected: all tests pass.

---

## Step 9 — `MessageMetricsToLogEntryMapper` + unit test

Create `MessageMetricsToLogEntryMapper` mapping `MessageMetrics` → `LogEntry` (severity INFO, message/error counts in payload, api/connector labels).

Create `MessageMetricsToLogEntryMapperTest`:
- severity INFO
- payload contains message count and error count
- labels contain `gravitee.api_id`, `gravitee.connector_id`

**Verify:**
```
mvn test -pl . -Dtest=MessageMetricsToLogEntryMapperTest
```
Expected: all tests pass.

---

## Step 10 — `GCloudReporter` + unit test

Create `GCloudReporter extends AbstractService implements Reporter`:
- `canHandle()` routing table (all types, respects config flags, returns false when disabled)
- `report()` delegates to mappers and calls `logging.write()`; catches all exceptions
- `doStop()` calls `logging.flush()` then `logging.close()`

Create `GCloudReporterTest` with mocked `Logging` and `GCloudReporterConfiguration`:
- all `canHandle()` combinations
- disabled reporter → `canHandle()` always false
- disabled reporter → `report()` never calls `logging.write()`
- Metrics → `logging.write()` called with one entry, severity INFO
- 5xx Metrics → severity ERROR in the written entry
- EndpointStatus non-transition → `logging.write()` not called
- Log with `reportLogs=false` → not handled

**Verify:**
```
mvn test -pl . -Dtest=GCloudReporterTest
```
Expected: all tests pass.

---

## Step 11 — Full unit-test suite

Run all unit tests together to catch any cross-class regressions.

**Verify:**
```
mvn test
```
Expected: `BUILD SUCCESS`, zero test failures.

---

## Step 12 — Plugin ZIP packaging

Create `src/main/assembly/plugin-assembly.xml` (if not done in Step 1) and verify the ZIP is produced with bundled runtime deps and no provided-scope jars.

**Verify:**
```
mvn package -DskipTests
unzip -l target/gravitee-reporter-gcloud-*.zip | grep -E "(google-cloud-logging|google-auth|plugin.properties|GCloudReporter)"
```
Expected: `google-cloud-logging`, `google-auth-library-oauth2-http`, `plugin.properties`, and `GCloudReporter.class` all appear; Spring / SLF4J / Gravitee API jars do **not** appear.

---

## Step 13 — Local credentials file

Create `local.properties.template`:
```
GOOGLE_CLOUD_PROJECT=fh-test-414003
# GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa-key.json  # omit to use ADC
```

Create `local.properties` (git-ignored) pre-filled with:
```
GOOGLE_CLOUD_PROJECT=fh-test-414003
```

Add `local.properties` to `.gitignore`.

**Verify:**
```
grep "local.properties" .gitignore
grep "GOOGLE_CLOUD_PROJECT=fh-test-414003" local.properties
```
Expected: both greps return a match.

---

## Step 14 — GitHub Actions workflow

Create `.github/workflows/ci.yml`:
- trigger on `push` and `pull_request`
- job `build`: Java 21, `mvn verify` (unit tests only, no IT profile)
- job `integration-test`: Java 21, `mvn verify -Pintegration-test`
  - requires secret `GCP_PROJECT_ID` (set to `fh-test-414003` in repository secrets)
  - requires secret `GCP_CREDENTIALS_JSON` (service-account key JSON)
  - passes them as environment variables `GOOGLE_CLOUD_PROJECT` and `GOOGLE_APPLICATION_CREDENTIALS_JSON` to the Maven process
  - writes `GOOGLE_APPLICATION_CREDENTIALS_JSON` to a temp file and sets `GOOGLE_APPLICATION_CREDENTIALS` pointing to it before running Maven

**Verify:**
```
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"
grep "GOOGLE_CLOUD_PROJECT" .github/workflows/ci.yml
grep "integration-test" .github/workflows/ci.yml
```
Expected: valid YAML, both greps match.

---

## Step 15 — Integration test infrastructure helpers

Create in `src/test/java/io/gravitee/reporter/gcloud/integration/`:

- `GraviteeManagementApi.java` — Retrofit2 interface (POST /apis, /plans, /deployments, /_start)
- `ManagementApiHelper.java` — drives the five-step API lifecycle: create → create plan → publish plan → start → deploy; Basic Auth with admin/admin; throws `IllegalStateException` on non-2xx

**Verify:**
```
mvn test-compile
```
Expected: `BUILD SUCCESS`, no compilation errors in test sources.

---

## Step 16 — `GCloudLoggingClient`

Create `GCloudLoggingClient` in the integration package:
- wraps `com.google.cloud.logging.Logging`
- `pollForEntries(String filter, Duration timeout)` — calls `logging.listLogEntries()` with the filter every 5 seconds until at least one entry is found or timeout expires; uses Awaitility
- built from `GOOGLE_CLOUD_PROJECT` env var and ADC (or `GOOGLE_APPLICATION_CREDENTIALS`)

**Verify:**
```
mvn test-compile
```
Expected: `BUILD SUCCESS`.

---

## Step 17 — `GCloudReporterIT`

Create `GCloudReporterIT` following the Sentry IT pattern:

`@BeforeAll`:
1. Read `GOOGLE_CLOUD_PROJECT` from env (fail fast if absent)
2. Create shared Docker network
3. Start `mongo:7.0`
4. Start `graviteeio/apim-management-api:4.9.x` (wait for `/management/organizations/DEFAULT/environments/DEFAULT/apis` → 200)
5. Start `graviteeio/apim-gateway:4.9.x` with plugin ZIP bind-mounted and `GRAVITEE_REPORTERS_GCLOUD_*` env vars set
6. Start `mccutchen/go-httpbin`
7. Create two APIs via `ManagementApiHelper`: `gcloud-it-ok` (→ `/status/200`) and `gcloud-it-error` (→ `/status/500`)
8. Instantiate `GCloudLoggingClient`

`@AfterAll`: stop containers, close client.

Test methods:
- `shouldWriteLogEntryForSuccessfulRequest` — GET through gateway → poll for entry with `httpRequest.status=200` and `severity=INFO`
- `shouldWriteErrorSeverityFor5xxResponse` — GET → poll for entry with `httpRequest.status=500` and `severity=ERROR`
- `shouldPopulateHttpRequestFields` — assert `httpRequest.userAgent`, `httpRequest.latency`, `httpRequest.remoteIp` are set
- `shouldSetTraceAndSpanFields` — capture `X-Gravitee-Transaction-Id` response header; assert entry's `trace` contains it and `spanId` is non-null

**Verify (requires `GOOGLE_CLOUD_PROJECT=fh-test-414003` and valid ADC):**
```
mvn verify -Pintegration-test
```
Expected: `BUILD SUCCESS`, 4 IT methods pass, log entries visible in Cloud Logging under project `fh-test-414003`.

---

## Step 18 — README

Write `README.md` covering:
- what the plugin does
- requirements (APIM 4.9+, Java 21, GCP project with Logs Writer IAM)
- installation (copy ZIP to plugins dir)
- `gravitee.yml` configuration reference (all fields with defaults)
- authentication options (ADC vs service-account file)
- local development and running integration tests

**Verify:**
```
mvn verify -DskipTests    # ensure README doesn't break build
```
Expected: `BUILD SUCCESS`.
