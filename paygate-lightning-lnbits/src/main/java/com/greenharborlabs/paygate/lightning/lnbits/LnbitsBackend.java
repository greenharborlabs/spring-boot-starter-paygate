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
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String responseBody = truncateBody(response.body());
        log.log(
            System.Logger.Level.WARNING,
            "LNbits createInvoice returned HTTP {0}: {1}",
            response.statusCode(),
            responseBody);
        throw new LnbitsException(
            "LNbits API returned HTTP " + response.statusCode() + ": " + responseBody);
      }
      JsonNode json = objectMapper.readTree(response.body());

      JsonNode hashNode = json.get("payment_hash");
      if (hashNode == null || hashNode.isNull()) {
        throw new LnbitsException("Missing 'payment_hash' in LNbits create invoice response");
      }
      JsonNode bolt11Node = json.get("payment_request");
      if (bolt11Node == null || bolt11Node.isNull()) {
        throw new LnbitsException("Missing 'payment_request' in LNbits create invoice response");
      }

      String paymentHashHex = hashNode.asText();
      String bolt11 = bolt11Node.asText();
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
          config.requestTimeoutSeconds());
      throw new LnbitsTimeoutException(
          "LNbits createInvoice timed out after " + config.requestTimeoutSeconds() + "s", e);
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
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String body = truncateBody(response.body());
        log.log(
            System.Logger.Level.WARNING,
            "LNbits lookupInvoice returned HTTP {0} for hash={1}: {2}",
            response.statusCode(),
            hashHex,
            body);
        throw new LnbitsException(
            "LNbits API returned HTTP " + response.statusCode() + ": " + body);
      }
      JsonNode json = objectMapper.readTree(response.body());

      JsonNode paidNode = json.get("paid");
      if (paidNode == null || paidNode.isNull()) {
        throw new LnbitsException("Missing 'paid' in LNbits lookup response");
      }
      boolean paid = paidNode.asBoolean();

      JsonNode details = json.get("details");
      if (details == null || details.isNull()) {
        throw new LnbitsException("Missing 'details' in LNbits lookup response");
      }

      JsonNode bolt11Node = details.get("bolt11");
      if (bolt11Node == null || bolt11Node.isNull()) {
        throw new LnbitsException("Missing 'details.bolt11' in LNbits lookup response");
      }
      JsonNode amountNode = details.get("amount");
      if (amountNode == null || amountNode.isNull()) {
        throw new LnbitsException("Missing 'details.amount' in LNbits lookup response");
      }

      String bolt11 = bolt11Node.asText();
      long amount = amountNode.asLong() / MSAT_PER_SAT;
      String memo = details.has("memo") ? details.get("memo").asText() : null;

      InvoiceStatus status = paid ? InvoiceStatus.SETTLED : InvoiceStatus.PENDING;
      byte[] preimage = null;
      if (paid && json.has("preimage") && !json.get("preimage").isNull()) {
        preimage = HEX.parseHex(json.get("preimage").asText());
      }

      Instant createdAt;
      JsonNode timeNode = details.get("time");
      if (timeNode != null && !timeNode.isNull() && timeNode.isNumber()) {
        createdAt = Instant.ofEpochSecond(timeNode.asLong());
      } else {
        createdAt = Instant.now();
      }

      Instant expiresAt;
      JsonNode expiryNode = details.get("expiry");
      if (expiryNode != null && !expiryNode.isNull() && expiryNode.isNumber()) {
        expiresAt = createdAt.plusSeconds(expiryNode.asLong());
      } else {
        expiresAt = createdAt.plus(DEFAULT_INVOICE_EXPIRY);
      }

      return new Invoice(paymentHash, bolt11, amount, memo, status, preimage, createdAt, expiresAt);
    } catch (HttpTimeoutException e) {
      log.log(
          System.Logger.Level.WARNING,
          "LNbits lookupInvoice timed out after {0}s",
          config.requestTimeoutSeconds());
      throw new LnbitsTimeoutException(
          "LNbits lookupInvoice timed out after " + config.requestTimeoutSeconds() + "s", e);
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

  private static String truncateBody(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    return body.length() <= MAX_BODY_LENGTH ? body : body.substring(0, MAX_BODY_LENGTH) + "...";
  }
}
