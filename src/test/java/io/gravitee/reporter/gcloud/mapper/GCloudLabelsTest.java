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
