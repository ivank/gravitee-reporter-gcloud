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

/**
 * HTTP request metadata for a Cloud Logging log entry.
 *
 * <p>Fields with zero/null values are omitted from the serialised JSON by
 * {@link GCloudEntrySerializer}:
 * <ul>
 *   <li>{@code requestSize} — omitted when 0</li>
 *   <li>{@code responseSize} — omitted when 0</li>
 *   <li>{@code latencyMs} — omitted when 0</li>
 *   <li>{@code userAgent}, {@code remoteIp}, {@code serverIp} — omitted when null/blank</li>
 * </ul>
 */
public record GCloudHttpRequest(
  String requestMethod,
  String requestUrl,
  long requestSize,
  int status,
  long responseSize,
  String userAgent,
  String remoteIp,
  String serverIp,
  long latencyMs
) {}
