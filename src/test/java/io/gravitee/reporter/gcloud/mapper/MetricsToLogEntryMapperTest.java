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

import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.writer.GCloudLogEntry;
import io.gravitee.reporter.gcloud.writer.GCloudSeverity;
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
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.INFO);
  }

  @Test
  void status500MapsToSeverityError() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(500));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.ERROR);
  }

  @Test
  void status500WithCaptureErrorsFalseDoesNotMapToError() {
    when(cfg.isCaptureErrors()).thenReturn(false);
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(500));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.INFO);
  }

  @Test
  void status404MapsToSeverityWarning() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(404));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.WARNING);
  }

  @Test
  void transactionIdMapsToTrace() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.trace()).isEqualTo("txn-aabbccdd");
  }

  @Test
  void tracePrefixIsPrependedToTransactionId() {
    when(cfg.getTracePrefix()).thenReturn("projects/my-project/traces/");
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.trace()).isEqualTo(
      "projects/my-project/traces/txn-aabbccdd"
    );
  }

  @Test
  void requestIdMapsToSpanId() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.spanId()).isEqualTo("req-11223344");
  }

  @Test
  void httpRequestMethodIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.httpRequest()).isNotNull();
    assertThat(entry.httpRequest().requestMethod()).isEqualTo("GET");
  }

  @Test
  void httpRequestStatusIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().status()).isEqualTo(200);
  }

  @Test
  void httpRequestUserAgentIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().userAgent()).isEqualTo(
      "gravitee-test-client/1.0"
    );
  }

  @Test
  void httpRequestRemoteIpIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().remoteIp()).isEqualTo("10.0.0.1");
  }

  @Test
  void httpRequestLatencyIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().latencyMs()).isEqualTo(42L);
  }

  @Test
  void labelsContainApiId() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.labels()).containsEntry("gravitee.api_id", "api-123");
  }

  @Test
  void labelsContainApiName() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.labels()).containsEntry("gravitee.api_name", "Test API");
  }

  @Test
  void nullFieldsDoNotCauseNpe() {
    Metrics m = new Metrics();
    m.setStatus(200);
    GCloudLogEntry entry = mapper.map(m);
    assertThat(entry).isNotNull();
    assertThat(entry.labels()).doesNotContainKey("gravitee.api_id");
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
