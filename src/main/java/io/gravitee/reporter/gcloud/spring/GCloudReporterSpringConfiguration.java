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
    throw new IllegalStateException(
      "GCloud reporter: reporters.gcloud.projectid must be set in gravitee.yml"
    );
  }
}
