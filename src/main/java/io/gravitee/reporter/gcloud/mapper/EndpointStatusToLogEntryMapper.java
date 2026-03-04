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
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a Gravitee {@link EndpointStatus} to a GCL {@link LogEntry}.
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
   * @return an empty Optional when the status is not a transition; otherwise a populated LogEntry.
   */
  public Optional<LogEntry> map(EndpointStatus status) {
    if (!status.isTransition()) {
      return Optional.empty();
    }
    try {
      Severity severity = status.isAvailable() ? Severity.INFO : Severity.ERROR;

      Map<String, Object> payload = new HashMap<>();
      payload.put("api_id", status.getApi() != null ? status.getApi() : "");
      payload.put(
        "api_name",
        status.getApiName() != null ? status.getApiName() : ""
      );
      payload.put(
        "endpoint",
        status.getEndpoint() != null ? status.getEndpoint() : ""
      );
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
                s.getName() != null ? s.getName() : "",
                "success",
                s.isSuccess(),
                "message",
                s.getMessage() != null ? s.getMessage() : ""
              )
            )
            .toList()
        );
      }

      Map<String, String> labels = new HashMap<>();
      GCloudLabels.ifPresent(status.getApi(), "gravitee.api_id", labels);
      GCloudLabels.ifPresent(status.getApiName(), "gravitee.api_name", labels);
      GCloudLabels.ifPresent(status.getEndpoint(), "gravitee.endpoint", labels);
      labels.put("gravitee.available", String.valueOf(status.isAvailable()));

      return Optional.of(
        LogEntry.newBuilder(Payload.JsonPayload.of(payload))
          .setSeverity(severity)
          .setLabels(labels)
          .build()
      );
    } catch (Exception e) {
      log.warn("Failed to map EndpointStatus to LogEntry — skipping", e);
      return Optional.empty();
    }
  }
}
