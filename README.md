# gravitee-reporter-gcloud

A [Gravitee APIM](https://www.gravitee.io/) reporter plugin that writes gateway telemetry to
[Google Cloud Logging](https://cloud.google.com/logging) as structured log entries.

Each Gravitee event type maps to native GCL fields so that logs are immediately queryable in
the Cloud Logging console, usable in log-based metrics, and compatible with Cloud Trace:

| Gravitee event | GCL fields populated |
|---|---|
| HTTP request metrics | `httpRequest` (method, url, status, sizes, userAgent, remoteIp, latency), `trace`, `spanId`, `severity` (INFO / WARNING / ERROR), `labels` |
| Full request/response logs | `severity=DEBUG`, JSON payload with truncated bodies |
| Endpoint health-check transitions | `severity=ERROR` (down) or `INFO` (recovered) |
| Async message metrics | `severity=INFO`, JSON payload with counts |

---

## Requirements

- Gravitee APIM **4.9+**
- Java **21**
- A Google Cloud project with the **Logs Writer** IAM role
  (`roles/logging.logWriter`) granted to the gateway's identity

---

## Installation

1. Build the plugin ZIP:
   ```sh
   mvn clean package -DskipTests
   ```

2. Copy `target/gravitee-reporter-gcloud-<version>.zip` into the gateway's plugin directory
   (default: `${node.home}/plugins`).

3. Restart the gateway.

---

## Configuration (`gravitee.yml`)

```yaml
reporters:
  gcloud:
    enabled: true

    # Google Cloud project ID.
    # Defaults to the GOOGLE_CLOUD_PROJECT environment variable or the GCE metadata server.
    projectId: my-gcp-project

    # Cloud Logging log name (the part after "projects/<project>/logs/")
    logName: gravitee-gateway

    # Monitored resource attached to every log entry.
    # Use "global" for non-GCP deployments; on GKE use "k8s_container".
    resource:
      type: global
      # labels:
      #   project_id: my-gcp-project

    # Path to a service-account JSON key file.
    # Omit to use Application Default Credentials (ADC).
    # credentialsFile: /opt/gravitee/sa-key.json

    # Set log severity to ERROR for HTTP 5xx, WARNING for 4xx. Default: true
    captureErrors: true

    # Write a log entry on each endpoint health-check state transition. Default: true
    reportHealthChecks: true

    # Write a log entry with full request/response bodies (high volume). Default: false
    reportLogs: false

    # Write a log entry for async message metrics. Default: true
    reportMessageMetrics: true

    # Write a log entry for gateway node monitor events. Default: false
    reportMonitor: false

    # Optional prefix for the trace field, e.g. projects/my-project/traces/
    # tracePrefix: projects/my-gcp-project/traces/
```

---

## Authentication

The plugin uses the [Google Auth Library](https://github.com/googleapis/google-auth-library-java)
and supports two authentication methods:

### Application Default Credentials (ADC) — recommended

Leave `credentialsFile` unset. ADC is resolved in this order:

1. `GOOGLE_APPLICATION_CREDENTIALS` environment variable pointing to a key file
2. `gcloud auth application-default login` credentials (`~/.config/gcloud/`)
3. Attached service account (on GCE / GKE / Cloud Run)

### Service Account key file

```yaml
reporters:
  gcloud:
    credentialsFile: /opt/gravitee/sa-key.json
```

Grant the service account `roles/logging.logWriter` on the GCP project.

---

## Running integration tests

Integration tests start a full Gravitee APIM stack via Testcontainers and write real log
entries to Cloud Logging in the `fh-test-414003` project.

### Local development

```sh
# Authenticate with ADC
gcloud auth application-default login

# Run integration tests
mvn clean verify -Pintegration-test
```

`GOOGLE_CLOUD_PROJECT=fh-test-414003` is pre-filled in `local.properties` (git-ignored).
Copy `local.properties.template` to override defaults or add a service-account key path.

### CI/CD (GitHub Actions)

Set two repository secrets:

| Secret | Value |
|---|---|
| `GCP_PROJECT_ID` | `fh-test-414003` |
| `GCP_CREDENTIALS_JSON` | Contents of the service-account JSON key |

The workflow writes the JSON to a temp file, sets `GOOGLE_APPLICATION_CREDENTIALS`, and runs
`mvn verify -Pintegration-test`.

---

## Log entry structure

Example log entry for a successful request (viewed in Cloud Logging):

```json
{
  "severity": "INFO",
  "httpRequest": {
    "requestMethod": "GET",
    "requestUrl": "http://gateway.example.com/api/v1/users/42",
    "status": 200,
    "responseSize": "1024",
    "userAgent": "curl/8.1.2",
    "remoteIp": "10.0.0.1",
    "latency": "0.042s"
  },
  "trace": "projects/my-project/traces/txn-aabbccdd",
  "spanId": "req-11223344",
  "labels": {
    "gravitee.api_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "gravitee.api_name": "My API",
    "gravitee.plan": "plan-id",
    "gravitee.application": "app-id",
    "gravitee.endpoint": "https://backend.example.com",
    "gravitee.gateway_ms": "42",
    "gravitee.latency_ms": "5",
    "gravitee.endpoint_ms": "37"
  },
  "jsonPayload": {
    "api_id": "3fa85f64-...",
    "status": 200,
    "method": "GET",
    "uri": "/api/v1/users/42",
    "gateway_response_ms": 42,
    "gateway_latency_ms": 5,
    "endpoint_response_ms": 37
  }
}
```

---

## Build

```sh
mvn clean package -DskipTests          # produces target/gravitee-reporter-gcloud-*.zip
mvn test                               # unit tests only
mvn verify -Pintegration-test          # full stack integration tests
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
