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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GCloudLabelsTest {

  @Test
  void nonNullValueInsertsKey() {
    Map<String, String> labels = new HashMap<>();
    GCloudLabels.ifPresent("my-api", "gravitee.api_id", labels);
    assertThat(labels).containsEntry("gravitee.api_id", "my-api");
  }

  @Test
  void nullValueDoesNotInsertKey() {
    Map<String, String> labels = new HashMap<>();
    GCloudLabels.ifPresent(null, "gravitee.api_id", labels);
    assertThat(labels).doesNotContainKey("gravitee.api_id");
  }

  @Test
  void blankValueDoesNotInsertKey() {
    Map<String, String> labels = new HashMap<>();
    GCloudLabels.ifPresent("   ", "gravitee.api_id", labels);
    assertThat(labels).doesNotContainKey("gravitee.api_id");
  }
}
