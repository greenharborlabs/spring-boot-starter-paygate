package com.greenharborlabs.l402.lightning.lnbits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * LNbits implementation of {@link LightningBackend}.
 * Uses {@link java.net.http.HttpClient} for HTTP and Jackson for JSON.
 */
public class LnbitsBackend implements LightningBackend {

    private static final HexFormat HEX = HexFormat.of();
    private static final Duration DEFAULT_INVOICE_EXPIRY = Duration.ofHours(1);

    private final LnbitsConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;

    public LnbitsBackend(LnbitsConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        // Strip trailing slash for consistent URL construction
        String url = config.baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("out", false);
            body.put("amount", amountSats);
            body.put("memo", memo);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/payments"))
                    .header("X-Api-Key", config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LnbitsException("LNbits API returned HTTP " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());

            String paymentHashHex = json.get("payment_hash").asText();
            String bolt11 = json.get("payment_request").asText();
            Instant now = Instant.now();

            return new Invoice(
                    HEX.parseHex(paymentHashHex),
                    bolt11,
                    amountSats,
                    memo,
                    InvoiceStatus.PENDING,
                    null,
                    now,
                    now.plus(DEFAULT_INVOICE_EXPIRY)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LnbitsException("Interrupted while creating invoice", e);
        } catch (LnbitsException e) {
            throw e;
        } catch (Exception e) {
            throw new LnbitsException("Failed to create invoice via LNbits", e);
        }
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        try {
            String hashHex = HEX.formatHex(paymentHash);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/payments/" + hashHex))
                    .header("X-Api-Key", config.apiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LnbitsException("LNbits API returned HTTP " + response.statusCode());
            }
            JsonNode json = objectMapper.readTree(response.body());

            boolean paid = json.get("paid").asBoolean();
            JsonNode details = json.get("details");

            String bolt11 = details.get("bolt11").asText();
            long amount = details.get("amount").asLong();
            String memo = details.has("memo") ? details.get("memo").asText() : null;

            InvoiceStatus status = paid ? InvoiceStatus.SETTLED : InvoiceStatus.PENDING;
            byte[] preimage = null;
            if (paid && json.has("preimage") && !json.get("preimage").isNull()) {
                preimage = HEX.parseHex(json.get("preimage").asText());
            }

            Instant now = Instant.now();
            return new Invoice(
                    paymentHash,
                    bolt11,
                    amount,
                    memo,
                    status,
                    preimage,
                    now,
                    now.plus(DEFAULT_INVOICE_EXPIRY)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LnbitsException("Interrupted while looking up invoice", e);
        } catch (LnbitsException e) {
            throw e;
        } catch (Exception e) {
            throw new LnbitsException("Failed to lookup invoice via LNbits", e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/wallet"))
                    .header("X-Api-Key", config.apiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
