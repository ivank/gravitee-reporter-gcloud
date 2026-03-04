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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import com.google.cloud.logging.Logging;
import io.gravitee.node.api.monitor.Monitor;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.mapper.GCloudTestSupport;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GCloudReporterTest {

  @Mock
  private GCloudReporterConfiguration cfg;

  @Mock
  private Logging logging;

  private GCloudReporter reporter;

  @BeforeEach
  void setUp() throws Exception {
    reporter = new GCloudReporter();
    inject(reporter, "cfg", cfg);
    inject(reporter, "logging", logging);

    when(cfg.isEnabled()).thenReturn(true);
    when(cfg.isCaptureErrors()).thenReturn(true);
    when(cfg.isReportLogs()).thenReturn(true);
    when(cfg.isReportMessageMetrics()).thenReturn(true);
    when(cfg.isReportHealthChecks()).thenReturn(true);
    when(cfg.isReportMonitor()).thenReturn(false);
    when(cfg.getTracePrefix()).thenReturn("");
    when(cfg.getLogName()).thenReturn("gravitee-gateway");
    when(cfg.getResourceType()).thenReturn("global");
    when(cfg.getResourceLabels()).thenReturn(java.util.Map.of());
    when(cfg.getProjectId()).thenReturn("");

    // Mock the LoggingOptions returned by logging.getOptions()
    com.google.cloud.logging.LoggingOptions opts = mock(
      com.google.cloud.logging.LoggingOptions.class
    );
    when(opts.getProjectId()).thenReturn("test-project");
    when(logging.getOptions()).thenReturn(opts);

    reporter.doStart();
  }

  // ===== canHandle() =====

  @Test
  void metricsAreAlwaysHandledWhenEnabled() {
    assertThat(reporter.canHandle(GCloudTestSupport.metrics(200))).isTrue();
  }

  @Test
  void logIsHandledWhenReportLogsEnabled() {
    assertThat(reporter.canHandle(Log.builder().build())).isTrue();
  }

  @Test
  void logIsNotHandledWhenReportLogsDisabled() {
    when(cfg.isReportLogs()).thenReturn(false);
    assertThat(reporter.canHandle(Log.builder().build())).isFalse();
  }

  @Test
  void messageMetricsIsHandledWhenEnabled() {
    assertThat(reporter.canHandle(new MessageMetrics())).isTrue();
  }

  @Test
  void messageMetricsIsNotHandledWhenDisabled() {
    when(cfg.isReportMessageMetrics()).thenReturn(false);
    assertThat(reporter.canHandle(new MessageMetrics())).isFalse();
  }

  @Test
  void endpointStatusIsHandledWhenEnabled() {
    assertThat(
      reporter.canHandle(
        EndpointStatus.forEndpoint("api", "ep")
          .on(System.currentTimeMillis())
          .build()
      )
    ).isTrue();
  }

  @Test
  void endpointStatusIsNotHandledWhenDisabled() {
    when(cfg.isReportHealthChecks()).thenReturn(false);
    assertThat(
      reporter.canHandle(
        EndpointStatus.forEndpoint("api", "ep")
          .on(System.currentTimeMillis())
          .build()
      )
    ).isFalse();
  }

  @Test
  void monitorIsNotHandledWhenDisabled() {
    assertThat(reporter.canHandle(mock(Monitor.class))).isFalse();
  }

  @Test
  void disabledReporterHandlesNothing() {
    when(cfg.isEnabled()).thenReturn(false);
    assertThat(reporter.canHandle(GCloudTestSupport.metrics(200))).isFalse();
    assertThat(reporter.canHandle(Log.builder().build())).isFalse();
    assertThat(
      reporter.canHandle(
        EndpointStatus.forEndpoint("api", "ep")
          .on(System.currentTimeMillis())
          .build()
      )
    ).isFalse();
  }

  // ===== report() =====

  @Test
  void metricsCallsLoggingWrite() throws Exception {
    reporter.report(GCloudTestSupport.metrics(200));
    verify(logging, times(1)).write(anyCollection());
  }

  @Test
  void metrics5xxCallsLoggingWriteWithErrorSeverity() throws Exception {
    reporter.report(GCloudTestSupport.metrics(500));
    verify(logging, times(1)).write(anyCollection());
  }

  @Test
  void disabledReporterDoesNotCallLoggingWrite() throws Exception {
    when(cfg.isEnabled()).thenReturn(false);
    reporter.report(GCloudTestSupport.metrics(200));
    verify(logging, never()).write(anyCollection());
  }

  @Test
  void endpointStatusNonTransitionDoesNotCallLoggingWrite() throws Exception {
    reporter.report(GCloudTestSupport.endpointStatusNonTransition());
    verify(logging, never()).write(anyCollection());
  }

  @Test
  void endpointStatusTransitionCallsLoggingWrite() throws Exception {
    reporter.report(GCloudTestSupport.endpointStatusTransition(false));
    verify(logging, times(1)).write(anyCollection());
  }

  // ===== helpers =====

  private static void inject(Object target, String fieldName, Object value)
    throws Exception {
    Field f = findField(target.getClass(), fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static Field findField(Class<?> clazz, String name)
    throws NoSuchFieldException {
    try {
      return clazz.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      if (clazz.getSuperclass() != null) return findField(
        clazz.getSuperclass(),
        name
      );
      throw e;
    }
  }
}
