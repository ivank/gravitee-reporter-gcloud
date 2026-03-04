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
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Gravitee v4 {@link MessageMetrics} to an INFO-severity GCL {@link LogEntry}
 * capturing async message counts, error counts, and connector metadata.
 */
public class MessageMetricsToLogEntryMapper {

  private static final Logger log = LoggerFactory.getLogger(
    MessageMetricsToLogEntryMapper.class
  );

  private final GCloudReporterConfiguration cfg;

  public MessageMetricsToLogEntryMapper(GCloudReporterConfiguration cfg) {
    this.cfg = cfg;
  }

  public LogEntry map(MessageMetrics metrics) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put(
        "api_id",
        metrics.getApiId() != null ? metrics.getApiId() : ""
      );
      payload.put(
        "request_id",
        metrics.getRequestId() != null ? metrics.getRequestId() : ""
      );
      payload.put(
        "connector_id",
        metrics.getConnectorId() != null ? metrics.getConnectorId() : ""
      );
      payload.put(
        "connector_type",
        metrics.getConnectorType() != null
          ? metrics.getConnectorType().getLabel()
          : ""
      );
      payload.put("count", metrics.getCount());
      payload.put("error_count", metrics.getErrorCount());
      if (metrics.getGatewayLatencyMs() >= 0) {
        payload.put("gateway_latency_ms", metrics.getGatewayLatencyMs());
      }

      Map<String, String> labels = new HashMap<>();
      GCloudLabels.ifPresent(metrics.getApiId(), "gravitee.api_id", labels);
      GCloudLabels.ifPresent(
        metrics.getConnectorId(),
        "gravitee.connector_id",
        labels
      );
      if (metrics.getConnectorType() != null) {
        labels.put(
          "gravitee.connector_type",
          metrics.getConnectorType().getLabel()
        );
      }

      LogEntry.Builder builder = LogEntry.newBuilder(
        Payload.JsonPayload.of(payload)
      )
        .setSeverity(Severity.INFO)
        .setLabels(labels);

      if (metrics.getRequestId() != null && !metrics.getRequestId().isBlank()) {
        builder.setTrace(metrics.getRequestId());
      }

      return builder.build();
    } catch (Exception e) {
      log.warn("Failed to map MessageMetrics to LogEntry — skipping", e);
      return null;
    }
  }
}
