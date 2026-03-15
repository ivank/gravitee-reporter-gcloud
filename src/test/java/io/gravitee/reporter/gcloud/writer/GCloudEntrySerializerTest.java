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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GCloudEntrySerializerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private GCloudEntrySerializer serializer;

  @BeforeEach
  void setUp() {
    serializer = new GCloudEntrySerializer(
      "my-project",
      "gravitee-gateway",
      "global",
      Map.of("env", "prod")
    );
  }

  private GCloudLogEntry entry200() {
    return new GCloudLogEntry(
      GCloudSeverity.INFO,
      Instant.parse("2024-01-15T12:00:00Z"),
      "projects/my-project/traces/txn-abc",
      "req-123",
      Map.of("gravitee.api_id", "api-001"),
      Map.of("status", 200, "method", "GET"),
      new GCloudHttpRequest(
        "GET",
        "http://gw.example.com/api/v1/users",
        128L,
        200,
        512L,
        "curl/7.0",
        "10.0.0.1",
        "10.0.0.100",
        42L
      )
    );
  }

  @Test
  void outputIsValidJson() throws Exception {
    String json = serializer.serialize(List.of(entry200()));
    assertThat(JSON.readTree(json)).isNotNull();
  }

  @Test
  void logNameContainsProjectAndLogName() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    assertThat(root.get("logName").asText()).isEqualTo(
      "projects/my-project/logs/gravitee-gateway"
    );
  }

  @Test
  void resourceTypeIsPresent() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    assertThat(root.get("resource").get("type").asText()).isEqualTo("global");
  }

  @Test
  void resourceLabelsArePresent() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    assertThat(
      root.get("resource").get("labels").get("env").asText()
    ).isEqualTo("prod");
  }

  @Test
  void severityIsSet() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    assertThat(root.get("entries").get(0).get("severity").asText()).isEqualTo(
      "INFO"
    );
  }

  @Test
  void timestampIsRfc3339() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    String ts = root.get("entries").get(0).get("timestamp").asText();
    assertThat(ts).isEqualTo("2024-01-15T12:00:00Z");
  }

  @Test
  void traceAndSpanIdAreSet() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    JsonNode e = root.get("entries").get(0);
    assertThat(e.get("trace").asText()).isEqualTo(
      "projects/my-project/traces/txn-abc"
    );
    assertThat(e.get("spanId").asText()).isEqualTo("req-123");
  }

  @Test
  void httpRequestFieldsArePresent() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    JsonNode req = root.get("entries").get(0).get("httpRequest");
    assertThat(req.get("requestMethod").asText()).isEqualTo("GET");
    assertThat(req.get("status").asInt()).isEqualTo(200);
    assertThat(req.get("userAgent").asText()).isEqualTo("curl/7.0");
    assertThat(req.get("remoteIp").asText()).isEqualTo("10.0.0.1");
    assertThat(req.get("requestSize").asText()).isEqualTo("128");
    assertThat(req.get("responseSize").asText()).isEqualTo("512");
  }

  @Test
  void latencyIsProtobufDurationString() throws Exception {
    JsonNode root = JSON.readTree(serializer.serialize(List.of(entry200())));
    String latency = root
      .get("entries")
      .get(0)
      .get("httpRequest")
      .get("latency")
      .asText();
    assertThat(latency).isEqualTo("0.042000000s");
  }

  @Test
  void toProtoDurationFormatsCorrectly() {
    assertThat(GCloudEntrySerializer.toProtoDuration(42)).isEqualTo(
      "0.042000000s"
    );
    assertThat(GCloudEntrySerializer.toProtoDuration(1042)).isEqualTo(
      "1.042000000s"
    );
    assertThat(GCloudEntrySerializer.toProtoDuration(0)).isEqualTo(
      "0.000000000s"
    );
  }

  @Test
  void nullableFieldsAreOmittedWhenAbsent() throws Exception {
    GCloudLogEntry minimal = new GCloudLogEntry(
      GCloudSeverity.DEBUG,
      Instant.parse("2024-01-15T12:00:00Z"),
      null,
      null,
      Map.of(),
      Map.of("key", "val"),
      null
    );
    JsonNode root = JSON.readTree(serializer.serialize(List.of(minimal)));
    JsonNode e = root.get("entries").get(0);
    assertThat(e.has("trace")).isFalse();
    assertThat(e.has("spanId")).isFalse();
    assertThat(e.has("httpRequest")).isFalse();
    assertThat(e.has("labels")).isFalse();
  }

  @Test
  void specialCharactersInStringsAreEscaped() throws Exception {
    GCloudLogEntry entry = new GCloudLogEntry(
      GCloudSeverity.INFO,
      Instant.now(),
      null,
      null,
      Map.of(),
      Map.of("msg", "say \"hello\"\nworld"),
      null
    );
    String json = serializer.serialize(List.of(entry));
    assertThat(JSON.readTree(json)).isNotNull(); // valid JSON
    assertThat(json).contains("\\\"hello\\\"");
    assertThat(json).contains("\\n");
  }
}
