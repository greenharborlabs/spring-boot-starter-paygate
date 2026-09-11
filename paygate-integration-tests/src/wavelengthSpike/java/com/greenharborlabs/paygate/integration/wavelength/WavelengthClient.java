package com.greenharborlabs.paygate.integration.wavelength;

import com.greenharborlabs.paygate.api.SecurityBounds;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import tools.jackson.databind.node.ObjectNode;

/** Bounded HTTP client for the four pinned Wavelength v0.1.1 spike operations. */
final class WavelengthClient {

  static final int PAGE_SIZE = 100;
  static final int DEFAULT_MAX_LOOKUP_PAGES = 5;
  static final int HARD_MAX_LOOKUP_PAGES = 20;
  static final int DEFAULT_MAX_LOOKUP_ENTRIES = 500;
  static final int HARD_MAX_LOOKUP_ENTRIES = 2_000;
  static final Duration STATUS_DEADLINE = Duration.ofSeconds(10);
  static final Duration LOOKUP_DEADLINE = Duration.ofSeconds(15);

  private static final int GRPC_INVALID_ARGUMENT = 3;
  private static final int GRPC_DEADLINE_EXCEEDED = 4;
  private static final int GRPC_NOT_FOUND = 5;
  private static final int GRPC_PERMISSION_DENIED = 7;
  private static final int GRPC_RESOURCE_EXHAUSTED = 8;
  private static final int GRPC_ABORTED = 10;
  private static final int GRPC_UNIMPLEMENTED = 12;
  private static final int GRPC_UNAVAILABLE = 14;
  private static final int GRPC_UNAUTHENTICATED = 16;

  private final HttpClient httpClient;
  private final URI baseUri;
  private final String macaroonHex;
  private final Duration requestTimeout;
  private final int maxLookupPages;
  private final int maxLookupEntries;
  private final LongSupplier nanoTime;

  WavelengthClient(HttpClient httpClient, WavelengthClientConfig config) {
    this(httpClient, config, System::nanoTime);
  }

  WavelengthClient(HttpClient httpClient, WavelengthClientConfig config, LongSupplier nanoTime) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    Objects.requireNonNull(config, "config");
    this.baseUri = config.baseUri();
    this.macaroonHex = config.macaroonHex();
    this.requestTimeout = config.requestTimeout();
    this.maxLookupPages = config.maxLookupPages();
    this.maxLookupEntries = config.maxLookupEntries();
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  WavelengthWire.StatusResponse status() {
    return WavelengthWire.parseStatus(
        post(Operation.STATUS, "v1/wallet/status", emptyBody(), STATUS_DEADLINE));
  }

  WavelengthWire.RecvResponse recv(long amountSats, String memo) {
    if (!SecurityBounds.isValidPrice(amountSats)) {
      throw new IllegalArgumentException("amountSats must be within the supported invoice range");
    }
    if (memo == null || memo.getBytes(StandardCharsets.UTF_8).length > 256) {
      throw new IllegalArgumentException("memo must be at most 256 UTF-8 bytes");
    }
    var body = WavelengthWire.jsonMapper().createObjectNode();
    body.put("amt_sat", Long.toString(amountSats));
    body.put("memo", memo);
    // Recv is deliberately one send with no retry: timeout leaves creation indeterminate.
    return WavelengthWire.parseRecv(post(Operation.RECV, "v1/wallet/recv", body, requestTimeout));
  }

  WavelengthWire.InspectActivityResponse inspectActivity(byte[] paymentHash, LookupBudget budget) {
    var body = WavelengthWire.jsonMapper().createObjectNode();
    body.put("id", HexFormat.of().formatHex(paymentHash));
    body.put("ledger_limit", PAGE_SIZE);
    var response =
        WavelengthWire.parseInspectActivity(
            post(
                Operation.INSPECT_ACTIVITY,
                "v1/wallet/inspect/activity",
                body,
                budget.nextCallTimeout(requestTimeout)));
    budget.ensureActive();
    return response;
  }

