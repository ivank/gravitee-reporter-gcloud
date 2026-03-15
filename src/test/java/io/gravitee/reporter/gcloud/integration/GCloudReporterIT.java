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
package io.gravitee.reporter.gcloud.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.Severity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/**
 * End-to-end integration test for the {@code gravitee-reporter-gcloud} plugin.
 *
 * <p><b>What it does:</b>
 * <ol>
 *   <li>Starts a full Gravitee APIM 4.9 stack (MongoDB + Management API + Gateway) plus a
 *       go-httpbin mock backend using Testcontainers, with the gcloud reporter plugin
 *       mounted into the gateway.</li>
 *   <li>Creates two V4 HTTP proxy APIs via the Management REST API — one backed by
 *       {@code /status/200} and one by {@code /status/500}.</li>
 *   <li>Waits for the gateway to sync and serve both APIs.</li>
 *   <li>Sends HTTP requests through the gateway and asserts that the expected GCL log entries
 *       appear in Cloud Logging with the correct severity, HttpRequest fields, and trace data.</li>
 * </ol>
 *
 * <p><b>Prerequisites:</b> Docker running, {@code GOOGLE_CLOUD_PROJECT} env var (or
 * {@code local.properties}) used by the test harness to resolve the project ID, and valid
 * Application Default Credentials or {@code GOOGLE_APPLICATION_CREDENTIALS} pointing to a
 * service-account key with {@code roles/logging.logWriter} and {@code roles/logging.viewer}.
 * The gateway plugin reads the project ID from {@code reporters.gcloud.projectid}
 * (passed via {@code gravitee_reporters_gcloud_projectid} env var on the container).
 *
 * <p>Run with: {@code mvn clean verify -Pintegration-test}
 */
@Tag("integration")
class GCloudReporterIT {

  private static final Logger log = LoggerFactory.getLogger(
    GCloudReporterIT.class
  );
  private static final String LOG_NAME = "gravitee-gateway";

  private static final Network NETWORK = Network.newNetwork();
  private static final String MONGO_URI =
    "mongodb://mongodb:27017/gravitee?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=5000";

  private static MongoDBContainer mongodb;
  private static GenericContainer<?> managementApi;
  private static GenericContainer<?> gateway;
  private static GenericContainer<?> httpbin;

  private static ManagementApiHelper mgmtHelper;
  private static GCloudLoggingClient loggingClient;

  private static String successApiId;
  private static String errorApiId;

  private static HttpClient http;
  private static String gatewayBase;
  private static String gcpProjectId;

