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
package io.gravitee.reporter.gcloud.writer;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Serializes batches of {@link GCloudLogEntry} objects to the JSON body expected by
 * Cloud Logging REST API v2 {@code entries:write}.
 *
 * <p>Uses hand-crafted JSON to avoid pulling a JSON library into compile scope.
 * Only standard Java types are produced by the mappers (String, Long, Integer,
 * Boolean, Map, List) so a small recursive serializer is sufficient.
 */
public class GCloudEntrySerializer {

  private static final DateTimeFormatter RFC3339 =
    DateTimeFormatter.ISO_INSTANT;

  private final String projectId;
  private final String logName;
  private final String resourceType;
  private final Map<String, String> resourceLabels;

  public GCloudEntrySerializer(
    String projectId,
    String logName,
    String resourceType,
    Map<String, String> resourceLabels
  ) {
    this.projectId = projectId;
    this.logName = logName;
    this.resourceType = resourceType;
    this.resourceLabels = resourceLabels != null ? resourceLabels : Map.of();
  }

  /**
   * Serializes a batch of entries to the JSON body for {@code POST /v2/entries:write}.
   *
   * <p>The {@code logName} and {@code resource} fields are set at the batch level so that
   * they are shared by all entries in the batch (the Cloud Logging API propagates them
   * down to each entry).
   */
  public String serialize(List<GCloudLogEntry> entries) {
    String fullLogName = "projects/" + projectId + "/logs/" + encode(logName);
    StringBuilder sb = new StringBuilder();
    sb.append("{\"logName\":").append(jsonString(fullLogName));
    sb.append(",\"resource\":{\"type\":").append(jsonString(resourceType));
    sb.append(",\"labels\":").append(jsonStringMap(resourceLabels));
    sb.append("}");
    sb.append(",\"entries\":[");
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) sb.append(",");
      appendEntry(sb, entries.get(i));
    }
    sb.append("]}");
    return sb.toString();
  }

  private void appendEntry(StringBuilder sb, GCloudLogEntry entry) {
    sb.append("{");
    sb.append("\"severity\":").append(jsonString(entry.severity().name()));
    if (entry.timestamp() != null) {
      sb
        .append(",\"timestamp\":")
        .append(jsonString(RFC3339.format(entry.timestamp())));
    }
    if (entry.trace() != null && !entry.trace().isBlank()) {
      sb.append(",\"trace\":").append(jsonString(entry.trace()));
    }
    if (entry.spanId() != null && !entry.spanId().isBlank()) {
      sb.append(",\"spanId\":").append(jsonString(entry.spanId()));
    }
    if (entry.labels() != null && !entry.labels().isEmpty()) {
      sb.append(",\"labels\":").append(jsonStringMap(entry.labels()));
    }
    if (entry.jsonPayload() != null && !entry.jsonPayload().isEmpty()) {
      sb.append(",\"jsonPayload\":").append(jsonObject(entry.jsonPayload()));
    }
    if (entry.httpRequest() != null) {
      sb
        .append(",\"httpRequest\":")
        .append(jsonHttpRequest(entry.httpRequest()));
    }
    sb.append("}");
  }

  private String jsonHttpRequest(GCloudHttpRequest r) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    if (r.requestMethod() != null) {
      sb.append("\"requestMethod\":").append(jsonString(r.requestMethod()));
      first = false;
    }
    if (r.requestUrl() != null) {
      if (!first) sb.append(",");
      sb.append("\"requestUrl\":").append(jsonString(r.requestUrl()));
      first = false;
    }
    if (r.requestSize() > 0) {
      if (!first) sb.append(",");
      // Cloud Logging API expects requestSize as a string (int64 in JSON)
      sb
        .append("\"requestSize\":")
        .append(jsonString(String.valueOf(r.requestSize())));
      first = false;
    }
    if (!first) sb.append(",");
    sb.append("\"status\":").append(r.status());
    if (r.responseSize() > 0) {
      sb
        .append(",\"responseSize\":")
        .append(jsonString(String.valueOf(r.responseSize())));
    }
    if (r.userAgent() != null && !r.userAgent().isBlank()) {
      sb.append(",\"userAgent\":").append(jsonString(r.userAgent()));
    }
    if (r.remoteIp() != null && !r.remoteIp().isBlank()) {
      sb.append(",\"remoteIp\":").append(jsonString(r.remoteIp()));
    }
    if (r.serverIp() != null && !r.serverIp().isBlank()) {
      sb.append(",\"serverIp\":").append(jsonString(r.serverIp()));
    }
    if (r.latencyMs() > 0) {
      // Cloud Logging latency is a protobuf Duration string: "1.042000000s"
      sb
        .append(",\"latency\":")
        .append(jsonString(toProtoDuration(r.latencyMs())));
    }
    sb.append("}");
    return sb.toString();
  }

  /**
   * Formats milliseconds as a protobuf Duration string, e.g. {@code "0.042000000s"}.
   */
  static String toProtoDuration(long ms) {
    long secs = ms / 1000;
    long nanos = (ms % 1000) * 1_000_000L;
    return secs + "." + String.format("%09d", nanos) + "s";
  }

  private String jsonObject(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (!first) sb.append(",");
      sb
        .append(jsonString(e.getKey()))
        .append(":")
        .append(toJson(e.getValue()));
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  private String jsonStringMap(Map<String, String> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> e : map.entrySet()) {
      if (!first) sb.append(",");
      sb
        .append(jsonString(e.getKey()))
        .append(":")
        .append(jsonString(e.getValue()));
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  String toJson(Object value) {
    if (value == null) return "null";
    if (value instanceof String s) return jsonString(s);
    if (value instanceof Boolean b) return b.toString();
    if (value instanceof Number n) return n.toString();
    if (value instanceof Map<?, ?> m) return jsonObject(
      (Map<String, Object>) m
    );
    if (value instanceof List<?> list) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append(toJson(list.get(i)));
      }
      sb.append("]");
      return sb.toString();
    }
    return jsonString(value.toString());
  }

  private static String jsonString(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append("\"");
    return sb.toString();
  }

  /** URL-encodes the forward-slash in a log name for use in a logName path. */
  private static String encode(String name) {
    return name.replace("/", "%2F");
  }
}
