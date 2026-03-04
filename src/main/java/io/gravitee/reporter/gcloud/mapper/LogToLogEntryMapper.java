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

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.Severity;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Gravitee v4 {@link Log} (full request/response with bodies) to a DEBUG-severity
 * GCL {@link LogEntry}. Bodies are truncated to 4096 characters to cap payload size.
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

  public LogEntry map(Log logReportable) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put(
        "api_id",
        logReportable.getApiId() != null ? logReportable.getApiId() : ""
      );
      payload.put(
        "request_id",
        logReportable.getRequestId() != null ? logReportable.getRequestId() : ""
      );
      payload.put(
        "client_identifier",
        logReportable.getClientIdentifier() != null
          ? logReportable.getClientIdentifier()
          : ""
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

      Map<String, String> labels = new HashMap<>();
      GCloudLabels.ifPresent(
        logReportable.getApiId(),
        "gravitee.api_id",
        labels
      );
      GCloudLabels.ifPresent(
        logReportable.getRequestId(),
        "gravitee.request_id",
        labels
      );
      GCloudLabels.ifPresent(
        logReportable.getClientIdentifier(),
        "gravitee.client_id",
        labels
      );

      LogEntry.Builder builder = LogEntry.newBuilder(
        Payload.JsonPayload.of(payload)
      )
        .setSeverity(Severity.DEBUG)
        .setLabels(labels);

      if (
        logReportable.getRequestId() != null &&
        !logReportable.getRequestId().isBlank()
      ) {
        builder.setTrace(buildTrace(logReportable.getRequestId()));
      }

      return builder.build();
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
