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
package io.gravitee.reporter.gcloud.spring;

import com.google.auth.oauth2.GoogleCredentials;
import io.gravitee.reporter.gcloud.config.GCloudReporterConfiguration;
import io.gravitee.reporter.gcloud.writer.GCloudEntrySerializer;
import io.gravitee.reporter.gcloud.writer.GCloudLogWriter;
import java.io.FileInputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GCloudReporterSpringConfiguration {

  private static final Logger log = LoggerFactory.getLogger(
    GCloudReporterSpringConfiguration.class
  );

  private static final String LOGGING_SCOPE =
    "https://www.googleapis.com/auth/logging.write";

  @Bean
  public GCloudReporterConfiguration gCloudReporterConfiguration() {
    return new GCloudReporterConfiguration();
  }

  @Bean
  public GCloudLogWriter logWriter(GCloudReporterConfiguration cfg)
    throws IOException {
    GoogleCredentials credentials = loadCredentials(cfg);

    String projectId = resolveProjectId(cfg);
    log.debug(
      "GCloud reporter — project='{}' logName='{}'",
      projectId,
      cfg.getLogName()
    );

    GCloudEntrySerializer serializer = new GCloudEntrySerializer(
      projectId,
      cfg.getLogName(),
      cfg.getResourceType(),
      cfg.getResourceLabels()
    );

    return new GCloudLogWriter(
      serializer,
      credentials,
      cfg.getBatchSize(),
      cfg.getFlushIntervalSeconds()
    );
  }

  private GoogleCredentials loadCredentials(GCloudReporterConfiguration cfg)
    throws IOException {
    if (
      cfg.getCredentialsFile() != null && !cfg.getCredentialsFile().isBlank()
    ) {
      log.debug(
        "Loading GCloud credentials from file: {}",
        cfg.getCredentialsFile()
      );
      try (
        FileInputStream stream = new FileInputStream(cfg.getCredentialsFile())
      ) {
        return GoogleCredentials.fromStream(stream).createScoped(LOGGING_SCOPE);
      }
    }
    log.debug("Using Application Default Credentials for GCloud Logging");
    return GoogleCredentials.getApplicationDefault().createScoped(
      LOGGING_SCOPE
    );
  }

  private String resolveProjectId(GCloudReporterConfiguration cfg) {
    if (cfg.getProjectId() != null && !cfg.getProjectId().isBlank()) {
      return cfg.getProjectId();
    }
    String envProject = System.getenv("GOOGLE_CLOUD_PROJECT");
    if (envProject != null && !envProject.isBlank()) {
      return envProject;
    }
    throw new IllegalStateException(
      "GCloud reporter: reporters.gcloud.projectId must be set " +
        "(or GOOGLE_CLOUD_PROJECT env var)"
    );
  }
}
