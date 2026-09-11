package com.greenharborlabs.paygate.integration.wavelength;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class WavelengthTestFixtures {

  static final long AMOUNT = 10;
  static final long CREATED = 1_700_000_000L;
  static final Instant NOW = Instant.ofEpochSecond(CREATED + 10);
  static final String INVOICE = "synthetic-bolt11";
  static final String MEMO = "synthetic memo";
  static final byte[] PREIMAGE = repeated((byte) 7);
  static final byte[] PAYMENT_HASH = sha256(PREIMAGE);
  static final String PAYMENT_HASH_HEX = HexFormat.of().formatHex(PAYMENT_HASH);
  static final String OTHER_HASH_HEX = "ab".repeat(32);

  private WavelengthTestFixtures() {}

  static DecodedBolt11 decoded() {
    return new DecodedBolt11(
        "testnet-family",
        PAYMENT_HASH,
        AMOUNT,
        Instant.ofEpochSecond(CREATED),
        Instant.ofEpochSecond(CREATED + 3_600));
  }

  static String statusJson(boolean ready, String network) {
    return """
        {"ready":%s,"unlocked":true,"network":"%s","balance":{"confirmed_sat":"100","pending_in_sat":"0","pending_out_sat":"0","credit_available_sat":"0","credit_reserved_sat":"0"},"pending_count":0}
        """
        .formatted(ready, network)
        .trim();
  }

  static String swapRecvJson() {
    return """
        {"invoice":"%s","entry":%s,"credit_receive":null}
        """
        .formatted(
            INVOICE,
            entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, ""))
        .trim();
  }

  static String creditRecvJson() {
    return """
        {"invoice":"%s","entry":%s,"credit_receive":{"operation_id":"credit-op-1","amount_sat":"10","payment_hash":"%s"}}
        """
        .formatted(
            INVOICE,
            entryJson("credit-op-1", PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, ""),
            PAYMENT_HASH_HEX)
        .trim();
  }

  static String entryJson(
      String id, String progressHash, String status, String failureCode, String preimage) {
    String phase =
        switch (status) {
          case "ENTRY_STATUS_PENDING" -> "WALLET_ENTRY_PHASE_WAITING_FOR_PAYMENT";
          case "ENTRY_STATUS_COMPLETE" -> "WALLET_ENTRY_PHASE_CONFIRMED";
          case "ENTRY_STATUS_FAILED" -> "WALLET_ENTRY_PHASE_FAILED";
          default -> "WALLET_ENTRY_PHASE_UNSPECIFIED";
        };
    String failure = failureCode == null ? "" : ",\"failure_code\":\"" + failureCode + "\"";
    return """
        {"id":"%s","kind":"ENTRY_KIND_RECV","status":"%s","amount_sat":"10","created_at_unix":"1700000000","updated_at_unix":"1700000001","note":"synthetic memo","request":{"lightning_invoice":{"invoice":"synthetic-bolt11","payment_hash":"%s"}},"progress":{"phase":"%s","payment_hash":"%s","preimage":"%s"}%s}
        """
        .formatted(id, status, PAYMENT_HASH_HEX, phase, progressHash, preimage, failure)
        .trim();
  }

  static String inspectJson(String entry) {
    return "{\"entry\":" + entry + "}";
  }

  static String pageJson(List<String> entries, boolean hasMore, String nextCursor) {
    return "{\"activity\":{\"entries\":["
        + String.join(",", entries)
        + "],\"total\":"
        + entries.size()
        + ",\"has_more\":"
        + hasMore
        + ",\"next_cursor\":\""
        + nextCursor
        + "\"}}";
  }

  static String grpcError(int code) {
    return "{\"code\":" + code + ",\"message\":\"synthetic upstream detail\",\"details\":[]}";
  }

  static FixtureResponse json(int status, String body) {
    return new FixtureResponse(status, body, 0);
  }

  static FixtureResponse delayedJson(int status, String body, long delayMillis) {
    return new FixtureResponse(status, body, delayMillis);
  }

  private static byte[] repeated(byte value) {
    var result = new byte[32];
    java.util.Arrays.fill(result, value);
    return result;
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  record FixtureResponse(int status, String body, long delayMillis) {}

  static final class FixtureServer implements AutoCloseable {
    private final HttpServer server;
    private final ConcurrentLinkedQueue<FixtureResponse> responses;
    private final List<CapturedRequest> requests =
        java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger calls = new AtomicInteger();

    FixtureServer(FixtureResponse... responses) throws IOException {
      this.responses = new ConcurrentLinkedQueue<>(List.of(responses));
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    int calls() {
      return calls.get();
    }

    List<CapturedRequest> requests() {
      synchronized (requests) {
        return List.copyOf(requests);
      }
    }

    private void handle(HttpExchange exchange) throws IOException {
      calls.incrementAndGet();
      var requestBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requests.add(
          new CapturedRequest(
              exchange.getRequestURI().getPath(),
              requestBody,
              exchange.getRequestHeaders().getFirst("macaroon")));
      var response = responses.poll();
      if (response == null) {
        response = json(500, grpcError(13));
      }
      if (response.delayMillis() > 0) {
        try {
          Thread.sleep(response.delayMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      var bytes = response.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      try {
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally {
        exchange.close();
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  record CapturedRequest(String path, String body, String macaroon) {}
}
