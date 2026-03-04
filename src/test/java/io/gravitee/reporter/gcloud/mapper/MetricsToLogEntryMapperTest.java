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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Severity;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricsToLogEntryMapperTest {

  @Mock
  private GCloudReporterConfiguration cfg;

  private MetricsToLogEntryMapper mapper;

  @BeforeEach
  void setUp() {
    when(cfg.isCaptureErrors()).thenReturn(true);
    when(cfg.getTracePrefix()).thenReturn("");
    mapper = new MetricsToLogEntryMapper(cfg);
  }

  @Test
  void status200MapsToSeverityInfo() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.getSeverity()).isEqualTo(Severity.INFO);
  }

  @Test
  void status500MapsToSeverityError() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(500));
    assertThat(entry).isNotNull();
    assertThat(entry.getSeverity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void status500WithCaptureErrorsFalseDoesNotMapToError() {
    when(cfg.isCaptureErrors()).thenReturn(false);
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(500));
    assertThat(entry).isNotNull();
    assertThat(entry.getSeverity()).isEqualTo(Severity.INFO);
  }

  @Test
  void status404MapsToSeverityWarning() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(404));
    assertThat(entry).isNotNull();
    assertThat(entry.getSeverity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void transactionIdMapsToTrace() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.getTrace()).isEqualTo("txn-aabbccdd");
  }

  @Test
  void tracePrefixIsPrependedToTransactionId() {
    when(cfg.getTracePrefix()).thenReturn("projects/my-project/traces/");
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.getTrace()).isEqualTo(
      "projects/my-project/traces/txn-aabbccdd"
    );
  }

  @Test
  void requestIdMapsToSpanId() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.getSpanId()).isEqualTo("req-11223344");
  }

  @Test
  void httpRequestMethodIsPopulated() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.getHttpRequest()).isNotNull();
    assertThat(entry.getHttpRequest().getRequestMethod()).isEqualTo(
      com.google.cloud.logging.HttpRequest.RequestMethod.GET
    );
  }

  @Test
  void httpRequestStatusIsPopulated() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.getHttpRequest().getStatus()).isEqualTo(200);
  }

  @Test
  void httpRequestUserAgentIsPopulated() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.getHttpRequest().getUserAgent()).isEqualTo(
      "gravitee-test-client/1.0"
    );
  }

  @Test
  void httpRequestRemoteIpIsPopulated() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.getHttpRequest().getRemoteIp()).isEqualTo("10.0.0.1");
  }

  @Test
  void httpRequestLatencyIsPopulated() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.getHttpRequest().getLatencyDuration()).isEqualTo(
      java.time.Duration.ofMillis(42)
    );
  }

  @Test
  void labelsContainApiId() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.getLabels()).containsEntry("gravitee.api_id", "api-123");
  }

  @Test
  void labelsContainApiName() {
    LogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.getLabels()).containsEntry(
      "gravitee.api_name",
      "Test API"
    );
  }

  @Test
  void nullFieldsDoNotCauseNpe() {
    Metrics m = new Metrics();
    m.setStatus(200);
    LogEntry entry = mapper.map(m);
    assertThat(entry).isNotNull();
    assertThat(entry.getLabels()).doesNotContainKey("gravitee.api_id");
  }

  @Test
  void numericPathSegmentIsSanitized() {
    assertThat(
      MetricsToLogEntryMapper.sanitizePath("/api/v1/users/42")
    ).isEqualTo("/api/v1/users/{id}");
  }

  @Test
  void uuidPathSegmentIsSanitized() {
    assertThat(
      MetricsToLogEntryMapper.sanitizePath(
        "/api/v1/items/550e8400-e29b-41d4-a716-446655440000"
      )
    ).isEqualTo("/api/v1/items/{id}");
  }

  @Test
  void nonIdPathSegmentsAreNotSanitized() {
    assertThat(MetricsToLogEntryMapper.sanitizePath("/api/v1/users")).isEqualTo(
      "/api/v1/users"
    );
  }

  @Test
  void nullPathReturnsNull() {
    assertThat(MetricsToLogEntryMapper.sanitizePath(null)).isNull();
  }
}
