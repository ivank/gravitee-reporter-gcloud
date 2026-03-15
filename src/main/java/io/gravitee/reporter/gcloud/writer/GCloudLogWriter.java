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

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends {@link GCloudLogEntry} batches to Cloud Logging REST API v2 using
 * {@link java.net.http.HttpClient} (Java 21 built-in).
 *
 * <p>Entries are buffered in an {@link ArrayBlockingQueue} and flushed either when the
 * batch-size threshold is reached or on a periodic timer. On HTTP 429/500/503 responses
 * the write is retried up to 3 times with exponential back-off starting at 500 ms.
 */
public class GCloudLogWriter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(
    GCloudLogWriter.class
  );

  static final String DEFAULT_LOGGING_URL =
    "https://logging.googleapis.com/v2/entries:write";
  private static final int MAX_RETRIES = 3;
  private static final long BASE_BACKOFF_MS = 500;

  private final GCloudEntrySerializer serializer;
  private final GoogleCredentials credentials;
  private final HttpClient httpClient;
  private final ArrayBlockingQueue<GCloudLogEntry> queue;
  private final int batchSize;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean running = new AtomicBoolean(true);
  final String loggingUrl;

  public GCloudLogWriter(
    GCloudEntrySerializer serializer,
    GoogleCredentials credentials,
    int batchSize,
    int flushIntervalSeconds
  ) {
    this(
      serializer,
      credentials,
      batchSize,
      flushIntervalSeconds,
      DEFAULT_LOGGING_URL
    );
  }

  GCloudLogWriter(
    GCloudEntrySerializer serializer,
    GoogleCredentials credentials,
    int batchSize,
    int flushIntervalSeconds,
    String loggingUrl
  ) {
    this.serializer = serializer;
    this.credentials = credentials;
    this.batchSize = batchSize;
    this.loggingUrl = loggingUrl;
    this.queue = new ArrayBlockingQueue<>(batchSize * 10);
    this.httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .executor(Executors.newVirtualThreadPerTaskExecutor())
      .build();
    this.scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofVirtual().name("gcloud-log-flusher").factory()
    );
    scheduler.scheduleAtFixedRate(
      this::flush,
      flushIntervalSeconds,
      flushIntervalSeconds,
      TimeUnit.SECONDS
    );
  }

  /**
   * Adds an entry to the queue. If the queue is full the entry is dropped with a warning.
   * If the queue reaches the batch-size threshold, triggers an immediate flush.
   */
  public void enqueue(GCloudLogEntry entry) {
    if (entry == null) return;
    if (!queue.offer(entry)) {
      log.warn("GCloud log queue full — dropping entry");
      return;
    }
    if (queue.size() >= batchSize) {
      scheduler.execute(this::flush);
    }
  }

  /** Drains the queue and sends all pending entries to Cloud Logging. */
  public void flush() {
    if (queue.isEmpty()) return;
    List<GCloudLogEntry> batch = new ArrayList<>(batchSize);
    queue.drainTo(batch, batchSize);
    if (batch.isEmpty()) return;
    sendWithRetry(batch, 0);
  }

  private void sendWithRetry(List<GCloudLogEntry> batch, int attempt) {
    try {
      String body = serializer.serialize(batch);
      String token = getAccessToken();
      HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(loggingUrl))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(Duration.ofSeconds(30))
        .build();

      HttpResponse<String> response = httpClient.send(
        request,
        HttpResponse.BodyHandlers.ofString()
      );
      int status = response.statusCode();

      if (status == 200) {
        log.debug("Sent {} log entries to Cloud Logging", batch.size());
      } else if (
        (status == 429 || status == 500 || status == 503) &&
        attempt < MAX_RETRIES
      ) {
        long backoff = BASE_BACKOFF_MS * (1L << attempt);
        log.warn(
          "Cloud Logging returned {} — retry {}/{} in {}ms",
          status,
          attempt + 1,
          MAX_RETRIES,
          backoff
        );
        Thread.sleep(backoff);
        sendWithRetry(batch, attempt + 1);
      } else {
        log.error(
          "Cloud Logging write failed (status={}) — dropping {} entries: {}",
          status,
          batch.size(),
          response.body()
        );
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn(
        "Interrupted while sending log entries — dropping {} entries",
        batch.size()
      );
    } catch (Exception e) {
      log.error(
        "Exception sending log entries to Cloud Logging — dropping {} entries",
        batch.size(),
        e
      );
    }
  }

  private String getAccessToken() throws IOException {
    credentials.refreshIfExpired();
    return credentials.getAccessToken().getTokenValue();
  }

  @Override
  public void close() {
    running.set(false);
    scheduler.shutdown();
    try {
      flush();
      scheduler.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
