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

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/**
 * Drives the Gravitee APIM Management REST API (v2) to create and fully deploy a V4 HTTP proxy
 * API during integration test setup. Executes five sequential steps:
 * create → create plan → publish plan → start → deploy.
 */
class ManagementApiHelper {

  private static final Logger log = LoggerFactory.getLogger(
    ManagementApiHelper.class
  );
  private static final MediaType JSON = MediaType.get(
    "application/json; charset=utf-8"
  );
  private static final String ORG_ENV =
    "management/v2/organizations/DEFAULT/environments/DEFAULT/";
  private static final RequestBody EMPTY = RequestBody.create(JSON, "{}");

  private final GraviteeManagementApi api;

  ManagementApiHelper(String baseUrl) {
    String credentials = Base64.getEncoder().encodeToString(
      "admin:admin".getBytes(StandardCharsets.UTF_8)
    );

    OkHttpClient client = new OkHttpClient.Builder()
      .addInterceptor(chain ->
        chain.proceed(
          chain
            .request()
            .newBuilder()
            .header("Authorization", "Basic " + credentials)
            .build()
        )
      )
      .build();

    String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    this.api = new Retrofit.Builder()
      .baseUrl(base + ORG_ENV)
      .client(client)
      .addConverterFactory(JacksonConverterFactory.create())
      .build()
      .create(GraviteeManagementApi.class);
  }

  /**
   * Creates a V4 HTTP proxy API and fully deploys it. Returns the API UUID.
   *
   * @param name        human-readable API name (must be unique across test runs)
   * @param contextPath gateway context path, e.g. {@code /gcloud-it-ok}
   * @param backendUrl  upstream backend, e.g. {@code http://httpbin:8080/status/200}
   */
  String createAndDeployApi(String name, String contextPath, String backendUrl)
    throws Exception {
    return createAndDeployApi(name, contextPath, backendUrl, false);
  }

  /**
   * Creates a V4 HTTP proxy API and fully deploys it, optionally enabling request/response logging.
   * When {@code loggingEnabled} is {@code true} the API analytics block is configured to capture
   * entrypoint and endpoint headers for both request and response phases.
   *
   * @param name           human-readable API name (must be unique across test runs)
   * @param contextPath    gateway context path, e.g. {@code /gcloud-it-ok}
   * @param backendUrl     upstream backend, e.g. {@code http://httpbin:8080/status/200}
   * @param loggingEnabled whether to enable header/payload logging in the analytics block
   */
  String createAndDeployApi(
    String name,
    String contextPath,
    String backendUrl,
    boolean loggingEnabled
  ) throws Exception {
    String analyticsBlock = loggingEnabled
      ? """
      "analytics": {
        "enabled": true,
        "logging": {
          "mode":    {"entrypoint": true, "endpoint": true},
          "phase":   {"request": true,    "response": true},
          "content": {"headers": true,    "payload": false}
        }
      },
      """
      : "";

    // Step 1: create API
    String apiBody = """
      {
        "name": "%s",
        "apiVersion": "1.0.0",
        "definitionVersion": "V4",
        "type": "PROXY",
        "description": "Integration test API for gravitee-reporter-gcloud",
        %s
        "listeners": [{
          "type": "HTTP",
          "paths": [{"path": "%s"}],
          "entrypoints": [{"type": "http-proxy"}]
        }],
        "endpointGroups": [{
          "name": "default",
          "type": "http-proxy",
          "endpoints": [{
            "name": "main",
            "type": "http-proxy",
            "weight": 1,
            "inheritConfiguration": false,
            "configuration": {"target": "%s"}
          }]
        }]
      }
      """.formatted(name, analyticsBlock, contextPath, backendUrl);

    Response<JsonNode> createResp = api.createApi(body(apiBody)).execute();
    assertOk("createApi", createResp);
    String apiId = createResp.body().get("id").asText();
    log.info("Created API id={} name='{}' path={}", apiId, name, contextPath);

    // Step 2: create keyless plan
    String planBody = """
      {
        "name": "Default Plan",
        "definitionVersion": "V4",
        "security": {"type": "KEY_LESS"}
      }
      """;
    Response<JsonNode> planResp = api
      .createPlan(apiId, body(planBody))
      .execute();
    assertOk("createPlan", planResp);
    String planId = planResp.body().get("id").asText();
    log.info("Created plan id={}", planId);

    // Step 3: publish plan
    Response<Void> publishResp = api
      .publishPlan(apiId, planId, EMPTY)
      .execute();
    assertOk("publishPlan", publishResp);

    // Step 4: start API
    Response<Void> startResp = api.startApi(apiId, EMPTY).execute();
    assertOk("startApi", startResp);

    // Step 5: deploy API
    Response<Void> deployResp = api.deployApi(apiId, EMPTY).execute();
    assertOk("deployApi", deployResp);

    log.info("API '{}' deployed successfully (id={})", name, apiId);
    return apiId;
  }

  private static RequestBody body(String json) {
    return RequestBody.create(JSON, json);
  }

  private static void assertOk(String step, Response<?> resp) {
    if (!resp.isSuccessful()) {
      String body = "";
      try {
        body = resp.errorBody() != null ? resp.errorBody().string() : "";
      } catch (Exception ignored) {}
      throw new IllegalStateException(
        "Management API step '%s' failed: HTTP %d — %s".formatted(
          step,
          resp.code(),
          body
        )
      );
    }
  }
}
