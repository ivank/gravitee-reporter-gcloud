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
package io.gravitee.reporter.gcloud.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Serializes batches of {@link GCloudLogEntry} objects to the JSON body expected by
 * Cloud Logging REST API v2 {@code entries:write}.
 */
public class GCloudEntrySerializer {

  private static final DateTimeFormatter RFC3339 =
    DateTimeFormatter.ISO_INSTANT;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String fullLogName;
  private final String resourceType;
  private final Map<String, String> resourceLabels;

  public GCloudEntrySerializer(
    String projectId,
    String logName,
    String resourceType,
    Map<String, String> resourceLabels
  ) {
    this.fullLogName =
      "projects/" + projectId + "/logs/" + logName.replace("/", "%2F");
    this.resourceType = resourceType;
    this.resourceLabels = resourceLabels != null ? resourceLabels : Map.of();
  }

  /**
   * Serializes a batch of entries to the JSON body for {@code POST /v2/entries:write}.
   */
  public String serialize(List<GCloudLogEntry> entries) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("logName", fullLogName);

    ObjectNode resource = root.putObject("resource");
    resource.put("type", resourceType);
    ObjectNode resLabels = resource.putObject("labels");
    resourceLabels.forEach(resLabels::put);

    ArrayNode entriesNode = root.putArray("entries");
    entries.stream().map(this::toEntryNode).forEach(entriesNode::add);

    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize log entries", e);
    }
  }

  private ObjectNode toEntryNode(GCloudLogEntry entry) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("severity", entry.severity().name());
    if (entry.timestamp() != null) {
      node.put("timestamp", RFC3339.format(entry.timestamp()));
    }
    if (entry.trace() != null && !entry.trace().isBlank()) {
      node.put("trace", entry.trace());
    }
    if (entry.spanId() != null && !entry.spanId().isBlank()) {
      node.put("spanId", entry.spanId());
    }
    if (entry.labels() != null && !entry.labels().isEmpty()) {
      ObjectNode labelsNode = node.putObject("labels");
      entry.labels().forEach(labelsNode::put);
    }
    if (entry.jsonPayload() != null && !entry.jsonPayload().isEmpty()) {
      node.set("jsonPayload", MAPPER.valueToTree(entry.jsonPayload()));
    }
    if (entry.httpRequest() != null) {
      node.set("httpRequest", toHttpRequestNode(entry.httpRequest()));
    }
    return node;
  }

  private ObjectNode toHttpRequestNode(GCloudHttpRequest r) {
    ObjectNode node = MAPPER.createObjectNode();
    if (r.requestMethod() != null) node.put("requestMethod", r.requestMethod());
    if (r.requestUrl() != null) node.put("requestUrl", r.requestUrl());
    if (r.requestSize() > 0) node.put(
      "requestSize",
      String.valueOf(r.requestSize())
    );
    node.put("status", r.status());
    if (r.responseSize() > 0) node.put(
      "responseSize",
      String.valueOf(r.responseSize())
    );
    if (r.userAgent() != null && !r.userAgent().isBlank()) node.put(
      "userAgent",
      r.userAgent()
    );
    if (r.remoteIp() != null && !r.remoteIp().isBlank()) node.put(
      "remoteIp",
      r.remoteIp()
    );
    if (r.serverIp() != null && !r.serverIp().isBlank()) node.put(
      "serverIp",
      r.serverIp()
    );
    if (r.latencyMs() > 0) node.put("latency", toProtoDuration(r.latencyMs()));
    return node;
  }

  /**
   * Formats milliseconds as a protobuf Duration string, e.g. {@code "0.042000000s"}.
   */
  static String toProtoDuration(long ms) {
    long secs = ms / 1000;
    long nanos = (ms % 1000) * 1_000_000L;
    return secs + "." + String.format("%09d", nanos) + "s";
  }
}
