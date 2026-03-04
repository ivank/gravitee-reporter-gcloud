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
import com.google.cloud.logging.Severity;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EndpointStatusToLogEntryMapperTest {

  @Mock
  private GCloudReporterConfiguration cfg;

  private EndpointStatusToLogEntryMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new EndpointStatusToLogEntryMapper(cfg);
  }

  @Test
  void nonTransitionReturnsEmpty() {
    Optional<LogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusNonTransition()
    );
    assertThat(result).isEmpty();
  }

  @Test
  void transitionToUnavailableMapsToSeverityError() {
    Optional<LogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(false)
    );
    assertThat(result).isPresent();
    assertThat(result.get().getSeverity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void transitionToAvailableMapsToSeverityInfo() {
    Optional<LogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().getSeverity()).isEqualTo(Severity.INFO);
  }

  @Test
  void labelsContainApiId() {
    Optional<LogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().getLabels()).containsEntry(
      "gravitee.api_id",
      "api-123"
    );
  }

  @Test
  void labelsContainApiName() {
    Optional<LogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().getLabels()).containsEntry(
      "gravitee.api_name",
      "Test API"
    );
  }

  @Test
  void labelsContainEndpoint() {
    Optional<LogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().getLabels()).containsEntry(
      "gravitee.endpoint",
      "https://backend.example.com/health"
    );
  }

  @Test
  void labelsContainAvailableFlag() {
    Optional<LogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(false)
    );
    assertThat(result).isPresent();
    assertThat(result.get().getLabels()).containsEntry(
      "gravitee.available",
      "false"
    );
  }
}
