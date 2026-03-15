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
package io.gravitee.reporter.gcloud.writer;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable representation of a single Cloud Logging log entry to be sent via
 * the REST API {@code entries:write} endpoint.
 *
 * <p>Fields that may be null:
 * <ul>
 *   <li>{@code trace} — only set when a trace-id is available on the request</li>
 *   <li>{@code spanId} — only set when a span-id is available</li>
 *   <li>{@code httpRequest} — only set for HTTP-metrics log entries</li>
 * </ul>
 */
public record GCloudLogEntry(
  GCloudSeverity severity,
  Instant timestamp,
  String trace,
  String spanId,
  Map<String, String> labels,
  Map<String, Object> jsonPayload,
  GCloudHttpRequest httpRequest
) {}
