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
package io.gravitee.reporter.gcloud.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link GCloudLogWriter} using an in-process HTTP server
 * (from {@code com.sun.net.httpserver}) to verify batching and HTTP dispatch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GCloudLogWriterTest {

  private static final int BATCH_SIZE = 5;

  @Mock
  private GoogleCredentials credentials;

  private HttpServer server;
  private GCloudLogWriter writer;
  private List<String> receivedBodies;
  private volatile int serverResponseCode;

  @BeforeEach
  void setUp() throws Exception {
    receivedBodies = new CopyOnWriteArrayList<>();
    serverResponseCode = 200;

    server = HttpServer.create(new InetSocketAddress(0), 10);
    server.createContext("/v2/entries:write", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      receivedBodies.add(new String(body));
      byte[] resp = "{}".getBytes();
      exchange.sendResponseHeaders(serverResponseCode, resp.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(resp);
      }
    });
    server.start();

    AccessToken dummyToken = new AccessToken(
      "test-token",
      Date.from(Instant.now().plusSeconds(3600))
    );
    when(credentials.getAccessToken()).thenReturn(dummyToken);

    GCloudEntrySerializer serializer = new GCloudEntrySerializer(
      "test-project",
      "gravitee-gateway",
      "global",
      Map.of()
    );

    String url =
      "http://localhost:" + server.getAddress().getPort() + "/v2/entries:write";
    writer = new GCloudLogWriter(
      serializer,
      credentials,
      BATCH_SIZE,
      60, // long interval — we control flush manually
      url
    );
  }

  @AfterEach
  void tearDown() throws Exception {
    writer.close();
    server.stop(0);
  }

  private GCloudLogEntry makeEntry() {
    return new GCloudLogEntry(
      GCloudSeverity.INFO,
      Instant.now(),
      null,
      null,
      Map.of(),
      Map.of("status", 200),
      null
    );
  }

  @Test
  void enqueueAndFlushSendsRequest() throws Exception {
    writer.enqueue(makeEntry());
    writer.flush();

    assertThat(receivedBodies).hasSize(1);
    assertThat(receivedBodies.get(0)).contains("gravitee-gateway");
  }

  @Test
  void batchContainsAllEnqueuedEntries() throws Exception {
    for (int i = 0; i < 3; i++) writer.enqueue(makeEntry());
    writer.flush();

    assertThat(receivedBodies).hasSize(1);
    // Each entry has a "severity" field: count occurrences
    long severityCount = receivedBodies
      .get(0)
      .chars()
      .filter(c -> c == '{')
      .count();
    assertThat(severityCount).isGreaterThanOrEqualTo(3);
  }

  @Test
  void nullEntryIsIgnored() throws Exception {
    writer.enqueue(null);
    writer.flush();

    assertThat(receivedBodies).isEmpty();
  }

  @Test
  void batchThresholdTriggersSend() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    server.removeContext("/v2/entries:write");
    server.createContext("/v2/entries:write", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      receivedBodies.add(new String(body));
      byte[] resp = "{}".getBytes();
      exchange.sendResponseHeaders(200, resp.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(resp);
      }
      latch.countDown();
    });

    // Enqueue exactly batchSize entries to trigger auto-flush
    for (int i = 0; i < BATCH_SIZE; i++) writer.enqueue(makeEntry());

    // Wait up to 5s for the auto-flush to fire
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedBodies).isNotEmpty();
  }

  @Test
  void authTokenIsIncludedInRequest() throws Exception {
    List<String> authHeaders = new CopyOnWriteArrayList<>();
    server.removeContext("/v2/entries:write");
    server.createContext("/v2/entries:write", exchange -> {
      authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] resp = "{}".getBytes();
      exchange.sendResponseHeaders(200, resp.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(resp);
      }
    });

    writer.enqueue(makeEntry());
    writer.flush();

    assertThat(authHeaders).hasSize(1);
    assertThat(authHeaders.get(0)).isEqualTo("Bearer test-token");
  }

  @Test
  void flushOnEmptyQueueDoesNothing() throws Exception {
    writer.flush();
    assertThat(receivedBodies).isEmpty();
  }
}
