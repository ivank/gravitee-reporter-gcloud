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
package io.gravitee.reporter.gcloud.integration;

import static org.awaitility.Awaitility.await;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin client for the Google Cloud Logging API used to verify that the reporter
 * wrote the expected log entries during integration tests.
 *
 * <p>Mirrors the pattern of {@code SentryApiClient} from gravitee-reporter-sentry:
 * poll with Awaitility until at least one entry is found or timeout expires.
 */
class GCloudLoggingClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(
    GCloudLoggingClient.class
  );

  private final Logging logging;
  private final String projectId;

  GCloudLoggingClient(String projectId) {
    this.projectId = projectId;
    this.logging = LoggingOptions.newBuilder()
      .setProjectId(projectId)
      .build()
      .getService();
    log.info("GCloudLoggingClient connected to project '{}'", projectId);
  }

  /**
   * Polls Cloud Logging every 5 seconds until at least one entry matching {@code filter}
   * is found, or {@code timeout} expires.
   *
   * @param filter Cloud Logging filter expression, e.g.
   *               {@code logName="projects/my-proj/logs/gravitee-gateway" AND httpRequest.status=200}
   * @param timeout maximum wait duration
   * @return list of matching log entries (non-empty)
   * @throws org.awaitility.core.ConditionTimeoutException if no entry is found within timeout
   */
  List<LogEntry> pollForEntries(String filter, Duration timeout) {
    AtomicReference<List<LogEntry>> found = new AtomicReference<>(List.of());

    await("log entry matching: " + filter)
      .atMost(timeout)
      .pollInterval(Duration.ofSeconds(5))
      .until(() -> {
        List<LogEntry> entries = fetchEntries(filter);
        if (!entries.isEmpty()) {
          found.set(entries);
          return true;
        }
        return false;
      });

    return found.get();
  }

  private List<LogEntry> fetchEntries(String filter) {
    try {
      List<LogEntry> entries = new ArrayList<>();
      Logging.EntryListOption[] opts = {
        Logging.EntryListOption.filter(filter),
        Logging.EntryListOption.pageSize(20),
      };
      logging.listLogEntries(opts).iterateAll().forEach(entries::add);
      log.debug("filter='{}' → {} entries", filter, entries.size());
      return entries;
    } catch (Exception e) {
      log.warn("Error fetching log entries (will retry): {}", e.getMessage());
      return List.of();
    }
  }

  String logNameFilter(String logName) {
    return "logName=\"projects/%s/logs/%s\"".formatted(projectId, logName);
  }

  @Override
  public void close() {
    try {
      logging.close();
    } catch (Exception e) {
      log.warn("Error closing GCloud Logging client", e);
    }
  }
}
