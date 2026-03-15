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

import io.gravitee.reporter.api.v4.metric.MessageMetrics;
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
 * Maps a Gravitee v4 {@link MessageMetrics} to an INFO-severity {@link GCloudLogEntry}
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

  public GCloudLogEntry map(MessageMetrics metrics) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("api_id", Objects.toString(metrics.getApiId(), ""));
      payload.put("request_id", Objects.toString(metrics.getRequestId(), ""));
      payload.put(
        "connector_id",
        Objects.toString(metrics.getConnectorId(), "")
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

      var labels = new java.util.LinkedHashMap<>(
        GCloudLabels.of(
          "gravitee.api_id",
          metrics.getApiId(),
          "gravitee.connector_id",
          metrics.getConnectorId(),
          "gravitee.connector_type",
          metrics.getConnectorType() != null
            ? metrics.getConnectorType().getLabel()
            : null
        )
      );

      String trace = (metrics.getRequestId() != null &&
          !metrics.getRequestId().isBlank())
        ? metrics.getRequestId()
        : null;

      return new GCloudLogEntry(
        GCloudSeverity.INFO,
        Instant.now(),
        trace,
        null,
        labels,
        payload,
        null
      );
    } catch (Exception e) {
      log.warn("Failed to map MessageMetrics to LogEntry — skipping", e);
      return null;
    }
  }
}
