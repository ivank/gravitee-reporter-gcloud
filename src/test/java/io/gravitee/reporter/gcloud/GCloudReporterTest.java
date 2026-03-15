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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.gravitee.node.api.monitor.Monitor;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.mapper.GCloudTestSupport;
import io.gravitee.reporter.gcloud.writer.GCloudLogEntry;
import io.gravitee.reporter.gcloud.writer.GCloudLogWriter;
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
  private GCloudLogWriter logWriter;

  private GCloudReporter reporter;

  @BeforeEach
  void setUp() throws Exception {
    reporter = new GCloudReporter();
    inject(reporter, "cfg", cfg);
    inject(reporter, "logWriter", logWriter);

    when(cfg.isEnabled()).thenReturn(true);
    when(cfg.isCaptureErrors()).thenReturn(true);
    when(cfg.isReportLogs()).thenReturn(true);
    when(cfg.isReportMessageMetrics()).thenReturn(true);
    when(cfg.isReportHealthChecks()).thenReturn(true);
    when(cfg.isReportMonitor()).thenReturn(false);
    when(cfg.getTracePrefix()).thenReturn("");
    when(cfg.getLogName()).thenReturn("gravitee-gateway");
    when(cfg.getProjectId()).thenReturn("test-project");

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
    assertThat(reporter.canHandle(MessageMetrics.builder().build())).isTrue();
  }

  @Test
  void messageMetricsIsNotHandledWhenDisabled() {
    when(cfg.isReportMessageMetrics()).thenReturn(false);
    assertThat(reporter.canHandle(MessageMetrics.builder().build())).isFalse();
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
  void metricsCallsLogWriterEnqueue() throws Exception {
    reporter.report(GCloudTestSupport.metrics(200));
    verify(logWriter, times(1)).enqueue(any(GCloudLogEntry.class));
  }

  @Test
  void metrics5xxCallsLogWriterEnqueue() throws Exception {
    reporter.report(GCloudTestSupport.metrics(500));
    verify(logWriter, times(1)).enqueue(any(GCloudLogEntry.class));
  }

  @Test
  void disabledReporterDoesNotCallLogWriterEnqueue() throws Exception {
    when(cfg.isEnabled()).thenReturn(false);
    reporter.report(GCloudTestSupport.metrics(200));
    verify(logWriter, never()).enqueue(any());
  }

  @Test
  void endpointStatusNonTransitionDoesNotCallLogWriterEnqueue()
    throws Exception {
    reporter.report(GCloudTestSupport.endpointStatusNonTransition());
    verify(logWriter, never()).enqueue(any());
  }

  @Test
  void endpointStatusTransitionCallsLogWriterEnqueue() throws Exception {
    reporter.report(GCloudTestSupport.endpointStatusTransition(false));
    verify(logWriter, times(1)).enqueue(any(GCloudLogEntry.class));
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
