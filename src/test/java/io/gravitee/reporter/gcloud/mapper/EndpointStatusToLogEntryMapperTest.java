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
    Optional<GCloudLogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusNonTransition()
    );
    assertThat(result).isEmpty();
  }

  @Test
  void transitionToUnavailableMapsToSeverityError() {
    Optional<GCloudLogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(false)
    );
    assertThat(result).isPresent();
    assertThat(result.get().severity()).isEqualTo(GCloudSeverity.ERROR);
  }

  @Test
  void transitionToAvailableMapsToSeverityInfo() {
    Optional<GCloudLogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().severity()).isEqualTo(GCloudSeverity.INFO);
  }

  @Test
  void labelsContainApiId() {
    Optional<GCloudLogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().labels()).containsEntry(
      "gravitee.api_id",
      "api-123"
    );
  }

  @Test
  void labelsContainApiName() {
    Optional<GCloudLogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().labels()).containsEntry(
      "gravitee.api_name",
      "Test API"
    );
  }

  @Test
  void labelsContainEndpoint() {
    Optional<GCloudLogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(true)
    );
    assertThat(result).isPresent();
    assertThat(result.get().labels()).containsEntry(
      "gravitee.endpoint",
      "https://backend.example.com/health"
    );
  }

  @Test
  void labelsContainAvailableFlag() {
    Optional<GCloudLogEntry> result = mapper.map(
      GCloudTestSupport.endpointStatusTransition(false)
    );
    assertThat(result).isPresent();
    assertThat(result.get().labels()).containsEntry(
      "gravitee.available",
      "false"
    );
  }
}
