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

import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.writer.GCloudLogEntry;
import io.gravitee.reporter.gcloud.writer.GCloudSeverity;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricsToLogEntryMapperTest {

  @Mock
  private GCloudReporterConfiguration cfg;

  private MetricsToLogEntryMapper mapper;

  @BeforeEach
  void setUp() {
    when(cfg.isCaptureErrors()).thenReturn(true);
    when(cfg.getTracePrefix()).thenReturn("");
    mapper = new MetricsToLogEntryMapper(cfg);
  }

  @Test
  void status200MapsToSeverityInfo() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.INFO);
  }

  @Test
  void status500MapsToSeverityError() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(500));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.ERROR);
  }

  @Test
  void status500WithCaptureErrorsFalseDoesNotMapToError() {
    when(cfg.isCaptureErrors()).thenReturn(false);
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(500));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.INFO);
  }

  @Test
  void status404MapsToSeverityWarning() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(404));
    assertThat(entry).isNotNull();
    assertThat(entry.severity()).isEqualTo(GCloudSeverity.WARNING);
  }

  @Test
  void transactionIdMapsToTrace() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.trace()).isEqualTo("txn-aabbccdd");
  }

  @Test
  void tracePrefixIsPrependedToTransactionId() {
    when(cfg.getTracePrefix()).thenReturn("projects/my-project/traces/");
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.trace()).isEqualTo(
      "projects/my-project/traces/txn-aabbccdd"
    );
  }

  @Test
  void requestIdMapsToSpanId() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.spanId()).isEqualTo("req-11223344");
  }

  @Test
  void httpRequestMethodIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry).isNotNull();
    assertThat(entry.httpRequest()).isNotNull();
    assertThat(entry.httpRequest().requestMethod()).isEqualTo("GET");
  }

  @Test
  void httpRequestStatusIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().status()).isEqualTo(200);
  }

  @Test
  void httpRequestUserAgentIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().userAgent()).isEqualTo(
      "gravitee-test-client/1.0"
    );
  }

  @Test
  void httpRequestRemoteIpIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().remoteIp()).isEqualTo("10.0.0.1");
  }

  @Test
  void httpRequestLatencyIsPopulated() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.httpRequest().latencyMs()).isEqualTo(42L);
  }

  @Test
  void labelsContainApiId() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.labels()).containsEntry("gravitee.api_id", "api-123");
  }

  @Test
  void labelsContainApiName() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.labels()).containsEntry("gravitee.api_name", "Test API");
  }

  @Test
  void nullFieldsDoNotCauseNpe() {
    Metrics m = new Metrics();
    m.setStatus(200);
    GCloudLogEntry entry = mapper.map(m);
    assertThat(entry).isNotNull();
    assertThat(entry.labels()).doesNotContainKey("gravitee.api_id");
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadApiSectionContainsIdAndName() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> api = (Map<String, Object>) entry
      .jsonPayload()
      .get("api");
    assertThat(api)
      .containsEntry("id", "api-123")
      .containsEntry("name", "Test API");
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadContextContainsPlanAndApplication() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> ctx = (Map<String, Object>) entry
      .jsonPayload()
      .get("context");
    assertThat(ctx)
      .containsEntry("plan", "plan-789")
      .containsEntry("application", "app-456");
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadEntrypointRequestContainsMethodAndUri() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> entrypoint = (Map<String, Object>) entry
      .jsonPayload()
      .get("entrypoint");
    Map<String, Object> req = (Map<String, Object>) entrypoint.get("request");
    assertThat(req)
      .containsEntry("method", "GET")
      .containsEntry("uri", "/api/v1/users/42");
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadEntrypointRequestPathIsSanitized() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> entrypoint = (Map<String, Object>) entry
      .jsonPayload()
      .get("entrypoint");
    Map<String, Object> req = (Map<String, Object>) entrypoint.get("request");
    assertThat(req).containsEntry("path", "/api/v1/users/{id}");
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadEntrypointResponseContainsStatusAndTime() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> entrypoint = (Map<String, Object>) entry
      .jsonPayload()
      .get("entrypoint");
    Map<String, Object> resp = (Map<String, Object>) entrypoint.get("response");
    assertThat(resp).containsEntry("status", 200);
    assertThat(((Number) resp.get("time_ms")).longValue()).isEqualTo(42L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadEndpointResponseTimeIsSet() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> endpoint = (Map<String, Object>) entry
      .jsonPayload()
      .get("endpoint");
    Map<String, Object> resp = (Map<String, Object>) endpoint.get("response");
    assertThat(((Number) resp.get("time_ms")).longValue()).isEqualTo(37L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadGatewayLatencyIsSet() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> gateway = (Map<String, Object>) entry
      .jsonPayload()
      .get("gateway");
    assertThat(((Number) gateway.get("latency_ms")).longValue()).isEqualTo(5L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadErrorSectionAbsentFor200() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    assertThat(entry.jsonPayload()).doesNotContainKey("error");
  }

  @Test
  @SuppressWarnings("unchecked")
  void payloadErrorSectionPresentWhenErrorSet() {
    Metrics m = GCloudTestSupport.metrics(500);
    m.setErrorMessage("upstream timeout");
    m.setErrorKey("GATEWAY_TIMEOUT");
    GCloudLogEntry entry = mapper.map(m);
    Map<String, Object> error = (Map<String, Object>) entry
      .jsonPayload()
      .get("error");
    assertThat(error)
      .containsEntry("message", "upstream timeout")
      .containsEntry("key", "GATEWAY_TIMEOUT");
  }

  @Test
  @SuppressWarnings("unchecked")
  void entrypointRequestHeadersPopulatedWhenLogPresent() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metricsWithLog(200));
    Map<String, Object> req = (Map<String, Object>) ((Map<String, Object>) entry
        .jsonPayload()
        .get("entrypoint")).get("request");
    Map<String, String> headers = (Map<String, String>) req.get("headers");
    assertThat(headers).containsEntry("X-Request-Id", "req-header-001");
  }

  @Test
  @SuppressWarnings("unchecked")
  void entrypointResponseHeadersPopulatedWhenLogPresent() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metricsWithLog(200));
    Map<String, Object> resp = (Map<String, Object>) ((Map<
        String,
        Object
      >) entry.jsonPayload().get("entrypoint")).get("response");
    Map<String, String> headers = (Map<String, String>) resp.get("headers");
    assertThat(headers).containsEntry("Content-Type", "application/json");
  }

  @Test
  @SuppressWarnings("unchecked")
  void endpointRequestPopulatedWhenLogPresent() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metricsWithLog(200));
    Map<String, Object> endpointReq = (Map<String, Object>) ((Map<
        String,
        Object
      >) entry.jsonPayload().get("endpoint")).get("request");
    assertThat(endpointReq).isNotNull();
    assertThat(endpointReq).containsEntry("method", "GET");
    assertThat(endpointReq).containsEntry("uri", "/backend/users/42");
    Map<String, String> headers = (Map<String, String>) endpointReq.get(
      "headers"
    );
    assertThat(headers).containsEntry("X-Forwarded-For", "10.0.0.1");
  }

  @Test
  @SuppressWarnings("unchecked")
  void endpointResponseHeadersPopulatedWhenLogPresent() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metricsWithLog(200));
    Map<String, Object> endpResp = (Map<String, Object>) ((Map<
        String,
        Object
      >) entry.jsonPayload().get("endpoint")).get("response");
    Map<String, String> headers = (Map<String, String>) endpResp.get("headers");
    assertThat(headers).containsEntry("X-Backend-Trace", "trace-xyz");
  }

  @Test
  @SuppressWarnings("unchecked")
  void headersAbsentWhenNoLogPresent() {
    GCloudLogEntry entry = mapper.map(GCloudTestSupport.metrics(200));
    Map<String, Object> req = (Map<String, Object>) ((Map<String, Object>) entry
        .jsonPayload()
        .get("entrypoint")).get("request");
    assertThat(req).doesNotContainKey("headers");
    Map<String, Object> endpoint = (Map<String, Object>) entry
      .jsonPayload()
      .get("endpoint");
    assertThat(endpoint).doesNotContainKey("request");
  }

  @Test
  void numericPathSegmentIsSanitized() {
    assertThat(
      MetricsToLogEntryMapper.sanitizePath("/api/v1/users/42")
    ).isEqualTo("/api/v1/users/{id}");
  }

  @Test
  void uuidPathSegmentIsSanitized() {
    assertThat(
      MetricsToLogEntryMapper.sanitizePath(
        "/api/v1/items/550e8400-e29b-41d4-a716-446655440000"
      )
    ).isEqualTo("/api/v1/items/{id}");
  }

  @Test
  void nonIdPathSegmentsAreNotSanitized() {
    assertThat(MetricsToLogEntryMapper.sanitizePath("/api/v1/users")).isEqualTo(
      "/api/v1/users"
    );
  }

  @Test
  void nullPathReturnsNull() {
    assertThat(MetricsToLogEntryMapper.sanitizePath(null)).isNull();
  }
}
