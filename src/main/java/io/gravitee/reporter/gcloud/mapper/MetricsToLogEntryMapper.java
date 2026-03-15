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

import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.writer.GCloudHttpRequest;
import io.gravitee.reporter.gcloud.writer.GCloudLogEntry;
import io.gravitee.reporter.gcloud.writer.GCloudSeverity;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Gravitee v4 {@link Metrics} reportable to a {@link GCloudLogEntry} with a fully
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

  public GCloudLogEntry map(Metrics metrics) {
    try {
      Map<String, String> labels = buildLabels(metrics);

      GCloudHttpRequest httpRequest = buildHttpRequest(metrics);

      GCloudSeverity severity = resolveSeverity(metrics.getStatus());

      String trace = buildTrace(metrics.getTransactionId());
      String spanId = (metrics.getRequestId() != null &&
          !metrics.getRequestId().isBlank())
        ? metrics.getRequestId()
        : null;

      Instant timestamp = metrics.getTimestamp() > 0
        ? Instant.ofEpochMilli(metrics.getTimestamp())
        : Instant.now();

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

      return new GCloudLogEntry(
        severity,
        timestamp,
        trace,
        spanId,
        labels,
        payloadFields,
        httpRequest
      );
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

  private GCloudSeverity resolveSeverity(int status) {
    if (status >= 500) {
      return cfg.isCaptureErrors() ? GCloudSeverity.ERROR : GCloudSeverity.INFO;
    }
    if (status >= 400) {
      return GCloudSeverity.WARNING;
    }
    return GCloudSeverity.INFO;
  }

  private String buildTrace(String transactionId) {
    if (transactionId == null || transactionId.isBlank()) return null;
    String prefix = cfg.getTracePrefix();
    return (prefix != null && !prefix.isBlank())
      ? prefix + transactionId
      : transactionId;
  }

  private GCloudHttpRequest buildHttpRequest(Metrics metrics) {
    String requestMethod = metrics.getHttpMethod() != null
      ? metrics.getHttpMethod().name()
      : null;

    String requestUrl = null;
    if (metrics.getUri() != null) {
      String url = metrics.getUri();
      if (metrics.getHost() != null && !metrics.getHost().isBlank()) {
        url = "http://" + metrics.getHost() + url;
      }
      requestUrl = url;
    }

    long requestSize = metrics.getRequestContentLength() > 0
      ? metrics.getRequestContentLength()
      : 0L;
    long responseSize = metrics.getResponseContentLength() > 0
      ? metrics.getResponseContentLength()
      : 0L;

    String userAgent = (metrics.getUserAgent() != null &&
        !metrics.getUserAgent().isBlank())
      ? metrics.getUserAgent()
      : null;
    String remoteIp = (metrics.getRemoteAddress() != null &&
        !metrics.getRemoteAddress().isBlank())
      ? metrics.getRemoteAddress()
      : null;
    String serverIp = (metrics.getLocalAddress() != null &&
        !metrics.getLocalAddress().isBlank())
      ? metrics.getLocalAddress()
      : null;

    long latencyMs = metrics.getGatewayResponseTimeMs() > 0
      ? metrics.getGatewayResponseTimeMs()
      : 0L;

    return new GCloudHttpRequest(
      requestMethod,
      requestUrl,
      requestSize,
      metrics.getStatus(),
      responseSize,
      userAgent,
      remoteIp,
      serverIp,
      latencyMs
    );
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
