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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.writer.GCloudLogEntry;
import io.gravitee.reporter.gcloud.writer.GCloudSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessageMetricsToLogEntryMapperTest {

  @Mock
  private GCloudReporterConfiguration cfg;

  private MessageMetricsToLogEntryMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new MessageMetricsToLogEntryMapper(cfg);
  }

  @Test
  void severityIsInfo() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.INFO);
  }

  @Test
  void payloadContainsCount() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(
      ((Number) entry.jsonPayload().get("count")).longValue()
    ).isEqualTo(10L);
  }

  @Test
  void payloadContainsErrorCount() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(
      ((Number) entry.jsonPayload().get("error_count")).longValue()
    ).isEqualTo(2L);
  }

  @Test
  void labelsContainApiId() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry.labels()).containsEntry("gravitee.api_id", "api-123");
  }

  @Test
  void labelsContainConnectorId() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry.labels()).containsEntry(
      "gravitee.connector_id",
      "connector-kafka"
    );
  }

  @Test
  void requestIdMapsToTrace() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry.trace()).isEqualTo("req-msg-001");
  }
}
