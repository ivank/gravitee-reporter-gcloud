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
package io.gravitee.reporter.gcloud.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

/**
 * Configuration bean for the Google Cloud Logging reporter.
 * Binds to {@code reporters.gcloud.*} in {@code gravitee.yml}.
 */
public class GCloudReporterConfiguration {

  @Value("${reporters.gcloud.enabled:true}")
  private boolean enabled;

  /** GCP project ID. Required — set {@code reporters.gcloud.projectid} in gravitee.yml. */
  @Value("${reporters.gcloud.projectid:}")
  private String projectId;

  @Value("${reporters.gcloud.logName:gravitee-gateway}")
  private String logName;

  @Value("${reporters.gcloud.resource.type:global}")
  private String resourceType;

  /** Path to a service-account JSON key file. Empty = use Application Default Credentials. */
  @Value("${reporters.gcloud.credentialsFile:}")
  private String credentialsFile;

  @Value("${reporters.gcloud.captureErrors:true}")
  private boolean captureErrors;

  @Value("${reporters.gcloud.reportHealthChecks:true}")
  private boolean reportHealthChecks;

  @Value("${reporters.gcloud.reportLogs:false}")
  private boolean reportLogs;

  @Value("${reporters.gcloud.reportMessageMetrics:true}")
  private boolean reportMessageMetrics;

  @Value("${reporters.gcloud.reportMonitor:false}")
  private boolean reportMonitor;

  @Value("${reporters.gcloud.tracePrefix:}")
  private String tracePrefix;

  @Value("${reporters.gcloud.batchSize:500}")
  private int batchSize;

  @Value("${reporters.gcloud.flushIntervalSeconds:5}")
  private int flushIntervalSeconds;

  // Resource labels are not easily bound via @Value for Map types in a plain bean;
  // leave as empty map — users can extend this class if needed.
  private final Map<String, String> resourceLabels = new HashMap<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getProjectId() {
    return projectId;
  }

  public void setProjectId(String projectId) {
    this.projectId = projectId;
  }

  public String getLogName() {
    return logName;
  }

  public void setLogName(String logName) {
    this.logName = logName;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public String getCredentialsFile() {
    return credentialsFile;
  }

  public void setCredentialsFile(String credentialsFile) {
    this.credentialsFile = credentialsFile;
  }

  public boolean isCaptureErrors() {
    return captureErrors;
  }

  public void setCaptureErrors(boolean captureErrors) {
    this.captureErrors = captureErrors;
  }

  public boolean isReportHealthChecks() {
    return reportHealthChecks;
  }

  public void setReportHealthChecks(boolean reportHealthChecks) {
    this.reportHealthChecks = reportHealthChecks;
  }

  public boolean isReportLogs() {
    return reportLogs;
  }

  public void setReportLogs(boolean reportLogs) {
    this.reportLogs = reportLogs;
  }

  public boolean isReportMessageMetrics() {
    return reportMessageMetrics;
  }

  public void setReportMessageMetrics(boolean reportMessageMetrics) {
    this.reportMessageMetrics = reportMessageMetrics;
  }

  public boolean isReportMonitor() {
    return reportMonitor;
  }

  public void setReportMonitor(boolean reportMonitor) {
    this.reportMonitor = reportMonitor;
  }

  public String getTracePrefix() {
    return tracePrefix;
  }

  public void setTracePrefix(String tracePrefix) {
    this.tracePrefix = tracePrefix;
  }

  public Map<String, String> getResourceLabels() {
    return resourceLabels;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getFlushIntervalSeconds() {
    return flushIntervalSeconds;
  }

  public void setFlushIntervalSeconds(int flushIntervalSeconds) {
    this.flushIntervalSeconds = flushIntervalSeconds;
  }
}
