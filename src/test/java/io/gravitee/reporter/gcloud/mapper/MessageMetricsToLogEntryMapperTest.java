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
