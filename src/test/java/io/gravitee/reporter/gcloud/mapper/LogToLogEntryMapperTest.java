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
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
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
class LogToLogEntryMapperTest {

  @Mock
  private GCloudReporterConfiguration cfg;

  private LogToLogEntryMapper mapper;

  @BeforeEach
  void setUp() {
    when(cfg.getTracePrefix()).thenReturn("");
    mapper = new LogToLogEntryMapper(cfg);
  }

  @Test
  void severityIsDebug() {
    GCloudLogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.DEBUG);
  }

  @Test
  void requestIdMapsToTrace() {
    GCloudLogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.trace()).isEqualTo("req-001");
  }

  @Test
  void requestIdWithPrefixMapsToTrace() {
    when(cfg.getTracePrefix()).thenReturn("projects/my-proj/traces/");
    GCloudLogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.trace()).isEqualTo("projects/my-proj/traces/req-001");
  }

  @Test
  void labelsContainApiId() {
    GCloudLogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.labels()).containsEntry("gravitee.api_id", "api-abc");
  }

  @Test
  void labelsContainRequestId() {
    GCloudLogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.labels()).containsEntry("gravitee.request_id", "req-001");
  }

  @Test
  void bodyIsNotTruncatedWhenBelowLimit() {
    String body = "A".repeat(100);
    GCloudLogEntry entry = mapper.map(buildLog("req-001", body));
    assertThat(entry).isNotNull();
  }

  @Test
  void bodyIsTruncatedAt4096Characters() {
    String longBody = "X".repeat(5000);
    String truncated = LogToLogEntryMapper.truncate(longBody);
    assertThat(truncated).hasSize(4097); // 4096 chars + ellipsis (1 char)
    assertThat(truncated).endsWith("…");
  }

  @Test
  void nullBodyIsHandledWithoutNpe() {
    assertThat(LogToLogEntryMapper.truncate(null)).isNull();
  }

  @Test
  void shortBodyIsNotTruncated() {
    String body = "short body";
    assertThat(LogToLogEntryMapper.truncate(body)).isEqualTo(body);
  }

  @Test
  void nullEntrypointRequestIsHandledWithoutNpe() {
    Log log = Log.builder().apiId("api-abc").requestId("req-001").build();
    // entrypointRequest intentionally left null
    GCloudLogEntry entry = mapper.map(log);
    assertThat(entry).isNotNull();
  }

  private Log buildLog(String requestId, String body) {
    Log log = Log.builder().build();
    log.setApiId("api-abc");
    log.setApiName("Test API");
    log.setRequestId(requestId);
    log.setClientIdentifier("client-xyz");

    Request req = new Request();
    req.setMethod(HttpMethod.POST);
    req.setUri("/api/v1/data");
    req.setBody(body);
    log.setEntrypointRequest(req);

    Response resp = new Response(200);
    resp.setBody("{\"ok\":true}");
    log.setEntrypointResponse(resp);

    return log;
  }
}
