/*
 * Copyright 2026 Ivan Kerin (http://github.com/ivank)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.gravitee.reporter.gcloud.mapper;

import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
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
 * Maps a Gravitee v4 {@link Metrics} reportable to a {@link GCloudLogEntry}.
 *
 * <p>The {@code jsonPayload} is structured as nested objects:
 * <ul>
 *   <li>{@code api} — API identity and type</li>
 *   <li>{@code context} — subscription, application, plan, user, environment</li>
 *   <li>{@code entrypoint.request/response} — what the client sent and received</li>
 *   <li>{@code endpoint.request/response} — what the gateway sent to / received from the backend</li>
 *   <li>{@code gateway} — internal latency breakdown</li>
 *   <li>{@code error} — error key and message, if any</li>
 * </ul>
 *
 * <p>The {@code httpRequest} field is also populated for Cloud Logging's native
 * HTTP-request display (filtering by status, latency, etc.).
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

      Map<String, Object> payload = buildPayload(metrics);

      return new GCloudLogEntry(
        severity,
        timestamp,
        trace,
        spanId,
        labels,
        payload,
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

  private Map<String, Object> buildPayload(Metrics m) {
    Map<String, Object> p = new HashMap<>();

    // ── api ──────────────────────────────────────────────────────────────────
    Map<String, Object> api = new HashMap<>();
    put(api, "id", m.getApiId());
    put(api, "name", m.getApiName());
    put(api, "type", m.getApiType());
    p.put("api", api);

    // ── context ──────────────────────────────────────────────────────────────
    Map<String, Object> ctx = new HashMap<>();
    put(ctx, "application", m.getApplicationId());
    put(ctx, "plan", m.getPlanId());
    put(ctx, "subscription", m.getSubscriptionId());
    put(ctx, "client", m.getClientIdentifier());
    put(ctx, "user", m.getUser());
    put(ctx, "tenant", m.getTenant());
    put(ctx, "zone", m.getZone());
    if (!ctx.isEmpty()) p.put("context", ctx);

    // ── log (headers + actual endpoint request, when API logging is enabled) ─
    Log log = m.getLog();

    // ── entrypoint ───────────────────────────────────────────────────────────
    Map<String, Object> epReq = new HashMap<>();
    if (m.getHttpMethod() != null) epReq.put(
      "method",
      m.getHttpMethod().name()
    );
    put(epReq, "uri", m.getUri());
    if (m.getPathInfo() != null) {
      epReq.put("path", sanitizePath(m.getPathInfo()));
    }
    put(epReq, "host", m.getHost());
    put(epReq, "remote_ip", m.getRemoteAddress());
    put(epReq, "local_ip", m.getLocalAddress());
    put(epReq, "user_agent", m.getUserAgent());
    put(epReq, "entrypoint_id", m.getEntrypointId());
    if (m.getRequestContentLength() > 0) {
      epReq.put("size", m.getRequestContentLength());
    }
    if (log != null && log.getEntrypointRequest() != null) {
      putHeaders(epReq, log.getEntrypointRequest().getHeaders());
    }

    Map<String, Object> epResp = new HashMap<>();
    epResp.put("status", m.getStatus());
    if (m.getResponseContentLength() > 0) {
      epResp.put("size", m.getResponseContentLength());
    }
    epResp.put("time_ms", m.getGatewayResponseTimeMs());
    if (log != null && log.getEntrypointResponse() != null) {
      putHeaders(epResp, log.getEntrypointResponse().getHeaders());
    }

    Map<String, Object> entrypoint = new HashMap<>();
    entrypoint.put("request", epReq);
    entrypoint.put("response", epResp);
    p.put("entrypoint", entrypoint);

    // ── endpoint ─────────────────────────────────────────────────────────────
    Map<String, Object> endpoint = new HashMap<>();
    put(endpoint, "url", m.getEndpoint());

    if (log != null && log.getEndpointRequest() != null) {
      Request endpReq = log.getEndpointRequest();
      Map<String, Object> endpReqMap = new HashMap<>();
      if (endpReq.getMethod() != null) {
        endpReqMap.put("method", endpReq.getMethod().name());
      }
      put(endpReqMap, "uri", endpReq.getUri());
      putHeaders(endpReqMap, endpReq.getHeaders());
      if (!endpReqMap.isEmpty()) endpoint.put("request", endpReqMap);
    }

    Map<String, Object> endpResp = new HashMap<>();
    endpResp.put("time_ms", m.getEndpointResponseTimeMs());
    if (log != null && log.getEndpointResponse() != null) {
      Response endpRespLog = log.getEndpointResponse();
      if (endpRespLog.getStatus() > 0) {
        endpResp.put("status", endpRespLog.getStatus());
      }
      putHeaders(endpResp, endpRespLog.getHeaders());
    }
    endpoint.put("response", endpResp);

    p.put("endpoint", endpoint);

    // ── gateway ──────────────────────────────────────────────────────────────
    Map<String, Object> gateway = new HashMap<>();
    gateway.put("latency_ms", m.getGatewayLatencyMs());
    p.put("gateway", gateway);

    // ── error ─────────────────────────────────────────────────────────────────
    if (
      (m.getErrorMessage() != null && !m.getErrorMessage().isBlank()) ||
      (m.getErrorKey() != null && !m.getErrorKey().isBlank())
    ) {
      Map<String, Object> error = new HashMap<>();
      put(error, "message", m.getErrorMessage());
      put(error, "key", m.getErrorKey());
      p.put("error", error);
    }

    return p;
  }

  /** Puts {@code value} into {@code map} only when non-null and non-blank. */
  private static void put(Map<String, Object> map, String key, String value) {
    if (value != null && !value.isBlank()) map.put(key, value);
  }

  /** Adds a {@code headers} sub-map when {@code headers} is non-null and non-empty. */
  private static void putHeaders(Map<String, Object> map, HttpHeaders headers) {
    if (headers == null) return;
    Map<String, String> flat = headers.toSingleValueMap();
    if (flat != null && !flat.isEmpty()) map.put("headers", flat);
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
    GCloudLabels.ifPresent(
      metrics.getSubscriptionId(),
      "gravitee.subscription",
      labels
    );
    return labels;
  }
}
