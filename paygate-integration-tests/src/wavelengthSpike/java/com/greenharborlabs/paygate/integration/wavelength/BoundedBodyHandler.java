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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Buffers a Wavelength response only while it remains within the fixed spike safety bounds. */
final class BoundedBodyHandler implements HttpResponse.BodyHandler<byte[]> {

  static final int MAX_BODY_BYTES = 256 * 1024;
  static final String BODY_TOO_LARGE_MESSAGE = "Wavelength response body exceeds the 256 KiB limit";
  static final String BODY_TIMEOUT_MESSAGE = "Wavelength response body deadline exceeded";
  static final String BODY_READ_FAILURE_MESSAGE = "Wavelength response body could not be read";

  private static final ScheduledExecutorService DEADLINE_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("wavelength-body-deadline").factory());
  private static final DeadlineScheduler SYSTEM_DEADLINE_SCHEDULER =
      (delay, task) -> DEADLINE_EXECUTOR.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);

  private final Duration bodyDeadline;
  private final LongSupplier nanoTime;
  private final DeadlineScheduler deadlineScheduler;

  BoundedBodyHandler(Duration bodyDeadline) {
    this(bodyDeadline, System::nanoTime, SYSTEM_DEADLINE_SCHEDULER);
  }

  BoundedBodyHandler(
      Duration bodyDeadline, LongSupplier nanoTime, DeadlineScheduler deadlineScheduler) {
    this.bodyDeadline = Objects.requireNonNull(bodyDeadline, "bodyDeadline");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler, "deadlineScheduler");
    if (bodyDeadline.isZero() || bodyDeadline.isNegative()) {
      throw new IllegalArgumentException("bodyDeadline must be positive");
    }
    bodyDeadline.toNanos();
  }

  @Override
  public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
    Objects.requireNonNull(responseInfo, "responseInfo");
    if (declaresOversizedBody(responseInfo.headers())) {
      return new RejectedBodySubscriber(new IOException(BODY_TOO_LARGE_MESSAGE));
    }
    return new BoundedBodySubscriber(bodyDeadline, nanoTime, deadlineScheduler);
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

  @FunctionalInterface
  interface DeadlineScheduler {
    Future<?> schedule(Duration delay, Runnable task);
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
    private final long startedAtNanos;
    private final long deadlineNanos;
    private final LongSupplier nanoTime;
    private Future<?> deadlineTask;

    private Flow.Subscription subscription;
    private int observedBytes;
    private boolean terminated;

    BoundedBodySubscriber(
        Duration bodyDeadline, LongSupplier nanoTime, DeadlineScheduler deadlineScheduler) {
      this.nanoTime = nanoTime;
      startedAtNanos = nanoTime.getAsLong();
      deadlineNanos = bodyDeadline.toNanos();
      deadlineTask =
          deadlineScheduler.schedule(
              bodyDeadline, () -> Thread.startVirtualThread(this::onDeadline));
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription candidate) {
      Objects.requireNonNull(candidate, "subscription");
      Failure failure = null;
      boolean requestBody = false;
      synchronized (this) {
        if (subscription != null || terminated) {
          // Cancellation occurs below, outside the subscriber monitor.
        } else {
          subscription = candidate;
          if (deadlineExpired()) {
            failure = terminateWithFailureLocked(new HttpTimeoutException(BODY_TIMEOUT_MESSAGE));
          } else {
            requestBody = true;
          }
        }
      }

      if (failure != null) {
        publishFailure(failure);
      } else if (requestBody) {
        candidate.request(1);
      } else {
        candidate.cancel();
      }
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      long incomingBytes = 0;
      for (var buffer : buffers) {
        incomingBytes += buffer.remaining();
        if (incomingBytes > MAX_BODY_BYTES) {
          break;
        }
      }

      Failure failure = null;
      Flow.Subscription current = null;
      synchronized (this) {
        if (terminated) {
          return;
        }
        if (deadlineExpired()) {
          failure = terminateWithFailureLocked(new HttpTimeoutException(BODY_TIMEOUT_MESSAGE));
        } else if (incomingBytes > MAX_BODY_BYTES - observedBytes) {
          failure = terminateWithFailureLocked(new IOException(BODY_TOO_LARGE_MESSAGE));
        } else {
          for (var buffer : buffers) {
            int bytes = buffer.remaining();
            buffer.get(buffered, observedBytes, bytes);
            observedBytes += bytes;
          }
          current = subscription;
        }
      }

      if (failure != null) {
        publishFailure(failure);
      } else if (current != null) {
        current.request(1);
      }
    }

    @Override
    public void onError(Throwable throwable) {
      publishFailure(terminateWithFailure(new IOException(BODY_READ_FAILURE_MESSAGE)));
    }

    @Override
    public void onComplete() {
      Failure failure = null;
      byte[] result = null;
      synchronized (this) {
        if (terminated) {
          return;
        }
        if (deadlineExpired()) {
          failure = terminateWithFailureLocked(new HttpTimeoutException(BODY_TIMEOUT_MESSAGE));
        } else {
          terminated = true;
          cancelDeadlineLocked();
          result = Arrays.copyOf(buffered, observedBytes);
          Arrays.fill(buffered, (byte) 0);
        }
      }

      if (failure != null) {
        publishFailure(failure);
      } else {
        body.complete(result);
      }
    }

    private void onDeadline() {
      publishFailure(terminateWithFailure(new HttpTimeoutException(BODY_TIMEOUT_MESSAGE)));
    }

    private Failure terminateWithFailure(IOException exception) {
      synchronized (this) {
        return terminateWithFailureLocked(exception);
      }
    }

    private Failure terminateWithFailureLocked(IOException exception) {
      if (terminated) {
        return null;
      }
      terminated = true;
      cancelDeadlineLocked();
      Arrays.fill(buffered, (byte) 0);
      return new Failure(subscription, exception);
    }

    private void publishFailure(Failure failure) {
      if (failure == null) {
        return;
      }
      if (failure.subscription() != null) {
        failure.subscription().cancel();
      }
      body.completeExceptionally(failure.exception());
    }

    private boolean deadlineExpired() {
      return nanoTime.getAsLong() - startedAtNanos >= deadlineNanos;
    }

    private void cancelDeadlineLocked() {
      if (deadlineTask != null) {
        deadlineTask.cancel(false);
      }
    }

    private record Failure(Flow.Subscription subscription, IOException exception) {}
  }
}
