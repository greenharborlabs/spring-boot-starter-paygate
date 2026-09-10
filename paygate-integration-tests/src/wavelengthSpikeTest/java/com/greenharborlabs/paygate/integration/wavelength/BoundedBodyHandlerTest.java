package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Bounded Wavelength response bodies")
class BoundedBodyHandlerTest {

  private static final Duration TEST_DEADLINE = Duration.ofSeconds(3);

  @Nested
  @DisplayName("subscriber boundaries")
  class SubscriberBoundaries {

    @Test
    void acceptsExactly256Kib() {
      var subscriber = subscriber(Map.of(), TEST_DEADLINE);
      var subscription = new RecordingSubscription();
      subscriber.onSubscribe(subscription);

      subscriber.onNext(List.of(ByteBuffer.wrap(new byte[BoundedBodyHandler.MAX_BODY_BYTES])));
      subscriber.onComplete();

      assertThat(subscriber.getBody().toCompletableFuture().join())
          .hasSize(BoundedBodyHandler.MAX_BODY_BYTES);
      assertThat(subscription.cancelled).isFalse();
      assertThat(subscription.requests).isEqualTo(2);
    }

    @Test
    void rejectsByte256KibPlusOneBeforeCompletingOrParsing() {
      var subscriber = subscriber(Map.of(), TEST_DEADLINE);
      var subscription = new RecordingSubscription();
      var parseInvocations = new AtomicInteger();
      var parsed =
          subscriber
              .getBody()
              .thenApply(
                  bytes -> {
                    parseInvocations.incrementAndGet();
                    return bytes;
                  })
              .toCompletableFuture();
      subscriber.onSubscribe(subscription);

      subscriber.onNext(List.of(ByteBuffer.wrap(new byte[BoundedBodyHandler.MAX_BODY_BYTES])));
      subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));

      assertThatThrownBy(parsed::join)
          .isInstanceOf(CompletionException.class)
          .hasRootCauseMessage(BoundedBodyHandler.BODY_TOO_LARGE_MESSAGE);
      assertThat(subscription.cancelled).isTrue();
      assertThat(subscription.requests).isEqualTo(2);
      assertThat(parseInvocations).hasValue(0);
    }

    @Test
    void rejectsOversizedDeclaredLengthWithoutRequestingBodyBytes() {
      var subscriber =
          subscriber(
              Map.of(
                  "Content-Length",
                  List.of(Integer.toString(BoundedBodyHandler.MAX_BODY_BYTES + 1))),
              TEST_DEADLINE);
      var subscription = new RecordingSubscription();

      subscriber.onSubscribe(subscription);

      assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
          .isInstanceOf(CompletionException.class)
          .hasRootCauseMessage(BoundedBodyHandler.BODY_TOO_LARGE_MESSAGE);
      assertThat(subscription.cancelled).isTrue();
      assertThat(subscription.requests).isZero();
    }
  }

  @Nested
  @DisplayName("loopback transport")
  class LoopbackTransport {

    @Test
    void acceptsAnExactBoundaryResponse() throws Exception {
      try (var server =
              new TestServer(
                  exchange -> {
                    var body = new byte[BoundedBodyHandler.MAX_BODY_BYTES];
                    exchange.sendResponseHeaders(200, body.length);
                    try (var response = exchange.getResponseBody()) {
                      response.write(body);
                    }
                  });
          var client = HttpClient.newHttpClient()) {
        var response = client.send(request(server.uri()), new BoundedBodyHandler(TEST_DEADLINE));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(BoundedBodyHandler.MAX_BODY_BYTES);
      }
    }

    @Test
    void rejectsAnOversizedChunkedResponse() throws Exception {
      try (var server =
              new TestServer(
                  exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    try (var response = exchange.getResponseBody()) {
                      response.write(new byte[BoundedBodyHandler.MAX_BODY_BYTES + 1]);
                    } catch (IOException ignored) {
                      // Cancellation may close the loopback response while the fixture is writing.
                    }
                  });
          var client = HttpClient.newHttpClient()) {
        assertThatThrownBy(
                () -> client.send(request(server.uri()), new BoundedBodyHandler(TEST_DEADLINE)))
            .isInstanceOf(IOException.class)
            .hasMessage(BoundedBodyHandler.BODY_TOO_LARGE_MESSAGE);
      }
    }

    @Test
    void abortsAStalledBodyWithinItsDeadline() throws Exception {
      try (var server =
              new TestServer(
                  exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    try (var response = exchange.getResponseBody()) {
                      response.write(1);
                      response.flush();
                      Thread.sleep(Duration.ofSeconds(10));
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } catch (IOException ignored) {
                      // The body deadline closes the response stream.
                    }
                  });
          var client = HttpClient.newHttpClient()) {
        var started = System.nanoTime();

        assertThatThrownBy(
                () ->
                    client.send(
                        request(server.uri()), new BoundedBodyHandler(Duration.ofMillis(300))))
            .isInstanceOf(IOException.class)
            .hasMessage(BoundedBodyHandler.BODY_TIMEOUT_MESSAGE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      }
    }

    @Test
    void abortsAContinuouslySlowBodyAtTheAbsoluteDeadline() throws Exception {
      try (var server =
              new TestServer(
                  exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    try (var response = exchange.getResponseBody()) {
                      for (int index = 0; index < 40; index++) {
                        response.write(index);
                        response.flush();
                        Thread.sleep(75);
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } catch (IOException ignored) {
                      // The body deadline closes the response stream.
                    }
                  });
          var client = HttpClient.newHttpClient()) {
        var started = System.nanoTime();

        assertThatThrownBy(
                () ->
                    client.send(
                        request(server.uri()), new BoundedBodyHandler(Duration.ofMillis(350))))
            .isInstanceOf(IOException.class)
            .hasMessage(BoundedBodyHandler.BODY_TIMEOUT_MESSAGE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      }
    }
  }

  private static HttpResponse.BodySubscriber<byte[]> subscriber(
      Map<String, List<String>> headers, Duration deadline) {
    return new BoundedBodyHandler(deadline).apply(responseInfo(headers));
  }

  private static HttpResponse.ResponseInfo responseInfo(Map<String, List<String>> headers) {
    return new HttpResponse.ResponseInfo() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(headers, (name, value) -> true);
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }
    };
  }

  private static HttpRequest request(URI uri) {
    return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
  }

  private static final class RecordingSubscription implements Flow.Subscription {
    private long requests;
    private boolean cancelled;

    @Override
    public void request(long count) {
      requests += count;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }

  private static final class TestServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    TestServer(HttpHandler handler) throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      executor = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(executor);
      server.createContext("/fixture", handler);
      server.start();
    }

    URI uri() {
      return URI.create("http://localhost:" + server.getAddress().getPort() + "/fixture");
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
