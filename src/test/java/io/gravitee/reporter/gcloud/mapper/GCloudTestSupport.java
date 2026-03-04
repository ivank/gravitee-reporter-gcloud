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

import io.gravitee.common.http.HttpMethod;
import io.gravitee.reporter.api.health.EndpointStatus;
import io.gravitee.reporter.api.v4.common.MessageConnectorType;
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
    MessageMetrics m = new MessageMetrics();
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
