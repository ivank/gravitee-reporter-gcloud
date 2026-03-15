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
import io.gravitee.reporter.gcloud.mapper.EndpointStatusToLogEntryMapper;
import io.gravitee.reporter.gcloud.mapper.GCloudTestSupport;
import io.gravitee.reporter.gcloud.mapper.LogToLogEntryMapper;
import io.gravitee.reporter.gcloud.mapper.MessageMetricsToLogEntryMapper;
import io.gravitee.reporter.gcloud.mapper.MetricsToLogEntryMapper;
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
    when(cfg.isEnabled()).thenReturn(true);
    when(cfg.isCaptureErrors()).thenReturn(true);
    when(cfg.isReportLogs()).thenReturn(true);
    when(cfg.isReportMessageMetrics()).thenReturn(true);
    when(cfg.isReportHealthChecks()).thenReturn(true);
    when(cfg.isReportMonitor()).thenReturn(false);
    when(cfg.getTracePrefix()).thenReturn("");
    when(cfg.getLogName()).thenReturn("gravitee-gateway");
    when(cfg.getProjectId()).thenReturn("test-project");

    reporter = new GCloudReporter();
    inject(reporter, "cfg", cfg);
    inject(reporter, "logWriter", logWriter);
    inject(reporter, "metricsMapper", new MetricsToLogEntryMapper(cfg));
    inject(reporter, "logMapper", new LogToLogEntryMapper(cfg));
    inject(
      reporter,
      "endpointStatusMapper",
      new EndpointStatusToLogEntryMapper(cfg)
    );
    inject(
      reporter,
      "messageMetricsMapper",
      new MessageMetricsToLogEntryMapper(cfg)
    );

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
