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

import java.util.Map;

/**
 * Null-safe label insertion for GCL LogEntry labels.
 * Mirrors the SentryTags utility from gravitee-reporter-sentry.
 */
public final class GCloudLabels {

  private GCloudLabels() {}

  /**
   * Inserts {@code value} under {@code key} into {@code labels} only when
   * {@code value} is non-null and non-blank.
   */
  public static void ifPresent(
    String value,
    String key,
    Map<String, String> labels
  ) {
    if (value != null && !value.isBlank()) {
      labels.put(key, value);
    }
  }
}
