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

import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.writer.GCloudLogEntry;
import io.gravitee.reporter.gcloud.writer.GCloudSeverity;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Gravitee {@link EndpointStatus} to a {@link GCloudLogEntry}.
 *
 * <p>Only emits an entry on state transitions ({@link EndpointStatus#isTransition()} == true)
 * to avoid flooding Cloud Logging with repeated healthy-check results.
 */
public class EndpointStatusToLogEntryMapper {

  private static final Logger log = LoggerFactory.getLogger(
    EndpointStatusToLogEntryMapper.class
  );

  private final GCloudReporterConfiguration cfg;

  public EndpointStatusToLogEntryMapper(GCloudReporterConfiguration cfg) {
    this.cfg = cfg;
  }

  /**
   * @return an empty Optional when the status is not a transition; otherwise a populated GCloudLogEntry.
   */
  public Optional<GCloudLogEntry> map(EndpointStatus status) {
    if (!status.isTransition()) {
      return Optional.empty();
    }
    try {
      GCloudSeverity severity = status.isAvailable()
        ? GCloudSeverity.INFO
        : GCloudSeverity.ERROR;

      Map<String, Object> payload = new java.util.HashMap<>();
      payload.put("api_id", Objects.toString(status.getApi(), ""));
      payload.put("api_name", Objects.toString(status.getApiName(), ""));
      payload.put("endpoint", Objects.toString(status.getEndpoint(), ""));
      payload.put("available", status.isAvailable());
      payload.put("response_time_ms", status.getResponseTime());
      if (status.getSteps() != null) {
        payload.put(
          "steps",
          status
            .getSteps()
            .stream()
            .map(s ->
              Map.of(
                "name",
                Objects.toString(s.getName(), ""),
                "success",
                s.isSuccess(),
                "message",
                Objects.toString(s.getMessage(), "")
              )
            )
            .toList()
        );
      }

      var labels = new java.util.LinkedHashMap<>(
        GCloudLabels.of(
          "gravitee.api_id",
          status.getApi(),
          "gravitee.api_name",
          status.getApiName(),
          "gravitee.endpoint",
          status.getEndpoint()
        )
      );
      labels.put("gravitee.available", String.valueOf(status.isAvailable()));

      return Optional.of(
        new GCloudLogEntry(
          severity,
          Instant.now(),
          null,
          null,
          labels,
          payload,
          null
        )
      );
    } catch (Exception e) {
      log.warn("Failed to map EndpointStatus to LogEntry — skipping", e);
      return Optional.empty();
    }
  }
}
