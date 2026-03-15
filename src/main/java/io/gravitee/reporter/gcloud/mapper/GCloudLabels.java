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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Null-safe label helpers for GCL LogEntry labels.
 */
public final class GCloudLabels {

  private GCloudLabels() {}

  /**
   * Builds a {@code Map<String,String>} from alternating key/value pairs,
   * skipping any pair whose value is null or blank.
   *
   * <pre>
   * var labels = GCloudLabels.of(
   *     "gravitee.api_id",   metrics.getApiId(),
   *     "gravitee.api_name", metrics.getApiName()
   * );
   * </pre>
   */
  public static Map<String, String> of(String... kvPairs) {
    if (kvPairs.length % 2 != 0) {
      throw new IllegalArgumentException("kvPairs length must be even");
    }
    var labels = new LinkedHashMap<String, String>();
    IntStream.iterate(0, i -> i < kvPairs.length, i -> i + 2)
      .filter(i -> kvPairs[i + 1] != null && !kvPairs[i + 1].isBlank())
      .forEach(i -> labels.put(kvPairs[i], kvPairs[i + 1]));
    return labels;
  }

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
