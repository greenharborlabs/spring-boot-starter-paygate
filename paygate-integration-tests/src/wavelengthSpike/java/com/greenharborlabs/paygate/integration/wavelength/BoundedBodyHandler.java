package com.greenharborlabs.paygate.integration.wavelength;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Buffers a Wavelength response only while it remains within the fixed spike safety bounds. */
final class BoundedBodyHandler implements HttpResponse.BodyHandler<byte[]> {

  static final int MAX_BODY_BYTES = 256 * 1024;
  static final String BODY_TOO_LARGE_MESSAGE = "Wavelength response body exceeds the 256 KiB limit";
  static final String BODY_TIMEOUT_MESSAGE = "Wavelength response body deadline exceeded";
  static final String BODY_READ_FAILURE_MESSAGE = "Wavelength response body could not be read";

  private static final ScheduledExecutorService DEADLINE_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("wavelength-body-deadline").factory());

  private final Duration bodyDeadline;

  BoundedBodyHandler(Duration bodyDeadline) {
    this.bodyDeadline = Objects.requireNonNull(bodyDeadline, "bodyDeadline");
    if (bodyDeadline.isZero() || bodyDeadline.isNegative()) {
      throw new IllegalArgumentException("bodyDeadline must be positive");
    }
  }

  @Override
  public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
    Objects.requireNonNull(responseInfo, "responseInfo");
    if (declaresOversizedBody(responseInfo.headers())) {
      return new RejectedBodySubscriber(new IOException(BODY_TOO_LARGE_MESSAGE));
    }
    return new BoundedBodySubscriber(bodyDeadline);
  }

  private static boolean declaresOversizedBody(HttpHeaders headers) {
    for (var value : headers.allValues("Content-Length")) {
      try {
        if (Long.parseLong(value.trim()) > MAX_BODY_BYTES) {
          return true;
        }
      } catch (NumberFormatException ignored) {
        // The observed byte count remains authoritative for malformed declarations.
      }
    }
    return false;
  }

  private static final class RejectedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final CompletableFuture<byte[]> body = new CompletableFuture<>();

    RejectedBodySubscriber(IOException failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      Objects.requireNonNull(subscription, "subscription").cancel();
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      // A rejected subscriber never requests body bytes.
    }

    @Override
    public void onError(Throwable throwable) {
      // The fixed rejection reason remains authoritative and secret-free.
    }

    @Override
    public void onComplete() {
      // The fixed rejection reason remains authoritative and secret-free.
    }
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final byte[] buffered = new byte[MAX_BODY_BYTES];
    private ScheduledFuture<?> deadlineTask;

    private Flow.Subscription subscription;
    private int observedBytes;
    private boolean terminated;

    BoundedBodySubscriber(Duration bodyDeadline) {
      deadlineTask =
          DEADLINE_EXECUTOR.schedule(
              this::onDeadline, bodyDeadline.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription candidate) {
      Objects.requireNonNull(candidate, "subscription");
      if (subscription != null || terminated) {
        candidate.cancel();
        return;
      }
      subscription = candidate;
      candidate.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      Flow.Subscription current;
      synchronized (this) {
        if (terminated) {
          return;
        }

        long incomingBytes = 0;
        for (var buffer : buffers) {
          incomingBytes += buffer.remaining();
          if (incomingBytes > MAX_BODY_BYTES - observedBytes) {
            failLocked(new IOException(BODY_TOO_LARGE_MESSAGE));
            return;
          }
        }

        for (var buffer : buffers) {
          int bytes = buffer.remaining();
          buffer.get(buffered, observedBytes, bytes);
          observedBytes += bytes;
        }
        current = subscription;
      }

      if (current != null) {
        current.request(1);
      }
    }

    @Override
    public synchronized void onError(Throwable throwable) {
      failLocked(new IOException(BODY_READ_FAILURE_MESSAGE));
    }

    @Override
    public synchronized void onComplete() {
      if (terminated) {
        return;
      }
      terminated = true;
      cancelDeadline();
      var result = Arrays.copyOf(buffered, observedBytes);
      Arrays.fill(buffered, (byte) 0);
      body.complete(result);
    }

    private synchronized void onDeadline() {
      failLocked(new HttpTimeoutException(BODY_TIMEOUT_MESSAGE));
    }

    private void failLocked(IOException failure) {
      if (terminated) {
        return;
      }
      terminated = true;
      cancelDeadline();
      Arrays.fill(buffered, (byte) 0);
      body.completeExceptionally(failure);
      if (subscription != null) {
        subscription.cancel();
      }
    }

    private void cancelDeadline() {
      if (deadlineTask != null) {
        deadlineTask.cancel(false);
      }
    }
  }
}
