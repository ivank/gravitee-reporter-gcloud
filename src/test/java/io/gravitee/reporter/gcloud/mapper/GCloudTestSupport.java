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

import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.reporter.api.common.Request;
import io.gravitee.reporter.api.common.Response;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.common.MessageConnectorType;
import io.gravitee.reporter.api.v4.log.Log;
import io.gravitee.reporter.api.v4.metric.MessageMetrics;
import io.gravitee.reporter.api.v4.metric.Metrics;
import java.time.Instant;

/**
 * Factory methods for building test fixture objects.
 */
public final class GCloudTestSupport {

  private GCloudTestSupport() {}

  public static Metrics metrics(int status) {
    Metrics m = new Metrics();
    m.setApiId("api-123");
    m.setApiName("Test API");
    m.setApplicationId("app-456");
    m.setPlanId("plan-789");
    m.setEndpoint("https://backend.example.com");
    m.setHttpMethod(HttpMethod.GET);
    m.setUri("/api/v1/users/42");
    m.setPathInfo("/api/v1/users/42");
    m.setHost("gateway.example.com");
    m.setRemoteAddress("10.0.0.1");
    m.setLocalAddress("10.0.0.100");
    m.setUserAgent("gravitee-test-client/1.0");
    m.setStatus(status);
    m.setRequestContentLength(128);
    m.setResponseContentLength(512);
    m.setGatewayResponseTimeMs(42);
    m.setGatewayLatencyMs(5);
    m.setEndpointResponseTimeMs(37);
    m.setTransactionId("txn-aabbccdd");
    m.setRequestId("req-11223344");
    // setTimestamp() accepts long (epoch millis via Lombok @Getter/@Setter on the primitive field)
    m.setTimestamp(Instant.parse("2024-01-15T12:00:00Z").toEpochMilli());
    return m;
  }

  /** Builds a {@link Metrics} with a populated {@link Log} (simulates API logging enabled). */
  public static Metrics metricsWithLog(int status) {
    Metrics m = metrics(status);

    HttpHeaders epReqHeaders = HttpHeaders.create()
      .add("X-Request-Id", "req-header-001")
      .add("Content-Type", "application/json");

    Request entrypointReq = new Request();
    entrypointReq.setMethod(HttpMethod.GET);
    entrypointReq.setUri("/api/v1/users/42");
    entrypointReq.setHeaders(epReqHeaders);

    HttpHeaders epRespHeaders = HttpHeaders.create().add(
      "Content-Type",
      "application/json"
    );

    Response entrypointResp = new Response(status);
    entrypointResp.setHeaders(epRespHeaders);

    HttpHeaders endpReqHeaders = HttpHeaders.create()
      .add("X-Forwarded-For", "10.0.0.1")
      .add("Content-Type", "application/json");

    Request endpointReq = new Request();
    endpointReq.setMethod(HttpMethod.GET);
    endpointReq.setUri("/backend/users/42");
    endpointReq.setHeaders(endpReqHeaders);

    HttpHeaders endpRespHeaders = HttpHeaders.create().add(
      "X-Backend-Trace",
      "trace-xyz"
    );

    Response endpointResp = new Response(status);
    endpointResp.setHeaders(endpRespHeaders);

    Log log = Log.builder().build();
    log.setEntrypointRequest(entrypointReq);
    log.setEntrypointResponse(entrypointResp);
    log.setEndpointRequest(endpointReq);
    log.setEndpointResponse(endpointResp);

    m.setLog(log);
    return m;
  }

  public static EndpointStatus endpointStatusTransition(boolean available) {
    EndpointStatus s = EndpointStatus.forEndpoint(
      "api-123",
      "Test API",
      "https://backend.example.com/health"
    )
      .on(System.currentTimeMillis())
      .build();
    s.setAvailable(available);
    s.setResponseTime(100);
    s.setTransition(true);
    return s;
  }

  public static EndpointStatus endpointStatusNonTransition() {
    EndpointStatus s = EndpointStatus.forEndpoint(
      "api-123",
      "api-123",
      "https://backend.example.com/health"
    )
      .on(System.currentTimeMillis())
      .build();
    s.setAvailable(true);
    s.setTransition(false);
    return s;
  }

  public static MessageMetrics messageMetrics() {
    MessageMetrics m = MessageMetrics.builder().build();
    m.setApiId("api-123");
    m.setRequestId("req-msg-001");
    m.setConnectorId("connector-kafka");
    m.setConnectorType(MessageConnectorType.ENTRYPOINT);
    m.setCount(10L);
    m.setErrorCount(2L);
    m.setGatewayLatencyMs(15L);
    return m;
  }
}
