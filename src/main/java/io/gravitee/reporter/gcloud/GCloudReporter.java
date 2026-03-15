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

  @Autowired
  private MetricsToLogEntryMapper metricsMapper;

  @Autowired
  private LogToLogEntryMapper logMapper;

  @Autowired
  private EndpointStatusToLogEntryMapper endpointStatusMapper;

  @Autowired
  private MessageMetricsToLogEntryMapper messageMetricsMapper;

  @Override
  protected void doStart() throws Exception {
    super.doStart();
    if (!cfg.isEnabled()) {
      log.info("GCloud reporter is disabled — no telemetry will be sent");
      return;
    }
    log.info(
      "GCloud reporter started — writing to project='{}' logName='{}'",
      cfg.getProjectId(),
      cfg.getLogName()
    );
  }

  @Override
  protected void doStop() throws Exception {
    try {
      logWriter.close();
    } catch (Exception e) {
      log.warn("Error closing GCloud log writer on stop", e);
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
