# gravitee-reporter-gcloud

A [Gravitee APIM](https://www.gravitee.io/) reporter plugin that writes gateway telemetry to
[Google Cloud Logging](https://cloud.google.com/logging) as structured log entries.

Each Gravitee event type maps to native GCL fields so that logs are immediately queryable in
the Cloud Logging console, usable in log-based metrics, and compatible with Cloud Trace:

| Gravitee event | GCL fields populated |
|---|---|
| HTTP request metrics | `httpRequest` (method, url, status, sizes, userAgent, remoteIp, latency), `trace`, `spanId`, `severity` (INFO / WARNING / ERROR), `labels` |
| Full request/response logs | `severity=DEBUG`, JSON payload with headers |
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
    # Falls back to the GOOGLE_CLOUD_PROJECT environment variable.
    projectId: my-gcp-project

    # Cloud Logging log name (the part after "projects/<project>/logs/")
    logName: gravitee-gateway

    # Monitored resource attached to every log entry.
    # Use "global" for non-GCP deployments; on GKE use "k8s_container".
    resource:
      type: global

    # Path to a service-account JSON key file.
    # Omit to use Application Default Credentials (ADC).
    # credentialsFile: /opt/gravitee/sa-key.json

    # Set log severity to ERROR for HTTP 5xx, WARNING for 4xx. Default: true
    captureErrors: true

    # Write a log entry on each endpoint health-check state transition. Default: true
    reportHealthChecks: true

    # Write a log entry with full request/response headers (high volume). Default: false
    reportLogs: false

    # Write a log entry for async message metrics. Default: true
    reportMessageMetrics: true

    # Write a log entry for gateway node monitor events. Default: false
    reportMonitor: false

    # Optional prefix for the trace field, e.g. projects/my-project/traces/
    # tracePrefix: projects/my-gcp-project/traces/

    # Maximum number of log entries per batch sent to Cloud Logging. Default: 500
    batchSize: 500

    # How often (seconds) to flush a partial batch. Default: 5
    flushIntervalSeconds: 5
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

Integration tests write real log entries to Cloud Logging in the configured project.

### Local development

```sh
# Authenticate with ADC
gcloud auth application-default login

# Run integration tests
mvn clean verify -Pintegration-test
```

`GOOGLE_CLOUD_PROJECT` is pre-filled in `local.properties` (git-ignored).
Copy `local.properties.template` to override defaults or add a service-account key path.

### CI/CD (GitHub Actions)

Set two repository secrets:

| Secret | Value |
|---|---|
| `GCP_PROJECT_ID` | Your GCP project ID |
| `GCP_CREDENTIALS_JSON` | Contents of the service-account JSON key |

The workflow writes the JSON to a temp file, sets `GOOGLE_APPLICATION_CREDENTIALS`, and runs
`mvn verify -Pintegration-test`.

---

## Log entry structure

Example log entry for a successful request (viewed in Cloud Logging):

```json
{
  "severity": "INFO",
  "timestamp": "2026-03-15T10:00:00.042Z",
  "trace": "projects/my-project/traces/txn-aabbccdd",
  "spanId": "req-11223344",
  "httpRequest": {
    "requestMethod": "GET",
    "requestUrl": "http://gateway.example.com/api/v1/users/42",
    "status": 200,
    "responseSize": "1024",
    "userAgent": "curl/8.1.2",
    "remoteIp": "10.0.0.1",
    "latency": "0.042000000s"
  },
  "labels": {
    "gravitee.api_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "gravitee.api_name": "My API",
    "gravitee.plan": "plan-id",
    "gravitee.application": "app-id",
    "gravitee.subscription": "sub-id"
  },
  "jsonPayload": {
    "api": {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "My API",
      "type": "PROXY"
    },
    "context": {
      "application": "app-id",
      "plan": "plan-id",
      "subscription": "sub-id"
    },
    "entrypoint": {
      "request": {
        "method": "GET",
        "uri": "/api/v1/users/42",
        "path": "/api/v1/users/{id}",
        "remote_ip": "10.0.0.1",
        "user_agent": "curl/8.1.2"
      },
      "response": {
        "status": 200,
        "time_ms": 42
      }
    },
    "endpoint": {
      "url": "https://backend.example.com",
      "response": {
        "time_ms": 37
      }
    },
    "gateway": {
      "latency_ms": 5
    }
  }
}
```

Path segments that are numeric IDs or UUIDs are automatically replaced with `{id}` in the
`entrypoint.request.path` field to reduce log-based metric cardinality.

---

## Build

```sh
mvn clean package -DskipTests          # produces target/gravitee-reporter-gcloud-*.zip
mvn test                               # unit tests only
mvn verify -Pintegration-test          # integration tests (requires ADC)
```

---

## License

MIT — see [LICENSE](LICENSE).
