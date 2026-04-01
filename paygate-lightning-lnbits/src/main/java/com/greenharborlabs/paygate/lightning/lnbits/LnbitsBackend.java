package com.greenharborlabs.paygate.lightning.lnbits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * LNbits implementation of {@link LightningBackend}. Uses {@link java.net.http.HttpClient} for HTTP
 * and Jackson for JSON.
 */
public class LnbitsBackend implements LightningBackend {

  private static final System.Logger log = System.getLogger(LnbitsBackend.class.getName());
  private static final HexFormat HEX = HexFormat.of();
  private static final Duration DEFAULT_INVOICE_EXPIRY = Duration.ofHours(1);

  private final LnbitsConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String baseUrl;
  private static final int MAX_BODY_LENGTH = 200;

  // LNbits returns invoice amounts in millisatoshis; divide by this factor to convert to satoshis.
  private static final long MSAT_PER_SAT = 1000;

  private final Duration requestTimeout;

  public LnbitsBackend(LnbitsConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    // Strip trailing slash for consistent URL construction
    String url = config.baseUrl();
    this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    this.requestTimeout = Duration.ofSeconds(config.requestTimeoutSeconds());
  }

  @Override
  public Invoice createInvoice(long amountSats, String memo) {
    try {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("out", false);
      body.put("amount", amountSats);
      body.put("memo", memo);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/v1/payments"))
              .header("X-Api-Key", config.apiKey())
              .header("Content-Type", "application/json")
              .timeout(requestTimeout)
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      checkResponseStatus(response, "createInvoice", "");
      JsonNode json = objectMapper.readTree(response.body());

      String paymentHashHex =
          requireField(json, "payment_hash", "create invoice response").asText();
      String bolt11 = requireField(json, "payment_request", "create invoice response").asText();
      Instant now = Instant.now();

      log.log(System.Logger.Level.DEBUG, "LNbits invoice created, paymentHash={0}", paymentHashHex);

      return new Invoice(
          HEX.parseHex(paymentHashHex),
          bolt11,
          amountSats,
          memo,
          InvoiceStatus.PENDING,
          null,
          now,
          now.plus(DEFAULT_INVOICE_EXPIRY));
    } catch (HttpTimeoutException e) {
      log.log(
          System.Logger.Level.WARNING,
          "LNbits createInvoice timed out after {0}s",
          requestTimeout.toSeconds());
      throw new LnbitsTimeoutException(
          "LNbits createInvoice timed out after " + requestTimeout.toSeconds() + "s", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LnbitsException("Interrupted while creating invoice", e);
    } catch (LnbitsException e) {
      throw e;
    } catch (Exception e) {
      log.log(System.Logger.Level.WARNING, "LNbits createInvoice failed", e);
      throw new LnbitsException("Failed to create invoice via LNbits", e);
    }
  }

  @Override
  public Invoice lookupInvoice(byte[] paymentHash) {
    if (paymentHash == null) {
      throw new IllegalArgumentException("paymentHash must not be null");
    }
    if (paymentHash.length != 32) {
      throw new IllegalArgumentException(
          "paymentHash must be exactly 32 bytes, got " + paymentHash.length);
    }
    try {
      String hashHex = HEX.formatHex(paymentHash);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/v1/payments/" + hashHex))
              .header("X-Api-Key", config.apiKey())
              .timeout(requestTimeout)
              .GET()
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      checkResponseStatus(response, "lookupInvoice", " for hash=" + hashHex);
      JsonNode json = objectMapper.readTree(response.body());
      return parseLookupResponse(json, paymentHash);
    } catch (HttpTimeoutException e) {
      log.log(
          System.Logger.Level.WARNING,
          "LNbits lookupInvoice timed out after {0}s",
          requestTimeout.toSeconds());
      throw new LnbitsTimeoutException(
          "LNbits lookupInvoice timed out after " + requestTimeout.toSeconds() + "s", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LnbitsException("Interrupted while looking up invoice", e);
    } catch (LnbitsException e) {
      throw e;
    } catch (Exception e) {
      log.log(System.Logger.Level.WARNING, "LNbits lookupInvoice failed", e);
      throw new LnbitsException("Failed to lookup invoice via LNbits", e);
    }
  }

  @Override
  public boolean isHealthy() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/v1/wallet"))
              .header("X-Api-Key", config.apiKey())
              .timeout(requestTimeout)
              .GET()
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      boolean healthy = response.statusCode() == 200;
      log.log(System.Logger.Level.DEBUG, "LNbits health check result={0}", healthy);
      return healthy;
    } catch (HttpTimeoutException e) {
      log.log(System.Logger.Level.WARNING, "LNbits health check timed out");
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      log.log(System.Logger.Level.WARNING, "LNbits health check failed", e);
      return false;
    }
  }

