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

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Severity;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
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
    LogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry).isNotNull();
    assertThat(entry.getSeverity()).isEqualTo(Severity.DEBUG);
  }

  @Test
  void requestIdMapsToTrace() {
    LogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.getTrace()).isEqualTo("req-001");
  }

  @Test
  void requestIdWithPrefixMapsToTrace() {
    when(cfg.getTracePrefix()).thenReturn("projects/my-proj/traces/");
    LogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.getTrace()).isEqualTo("projects/my-proj/traces/req-001");
  }

  @Test
  void labelsContainApiId() {
    LogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.getLabels()).containsEntry("gravitee.api_id", "api-abc");
  }

  @Test
  void labelsContainRequestId() {
    LogEntry entry = mapper.map(buildLog("req-001", "Hello"));
    assertThat(entry.getLabels()).containsEntry(
      "gravitee.request_id",
      "req-001"
    );
  }

  @Test
  void bodyIsNotTruncatedWhenBelowLimit() {
    String body = "A".repeat(100);
    LogEntry entry = mapper.map(buildLog("req-001", body));
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
    LogEntry entry = mapper.map(log);
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
