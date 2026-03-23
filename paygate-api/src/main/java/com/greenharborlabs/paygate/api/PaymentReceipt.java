package com.greenharborlabs.paygate.api;

import java.util.Objects;

/**
 * Data for the {@code Payment-Receipt} response header (MPP spec).
 *
 * @param status         receipt status, e.g. "success"
 * @param challengeId    HMAC-bound challenge ID
 * @param method         payment method, e.g. "lightning"
 * @param reference      method-specific reference (nullable)
 * @param amountSats     amount paid in satoshis (must be positive)
 * @param timestamp      RFC 3339 timestamp
 * @param protocolScheme protocol scheme, e.g. "Payment"
 */
public record PaymentReceipt(
        String status,
        String challengeId,
        String method,
        String reference,
        long amountSats,
        String timestamp,
        String protocolScheme
) {

    public PaymentReceipt {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(protocolScheme, "protocolScheme must not be null");
        if (amountSats <= 0) {
            throw new IllegalArgumentException("amountSats must be > 0, got " + amountSats);
        }
    }
}