  /** Parses a lookup invoice JSON response into an {@link Invoice}. */
  private Invoice parseLookupResponse(JsonNode json, byte[] paymentHash) {
    boolean paid = requireField(json, "paid", "lookup response").asBoolean();
    JsonNode details = requireField(json, "details", "lookup response");

    String bolt11 = requireField(details, "bolt11", "lookup response", "details.bolt11").asText();
    long amount =
        requireField(details, "amount", "lookup response", "details.amount").asLong()
            / MSAT_PER_SAT;
    String memo = details.has("memo") ? details.get("memo").asText() : null;

    InvoiceStatus status = paid ? InvoiceStatus.SETTLED : InvoiceStatus.PENDING;
    byte[] preimage = null;
    if (paid && json.has("preimage") && !json.get("preimage").isNull()) {
      preimage = HEX.parseHex(json.get("preimage").asText());
    }

    Instant createdAt = parseEpochField(details, Instant.now());
    Instant expiresAt =
        parseExpiryField(details, createdAt, createdAt.plus(DEFAULT_INVOICE_EXPIRY));

    return new Invoice(paymentHash, bolt11, amount, memo, status, preimage, createdAt, expiresAt);
  }

  /** Parses an optional epoch-seconds field, returning the default if absent or non-numeric. */
  private static Instant parseEpochField(JsonNode parent, Instant defaultValue) {
    JsonNode node = parent.get("time");
    if (node != null && !node.isNull() && node.isNumber()) {
      return Instant.ofEpochSecond(node.asLong());
    }
    return defaultValue;
  }

  /**
   * Parses an optional expiry-seconds field relative to {@code base}, returning the default if
   * absent or non-numeric.
   */
  private static Instant parseExpiryField(JsonNode parent, Instant base, Instant defaultValue) {
    JsonNode node = parent.get("expiry");
    if (node != null && !node.isNull() && node.isNumber()) {
      return base.plusSeconds(node.asLong());
    }
    return defaultValue;
  }

  /** Returns a required non-null JSON field or throws {@link LnbitsException}. */
  private static JsonNode requireField(JsonNode parent, String field, String context) {
    return requireField(parent, field, context, field);
  }

  /** Returns a required non-null JSON field, using {@code displayName} in the error message. */
  private static JsonNode requireField(
      JsonNode parent, String field, String context, String displayName) {
    JsonNode node = parent.get(field);
    if (node == null || node.isNull()) {
      throw new LnbitsException("Missing '" + displayName + "' in LNbits " + context);
    }
    return node;
  }

  /**
   * Validates the HTTP response status code and throws {@link LnbitsException} with a truncated
   * body on non-2xx responses.
   */
  private static void checkResponseStatus(
      HttpResponse<String> response, String operation, String detail) {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String body = truncateBody(response.body());
      log.log(
          System.Logger.Level.WARNING,
          "LNbits {0} returned HTTP {1}{2}: {3}",
          operation,
          response.statusCode(),
          detail,
          body);
      throw new LnbitsException("LNbits API returned HTTP " + response.statusCode() + ": " + body);
    }
  }

  private static String truncateBody(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    return body.length() <= MAX_BODY_LENGTH ? body : body.substring(0, MAX_BODY_LENGTH) + "...";
  }
}
