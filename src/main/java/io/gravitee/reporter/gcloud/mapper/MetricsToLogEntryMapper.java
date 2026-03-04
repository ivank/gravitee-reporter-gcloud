/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.reporter.gcloud.mapper;

import com.google.cloud.logging.HttpRequest;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.Severity;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Gravitee v4 {@link Metrics} reportable to a GCL {@link LogEntry} with a fully
 * populated {@code httpRequest} field, trace/span identifiers, and Gravitee-specific labels.
 */
public class MetricsToLogEntryMapper {

  private static final Logger log = LoggerFactory.getLogger(
    MetricsToLogEntryMapper.class
  );

  /** Replaces numeric IDs and UUIDs in URL paths with {id} to reduce cardinality. */
  static final Pattern ID_PATTERN = Pattern.compile(
    "(?<=/)(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?=/|$)"
  );

  private final GCloudReporterConfiguration cfg;

  public MetricsToLogEntryMapper(GCloudReporterConfiguration cfg) {
    this.cfg = cfg;
  }

  public LogEntry map(Metrics metrics) {
    try {
      Map<String, String> labels = buildLabels(metrics);

      HttpRequest httpRequest = buildHttpRequest(metrics);

      Severity severity = resolveSeverity(metrics.getStatus());

      String trace = buildTrace(metrics.getTransactionId());

      Map<String, Object> payloadFields = new HashMap<>();
      payloadFields.put(
        "api_id",
        metrics.getApiId() != null ? metrics.getApiId() : ""
      );
      payloadFields.put(
        "api_name",
        metrics.getApiName() != null ? metrics.getApiName() : ""
      );
      payloadFields.put("status", metrics.getStatus());
      payloadFields.put(
        "method",
        metrics.getHttpMethod() != null ? metrics.getHttpMethod().name() : ""
      );
      payloadFields.put(
        "uri",
        metrics.getUri() != null ? metrics.getUri() : ""
      );
      payloadFields.put(
        "path",
        metrics.getPathInfo() != null ? sanitizePath(metrics.getPathInfo()) : ""
      );
      payloadFields.put(
        "gateway_response_ms",
        metrics.getGatewayResponseTimeMs()
      );
      payloadFields.put("gateway_latency_ms", metrics.getGatewayLatencyMs());
      payloadFields.put(
        "endpoint_response_ms",
        metrics.getEndpointResponseTimeMs()
      );

      LogEntry.Builder builder = LogEntry.newBuilder(
        Payload.JsonPayload.of(payloadFields)
      )
        .setSeverity(severity)
        .setHttpRequest(httpRequest)
        .setLabels(labels);

      if (trace != null && !trace.isBlank()) {
        builder.setTrace(trace);
      }
      if (metrics.getRequestId() != null && !metrics.getRequestId().isBlank()) {
        builder.setSpanId(metrics.getRequestId());
      }
      if (metrics.getTimestamp() > 0) {
        builder.setTimestamp(metrics.getTimestamp());
      }

      return builder.build();
    } catch (Exception e) {
      log.warn("Failed to map Metrics to LogEntry — skipping", e);
      return null;
    }
  }

  /** Replace numeric IDs and UUIDs in URI paths with {id} to reduce log-based metric cardinality. */
  static String sanitizePath(String path) {
    if (path == null) return null;
    return ID_PATTERN.matcher(path).replaceAll("{id}");
  }

  private Severity resolveSeverity(int status) {
    if (status >= 500) {
      return cfg.isCaptureErrors() ? Severity.ERROR : Severity.INFO;
    }
    if (status >= 400) {
      return Severity.WARNING;
    }
    return Severity.INFO;
  }

  private String buildTrace(String transactionId) {
    if (transactionId == null || transactionId.isBlank()) return null;
    String prefix = cfg.getTracePrefix();
    return (prefix != null && !prefix.isBlank())
      ? prefix + transactionId
      : transactionId;
  }

  private HttpRequest buildHttpRequest(Metrics metrics) {
    HttpRequest.Builder builder = HttpRequest.newBuilder();

    if (metrics.getHttpMethod() != null) {
      try {
        builder.setRequestMethod(
          HttpRequest.RequestMethod.valueOf(metrics.getHttpMethod().name())
        );
      } catch (IllegalArgumentException ignored) {}
    }
    if (metrics.getUri() != null) {
      String url = metrics.getUri();
      if (metrics.getHost() != null && !metrics.getHost().isBlank()) {
        url = "http://" + metrics.getHost() + url;
      }
      builder.setRequestUrl(url);
    }
    if (metrics.getRequestContentLength() > 0) {
      builder.setRequestSize(metrics.getRequestContentLength());
    }
    builder.setStatus(metrics.getStatus());
    if (metrics.getResponseContentLength() > 0) {
      builder.setResponseSize(metrics.getResponseContentLength());
    }
    if (metrics.getUserAgent() != null && !metrics.getUserAgent().isBlank()) {
      builder.setUserAgent(metrics.getUserAgent());
    }
    if (
      metrics.getRemoteAddress() != null &&
      !metrics.getRemoteAddress().isBlank()
    ) {
      builder.setRemoteIp(metrics.getRemoteAddress());
    }
    if (
      metrics.getLocalAddress() != null && !metrics.getLocalAddress().isBlank()
    ) {
      builder.setServerIp(metrics.getLocalAddress());
    }
    if (metrics.getGatewayResponseTimeMs() > 0) {
      builder.setLatencyDuration(
        Duration.ofMillis(metrics.getGatewayResponseTimeMs())
      );
    }

    return builder.build();
  }

  private Map<String, String> buildLabels(Metrics metrics) {
    Map<String, String> labels = new HashMap<>();
    GCloudLabels.ifPresent(metrics.getApiId(), "gravitee.api_id", labels);
    GCloudLabels.ifPresent(metrics.getApiName(), "gravitee.api_name", labels);
    GCloudLabels.ifPresent(
      metrics.getApplicationId(),
      "gravitee.application",
      labels
    );
    GCloudLabels.ifPresent(metrics.getPlanId(), "gravitee.plan", labels);
    GCloudLabels.ifPresent(metrics.getEndpoint(), "gravitee.endpoint", labels);
    GCloudLabels.ifPresent(
      String.valueOf(metrics.getGatewayResponseTimeMs()),
      "gravitee.gateway_ms",
      labels
    );
    GCloudLabels.ifPresent(
      String.valueOf(metrics.getGatewayLatencyMs()),
      "gravitee.latency_ms",
      labels
    );
    GCloudLabels.ifPresent(
      String.valueOf(metrics.getEndpointResponseTimeMs()),
      "gravitee.endpoint_ms",
      labels
    );
    return labels;
  }
}
