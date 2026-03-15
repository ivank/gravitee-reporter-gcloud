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
package io.gravitee.reporter.gcloud;

import io.gravitee.common.service.AbstractService;
import io.gravitee.node.api.monitor.Monitor;
import io.gravitee.reporter.api.Reportable;
import io.gravitee.reporter.api.Reporter;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.mapper.EndpointStatusToLogEntryMapper;
import io.gravitee.reporter.gcloud.mapper.LogToLogEntryMapper;
import io.gravitee.reporter.gcloud.mapper.MessageMetricsToLogEntryMapper;
import io.gravitee.reporter.gcloud.mapper.MetricsToLogEntryMapper;
import io.gravitee.reporter.gcloud.writer.GCloudLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Gravitee reporter plugin that writes gateway telemetry to Google Cloud Logging as structured
 * log entries with native {@code httpRequest}, {@code trace}, {@code spanId}, {@code severity},
 * and {@code labels} fields — using the Cloud Logging REST API directly.
 */
public class GCloudReporter
  extends AbstractService<Reporter>
  implements Reporter {

  private static final Logger log = LoggerFactory.getLogger(
    GCloudReporter.class
  );

  @Autowired
  private GCloudReporterConfiguration cfg;

  @Autowired
  private GCloudLogWriter logWriter;

  private MetricsToLogEntryMapper metricsMapper;
  private LogToLogEntryMapper logMapper;
  private EndpointStatusToLogEntryMapper endpointStatusMapper;
  private MessageMetricsToLogEntryMapper messageMetricsMapper;

  @Override
  protected void doStart() throws Exception {
    super.doStart();
    if (!cfg.isEnabled()) {
      log.info("GCloud reporter is disabled — no telemetry will be sent");
      return;
    }

    metricsMapper = new MetricsToLogEntryMapper(cfg);
    logMapper = new LogToLogEntryMapper(cfg);
    endpointStatusMapper = new EndpointStatusToLogEntryMapper(cfg);
    messageMetricsMapper = new MessageMetricsToLogEntryMapper(cfg);

    log.info(
      "GCloud reporter started — writing to project='{}' logName='{}'",
      cfg.getProjectId(),
      cfg.getLogName()
    );
  }

  @Override
  protected void doStop() throws Exception {
    if (logWriter != null) {
      try {
        logWriter.close();
      } catch (Exception e) {
        log.warn("Error closing GCloud log writer on stop", e);
      }
    }
    super.doStop();
  }

  @Override
  public boolean canHandle(Reportable reportable) {
    if (!cfg.isEnabled()) return false;
    return switch (reportable) {
      case Metrics ignored -> true;
      case Log ignored -> cfg.isReportLogs();
      case MessageMetrics ignored -> cfg.isReportMessageMetrics();
      case EndpointStatus ignored -> cfg.isReportHealthChecks();
      case Monitor ignored -> cfg.isReportMonitor();
      default -> false;
    };
  }

  @Override
  public void report(Reportable reportable) {
    if (!cfg.isEnabled()) return;
    try {
      switch (reportable) {
        case Metrics m -> logWriter.enqueue(metricsMapper.map(m));
        case Log l -> logWriter.enqueue(logMapper.map(l));
        case MessageMetrics mm -> logWriter.enqueue(
          messageMetricsMapper.map(mm)
        );
        case EndpointStatus es -> endpointStatusMapper
          .map(es)
          .ifPresent(logWriter::enqueue);
        case Monitor ignored -> {} // monitor reporting not yet implemented
        default -> {}
      }
    } catch (Exception e) {
      log.warn(
        "Unexpected error while reporting to GCloud Logging — skipping",
        e
      );
    }
  }
}