  WavelengthWire.ActivityPage list(String cursor, LookupBudget budget) {
    var body = WavelengthWire.jsonMapper().createObjectNode();
    body.put("view", "LIST_VIEW_ACTIVITY");
    body.put("pending_only", false);
    body.putArray("kinds").add("ENTRY_KIND_RECV");
    body.put("limit", PAGE_SIZE);
    body.put("cursor", cursor);
    var response =
        WavelengthWire.parseActivityPage(
            post(Operation.LIST, "v1/wallet/list", body, budget.nextCallTimeout(requestTimeout)));
    budget.ensureActive();
    return response;
  }

  LookupBudget newLookupBudget() {
    return new LookupBudget(nanoTime, LOOKUP_DEADLINE, maxLookupPages, maxLookupEntries);
  }

  private byte[] post(Operation operation, String path, ObjectNode body, Duration timeout) {
    var request =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("macaroon", macaroonHex)
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofByteArray(WavelengthWire.jsonBytes(body)))
            .build();
    try {
      var response = httpClient.send(request, new BoundedBodyHandler(timeout));
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return response.body();
      }
      throw classifyFailure(operation, response);
    } catch (HttpTimeoutException e) {
      throw timeout(operation, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WavelengthException("Wavelength operation was interrupted", e);
    } catch (IOException e) {
      if (containsMessage(e, BoundedBodyHandler.BODY_TIMEOUT_MESSAGE)) {
        throw timeout(operation, e);
      }
      if (containsMessage(e, BoundedBodyHandler.BODY_TOO_LARGE_MESSAGE)) {
        throw new WavelengthProtocolException("Wavelength response exceeded its size limit", e);
      }
      throw new WavelengthUpstreamException(
          "Wavelength service is unavailable",
          FailureClassification.TEMPORARY_UNAVAILABLE,
          -1,
          null,
          e);
    }
  }

  private static RuntimeException classifyFailure(
      Operation operation, HttpResponse<byte[]> response) {
    int httpStatus = response.statusCode();
    Integer grpcCode = WavelengthWire.parseGrpcCode(response.body()).orElse(null);

    if (operation == Operation.INSPECT_ACTIVITY
        && httpStatus == 404
        && Integer.valueOf(GRPC_NOT_FOUND).equals(grpcCode)) {
      return new WavelengthInspectNotFoundException();
    }
    if (httpStatus == 401
        || httpStatus == 403
        || Integer.valueOf(GRPC_UNAUTHENTICATED).equals(grpcCode)
        || Integer.valueOf(GRPC_PERMISSION_DENIED).equals(grpcCode)) {
      return new WavelengthAuthenticationException();
    }
    if (Integer.valueOf(GRPC_DEADLINE_EXCEEDED).equals(grpcCode)) {
      return timeout(operation, new HttpTimeoutException("upstream deadline"));
    }

    FailureClassification classification;
    if (httpStatus == 400 || Integer.valueOf(GRPC_INVALID_ARGUMENT).equals(grpcCode)) {
      classification = FailureClassification.PERMANENT_REQUEST;
    } else if (httpStatus == 404 || Integer.valueOf(GRPC_UNIMPLEMENTED).equals(grpcCode)) {
      classification = FailureClassification.PERMANENT_CONFIGURATION;
    } else if (httpStatus == 409 || Integer.valueOf(GRPC_ABORTED).equals(grpcCode)) {
      classification = FailureClassification.OPERATION_FAILURE;
    } else if (httpStatus == 429
        || Integer.valueOf(GRPC_RESOURCE_EXHAUSTED).equals(grpcCode)
        || httpStatus >= 500
        || Integer.valueOf(GRPC_UNAVAILABLE).equals(grpcCode)) {
      classification = FailureClassification.TEMPORARY_UNAVAILABLE;
    } else {
      classification = FailureClassification.PROTOCOL_FAILURE;
    }
    return new WavelengthUpstreamException(
        "Wavelength operation failed", classification, httpStatus, grpcCode, null);
  }

  private static WavelengthTimeoutException timeout(Operation operation, Throwable cause) {
    String message =
        operation == Operation.RECV
            ? "Wavelength Recv outcome is indeterminate"
            : "Wavelength operation timed out";
    return new WavelengthTimeoutException(message, cause);
  }

  private static boolean containsMessage(Throwable failure, String message) {
    for (var current = failure; current != null; current = current.getCause()) {
      if (message.equals(current.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private static ObjectNode emptyBody() {
    return WavelengthWire.jsonMapper().createObjectNode();
  }

  enum Operation {
    STATUS,
    RECV,
    INSPECT_ACTIVITY,
    LIST
  }

  enum FailureClassification {
    PERMANENT_REQUEST,
    PERMANENT_CONFIGURATION,
    OPERATION_FAILURE,
    TEMPORARY_UNAVAILABLE,
    PROTOCOL_FAILURE
  }

  record WavelengthClientConfig(
      URI baseUri,
      String macaroonHex,
      Duration requestTimeout,
      int maxLookupPages,
      int maxLookupEntries) {

    WavelengthClientConfig {
      Objects.requireNonNull(baseUri, "baseUri");
      Objects.requireNonNull(macaroonHex, "macaroonHex");
      Objects.requireNonNull(requestTimeout, "requestTimeout");
      if (!baseUri.isAbsolute()
          || baseUri.getHost() == null
          || baseUri.getUserInfo() != null
          || baseUri.getQuery() != null
          || baseUri.getFragment() != null) {
        throw new IllegalArgumentException("Wavelength base URI is invalid");
      }
      var normalizedPath = baseUri.getPath();
      if (normalizedPath == null || !normalizedPath.endsWith("/")) {
        baseUri = URI.create(baseUri.toString() + "/");
      }
      if (macaroonHex.isBlank()
          || macaroonHex.length() % 2 != 0
          || macaroonHex.length() > 131_072
          || !macaroonHex.equals(macaroonHex.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Wavelength credential is invalid");
      }
      try {
        HexFormat.of().parseHex(macaroonHex);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Wavelength credential is invalid");
      }
      if (requestTimeout.isZero()
          || requestTimeout.isNegative()
          || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
        throw new IllegalArgumentException("Wavelength request timeout is invalid");
      }
      if (maxLookupPages < 1 || maxLookupPages > HARD_MAX_LOOKUP_PAGES) {
        throw new IllegalArgumentException("Wavelength lookup page budget is invalid");
      }
      if (maxLookupEntries < 1 || maxLookupEntries > HARD_MAX_LOOKUP_ENTRIES) {
        throw new IllegalArgumentException("Wavelength lookup entry budget is invalid");
      }
    }

    static WavelengthClientConfig defaults(URI baseUri, String macaroonHex) {
      return new WavelengthClientConfig(
          baseUri,
          macaroonHex,
          Duration.ofSeconds(10),
          DEFAULT_MAX_LOOKUP_PAGES,
          DEFAULT_MAX_LOOKUP_ENTRIES);
    }
  }

  static final class LookupBudget {
    private final LongSupplier nanoTime;
    private final long deadlineNanos;
    private final int maxPages;
    private final int maxEntries;

    private int pages;
    private int entries;

    LookupBudget(LongSupplier nanoTime, Duration deadline, int maxPages, int maxEntries) {
      this.nanoTime = nanoTime;
      this.deadlineNanos = nanoTime.getAsLong() + deadline.toNanos();
      this.maxPages = maxPages;
      this.maxEntries = maxEntries;
    }

    Duration nextCallTimeout(Duration perRequestMaximum) {
      return Duration.ofNanos(Math.min(remainingNanos(), perRequestMaximum.toNanos()));
    }

    void ensureActive() {
      remainingNanos();
    }

    private long remainingNanos() {
      long remaining = deadlineNanos - nanoTime.getAsLong();
      if (remaining <= 0) {
        throw new WavelengthTimeoutException(
            "Wavelength invoice lookup timed out", new HttpTimeoutException("lookup deadline"));
      }
      return remaining;
    }

    void consumePage(int pageEntries) {
      if (pageEntries < 0 || pages >= maxPages || pageEntries > maxEntries - entries) {
        throw new WavelengthLookupExhaustedException();
      }
      pages++;
      entries += pageEntries;
    }

    boolean canContinue() {
      return pages < maxPages && entries < maxEntries;
    }
  }
}
