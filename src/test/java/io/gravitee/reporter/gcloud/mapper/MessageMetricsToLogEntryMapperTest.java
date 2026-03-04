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

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.Severity;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import java.util.Map;
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
    LogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry).isNotNull();
    assertThat(entry.getSeverity()).isEqualTo(Severity.INFO);
  }

  @Test
  void payloadContainsCount() {
    LogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    Map<String, ?> fields =
      ((Payload.JsonPayload) entry.getPayload()).getDataAsMap();
    assertThat(((Number) fields.get("count")).longValue()).isEqualTo(10L);
  }

  @Test
  void payloadContainsErrorCount() {
    LogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    Map<String, ?> fields =
      ((Payload.JsonPayload) entry.getPayload()).getDataAsMap();
    assertThat(((Number) fields.get("error_count")).longValue()).isEqualTo(2L);
  }

  @Test
  void labelsContainApiId() {
    LogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry.getLabels()).containsEntry("gravitee.api_id", "api-123");
  }

  @Test
  void labelsContainConnectorId() {
    LogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry.getLabels()).containsEntry(
      "gravitee.connector_id",
      "connector-kafka"
    );
  }

  @Test
  void requestIdMapsToTrace() {
    LogEntry entry = mapper.map(GCloudTestSupport.messageMetrics());
    assertThat(entry.getTrace()).isEqualTo("req-msg-001");
  }
}