  @BeforeAll
  static void startInfrastructure() throws Exception {
    gcpProjectId = System.getProperty(
      "GOOGLE_CLOUD_PROJECT",
      System.getenv("GOOGLE_CLOUD_PROJECT")
    );
    String credentialsFile = System.getProperty(
      "GOOGLE_APPLICATION_CREDENTIALS",
      System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    );

    String pluginVersion = System.getProperty(
      "project.version",
      "1.0.0-SNAPSHOT"
    );
    Path pluginZip = Paths.get(
      "target/gravitee-reporter-gcloud-" + pluginVersion + ".zip"
    );
    assertThat(pluginZip)
      .as(
        "Plugin ZIP not found at %s — run 'mvn package -DskipTests' first",
        pluginZip.toAbsolutePath()
      )
      .exists();

    // 1. MongoDB
    mongodb = new MongoDBContainer("mongo:7.0")
      .withNetwork(NETWORK)
      .withNetworkAliases("mongodb");

    // 2. Management API
    managementApi = new GenericContainer<>(
      "graviteeio/apim-management-api:4.9.13"
    )
      .withNetwork(NETWORK)
      .withNetworkAliases("management-api")
      .withExposedPorts(8083, 18083)
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_analytics_type", "none")
      .withEnv(
        "gravitee_plugins_path_0",
        "/opt/graviteeio-management-api/plugins"
      )
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18083")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      .dependsOn(mongodb)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.management-api"))
      )
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18083).forStatusCode(200)
      );

    // 3. go-httpbin mock backend
    httpbin = new GenericContainer<>("mccutchen/go-httpbin")
      .withNetwork(NETWORK)
      .withNetworkAliases("httpbin")
      .withExposedPorts(8080)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.httpbin"))
      )
      .waitingFor(Wait.forHttp("/get").forPort(8080).forStatusCode(200));

    // 4. Gateway with the gcloud reporter plugin
    GenericContainer<?> gw = new GenericContainer<>(
      "graviteeio/apim-gateway:4.9.13"
    )
      .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
      .withNetwork(NETWORK)
      .withNetworkAliases("gateway")
      .withExposedPorts(8082, 18082)
      .withCopyFileToContainer(
        MountableFile.forHostPath(pluginZip.toAbsolutePath().toString()),
        "/opt/graviteeio-gateway/plugins-ext/gravitee-reporter-gcloud.zip"
      )
      .withEnv("gravitee_management_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_ratelimit_mongodb_uri", MONGO_URI)
      .withEnv("gravitee_reporters_elasticsearch_enabled", "false")
      .withEnv("gravitee_plugins_path_0", "/opt/graviteeio-gateway/plugins")
      .withEnv("gravitee_plugins_path_1", "/opt/graviteeio-gateway/plugins-ext")
      .withEnv("gravitee_services_core_http_enabled", "true")
      .withEnv("gravitee_services_core_http_port", "18082")
      .withEnv("gravitee_services_core_http_host", "0.0.0.0")
      .withEnv("gravitee_services_core_http_authentication_type", "none")
      // GCloud reporter configuration
      .withEnv("gravitee_reporters_gcloud_enabled", "true")
      .withEnv("gravitee_reporters_gcloud_projectid", gcpProjectId)
      .withEnv("gravitee_reporters_gcloud_logname", LOG_NAME)
      .withEnv("gravitee_reporters_gcloud_resource_type", "global")
      .withEnv("gravitee_reporters_gcloud_captureerrors", "true")
      .withEnv("gravitee_reporters_gcloud_reporthealthchecks", "false")
      .withEnv("gravitee_reporters_gcloud_reportlogs", "true")
      .withEnv("gravitee_reporters_gcloud_reportmessagemetrics", "false")
      .dependsOn(managementApi)
      .withLogConsumer(
        new Slf4jLogConsumer(LoggerFactory.getLogger("tc.gateway"))
      )
      .waitingFor(
        Wait.forHttp("/_node/health").forPort(18082).forStatusCode(200)
      );

    log.info("GCloud IT — project: {}", gcpProjectId);

    // Mount credentials file if provided; otherwise ADC inside container uses metadata server.
    // For local dev with ADC, mount the well-known ADC file.
    if (credentialsFile != null && !credentialsFile.isBlank()) {
      log.info(
        "GCloud IT — credentials: service-account key file at {}",
        credentialsFile
      );
      gw = gw
        .withCopyFileToContainer(
          MountableFile.forHostPath(credentialsFile),
          "/opt/sa-key.json"
        )
        .withEnv("GOOGLE_APPLICATION_CREDENTIALS", "/opt/sa-key.json");
    } else {
      // Try to mount the ADC credentials file from the standard location
      java.nio.file.Path adcPath = Paths.get(
        System.getProperty("user.home"),
        ".config",
        "gcloud",
        "application_default_credentials.json"
      );
      if (adcPath.toFile().exists()) {
        log.info(
          "GCloud IT — credentials: Application Default Credentials from {}",
          adcPath
        );
        gw = gw.withCopyFileToContainer(
          MountableFile.forHostPath(adcPath.toString()),
          "/root/.config/gcloud/application_default_credentials.json"
        );
      } else {
        log.info(
          "GCloud IT — credentials: none found locally, relying on GCE metadata server"
        );
      }
    }
    gateway = gw;

    Startables.deepStart(gateway, httpbin).join();

    gatewayBase = "http://localhost:" + gateway.getMappedPort(8082);
    String mgmtBase = "http://localhost:" + managementApi.getMappedPort(8083);

    http = HttpClient.newHttpClient();
    mgmtHelper = new ManagementApiHelper(mgmtBase);
    loggingClient = new GCloudLoggingClient(gcpProjectId);

    successApiId = mgmtHelper.createAndDeployApi(
      "GCloud IT Success",
      "/gcloud-it-ok",
      "http://httpbin:8080/status/200",
      true // enable analytics logging to capture headers and endpoint request
    );
    errorApiId = mgmtHelper.createAndDeployApi(
      "GCloud IT Error",
      "/gcloud-it-err",
      "http://httpbin:8080/status/500"
    );

    // Wait for gateway to sync both APIs
    await("gateway to serve success API")
      .atMost(Duration.ofSeconds(90))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/gcloud-it-ok"))
                .build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );

    await("gateway to serve error API")
      .atMost(Duration.ofSeconds(30))
      .pollInterval(Duration.ofSeconds(3))
      .until(
        () ->
          http
            .send(
              HttpRequest.newBuilder()
                .uri(URI.create(gatewayBase + "/gcloud-it-err"))
                .build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode() !=
          404
      );
  }

  @AfterAll
  static void stopInfrastructure() {
    if (loggingClient != null) loggingClient.close();
    Stream.of(gateway, httpbin, managementApi, mongodb)
      .filter(Objects::nonNull)
      .forEach(GenericContainer::stop);
    NETWORK.close();
  }

  @Test
  void shouldWriteLogEntryForSuccessfulRequest() throws Exception {
    var response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/gcloud-it-ok"))
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(200);

    String filter =
      loggingClient.logNameFilter(LOG_NAME) +
      " AND labels.\"gravitee.api_id\"=\"" +
      successApiId +
      "\"" +
      " AND httpRequest.status=200";

    List<LogEntry> entries = loggingClient.pollForEntries(
      filter,
      Duration.ofSeconds(90)
    );

    assertThat(entries).isNotEmpty();
    LogEntry entry = entries.get(0);
    assertThat(entry.getSeverity()).isEqualTo(Severity.INFO);
    assertThat(entry.getHttpRequest()).isNotNull();
    assertThat(entry.getHttpRequest().getRequestMethod()).isEqualTo(
      com.google.cloud.logging.HttpRequest.RequestMethod.GET
    );
    assertThat(entry.getTrace()).isNotNull().isNotBlank();
  }

  @Test
  void shouldWriteErrorSeverityFor5xxResponse() throws Exception {
    var response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/gcloud-it-err"))
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(500);

    String filter =
      loggingClient.logNameFilter(LOG_NAME) +
      " AND labels.\"gravitee.api_id\"=\"" +
      errorApiId +
      "\"" +
      " AND httpRequest.status=500";

    List<LogEntry> entries = loggingClient.pollForEntries(
      filter,
      Duration.ofSeconds(90)
    );

    assertThat(entries).isNotEmpty();
    assertThat(entries.get(0).getSeverity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void shouldPopulateHttpRequestFields() throws Exception {
    var response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/gcloud-it-ok"))
        .header("User-Agent", "gravitee-it-test/1.0")
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(200);

    String filter =
      loggingClient.logNameFilter(LOG_NAME) +
      " AND labels.\"gravitee.api_id\"=\"" +
      successApiId +
      "\"" +
      " AND httpRequest.userAgent=\"gravitee-it-test/1.0\"";

    List<LogEntry> entries = loggingClient.pollForEntries(
      filter,
      Duration.ofSeconds(90)
    );

    assertThat(entries).isNotEmpty();
    com.google.cloud.logging.HttpRequest req = entries.get(0).getHttpRequest();
    assertThat(req.getUserAgent()).isEqualTo("gravitee-it-test/1.0");
    assertThat(req.getLatencyDuration()).isNotNull().isPositive();
    assertThat(req.getRemoteIp()).isNotNull().isNotBlank();
  }

  @Test
  void shouldSetTraceAndSpanFields() throws Exception {
    var response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/gcloud-it-ok"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertThat(response.statusCode()).isEqualTo(200);

    // The gateway echoes X-Gravitee-Transaction-Id in the response headers
    String transactionId = response
      .headers()
      .firstValue("X-Gravitee-Transaction-Id")
      .orElse(null);
    assertThat(transactionId)
      .as("X-Gravitee-Transaction-Id response header")
      .isNotNull();

    String filter =
      loggingClient.logNameFilter(LOG_NAME) +
      " AND labels.\"gravitee.api_id\"=\"" +
      successApiId +
      "\"" +
      " AND trace:\"" +
      transactionId +
      "\"";

    List<LogEntry> entries = loggingClient.pollForEntries(
      filter,
      Duration.ofSeconds(90)
    );

    assertThat(entries).isNotEmpty();
    LogEntry entry = entries.get(0);
    assertThat(entry.getTrace()).contains(transactionId);
    assertThat(entry.getSpanId()).isNotNull().isNotBlank();
  }

  /**
   * Verifies that when API logging is enabled (analytics.logging configured), the jsonPayload
   * contains entrypoint request headers and a populated endpoint.request section with the
   * actual method, URI, and headers forwarded to the backend.
   */
  @Test
  @SuppressWarnings("unchecked")
  void shouldPopulateEndpointRequestAndHeadersWhenLoggingEnabled()
    throws Exception {
    var response = http.send(
      HttpRequest.newBuilder()
        .uri(URI.create(gatewayBase + "/gcloud-it-ok"))
        .header("X-Custom-Header", "integration-test")
        .build(),
      HttpResponse.BodyHandlers.discarding()
    );
    assertThat(response.statusCode()).isEqualTo(200);

    String filter =
      loggingClient.logNameFilter(LOG_NAME) +
      " AND labels.\"gravitee.api_id\"=\"" +
      successApiId +
      "\"" +
      " AND httpRequest.status=200";

    List<LogEntry> entries = loggingClient.pollForEntries(
      filter,
      Duration.ofSeconds(90)
    );

    assertThat(entries).isNotEmpty();
    LogEntry entry = entries.get(0);

    Payload<?> rawPayload = entry.getPayload();
    assertThat(rawPayload).isInstanceOf(Payload.JsonPayload.class);
    Map<String, Object> data =
      ((Payload.JsonPayload) rawPayload).getDataAsMap();

    // entrypoint.request must have headers when API logging is enabled
    Map<String, Object> entrypoint = (Map<String, Object>) data.get(
      "entrypoint"
    );
    assertThat(entrypoint).isNotNull();
    Map<String, Object> epReq = (Map<String, Object>) entrypoint.get("request");
    assertThat(epReq).isNotNull();
    assertThat(epReq).containsKey("headers");

    // endpoint.request must be present with method, URI, and headers
    Map<String, Object> endpoint = (Map<String, Object>) data.get("endpoint");
    assertThat(endpoint).isNotNull();
    Map<String, Object> endpReq = (Map<String, Object>) endpoint.get("request");
    assertThat(endpReq)
      .as("endpoint.request must be present when API logging is enabled")
      .isNotNull();
    assertThat(endpReq).containsKey("method");
    assertThat(endpReq).containsKey("uri");
    assertThat(endpReq).containsKey("headers");
  }
}
