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

import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.writer.GCloudLogEntry;
import io.gravitee.reporter.gcloud.writer.GCloudSeverity;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Gravitee v4 {@link Log} (full request/response with bodies) to a DEBUG-severity
 * {@link GCloudLogEntry}. Bodies are truncated to 4096 characters to cap payload size.
 */
public class LogToLogEntryMapper {

  private static final Logger log = LoggerFactory.getLogger(
    LogToLogEntryMapper.class
  );
  private static final int MAX_BODY_LENGTH = 4096;

  private final GCloudReporterConfiguration cfg;

  public LogToLogEntryMapper(GCloudReporterConfiguration cfg) {
    this.cfg = cfg;
  }

  public GCloudLogEntry map(Log logReportable) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("api_id", Objects.toString(logReportable.getApiId(), ""));
      payload.put(
        "request_id",
        Objects.toString(logReportable.getRequestId(), "")
      );
      payload.put(
        "client_identifier",
        Objects.toString(logReportable.getClientIdentifier(), "")
      );
      payload.put(
        "entrypoint_request",
        requestPayload(logReportable.getEntrypointRequest())
      );
      payload.put(
        "entrypoint_response",
        responsePayload(logReportable.getEntrypointResponse())
      );
      payload.put(
        "endpoint_request",
        requestPayload(logReportable.getEndpointRequest())
      );
      payload.put(
        "endpoint_response",
        responsePayload(logReportable.getEndpointResponse())
      );

      var labels = GCloudLabels.of(
        "gravitee.api_id",
        logReportable.getApiId(),
        "gravitee.request_id",
        logReportable.getRequestId(),
        "gravitee.client_id",
        logReportable.getClientIdentifier()
      );

      String trace = (logReportable.getRequestId() != null &&
          !logReportable.getRequestId().isBlank())
        ? buildTrace(logReportable.getRequestId())
        : null;

      return new GCloudLogEntry(
        GCloudSeverity.DEBUG,
        Instant.now(),
        trace,
        null,
        labels,
        payload,
        null
      );
    } catch (Exception e) {
      log.warn("Failed to map Log to LogEntry — skipping", e);
      return null;
    }
  }

  private String buildTrace(String requestId) {
    String prefix = cfg.getTracePrefix();
    return (prefix != null && !prefix.isBlank())
      ? prefix + requestId
      : requestId;
  }

  private Map<String, Object> requestPayload(Request request) {
    if (request == null) return Map.of();
    Map<String, Object> map = new HashMap<>();
    if (request.getMethod() != null) map.put(
      "method",
      request.getMethod().name()
    );
    if (request.getUri() != null) map.put("uri", request.getUri());
    if (request.getHeaders() != null) map.put(
      "headers",
      request.getHeaders().toSingleValueMap()
    );
    if (request.getBody() != null) map.put("body", truncate(request.getBody()));
    return map;
  }

  private Map<String, Object> responsePayload(Response response) {
    if (response == null) return Map.of();
    Map<String, Object> map = new HashMap<>();
    map.put("status", response.getStatus());
    if (response.getHeaders() != null) map.put(
      "headers",
      response.getHeaders().toSingleValueMap()
    );
    if (response.getBody() != null) map.put(
      "body",
      truncate(response.getBody())
    );
    return map;
  }

  static String truncate(String value) {
    if (value == null) return null;
    if (value.length() <= MAX_BODY_LENGTH) return value;
    return value.substring(0, MAX_BODY_LENGTH) + "…";
  }
}
