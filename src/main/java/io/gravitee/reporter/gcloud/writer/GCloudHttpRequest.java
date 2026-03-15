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
