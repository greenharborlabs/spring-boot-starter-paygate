package com.greenharborlabs.paygate.protocol.mpp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the {@code request} field in an MPP challenge.
 *
 * <p>Wire format (JCS/RFC 8785 — keys sorted lexicographically):
 * <pre>
 * {"amount":"100","currency":"BTC","description":"Access to API",
 *  "methodDetails":{"invoice":"lnbc...","network":"mainnet","paymentHash":"abc123..."}}
 * </pre>
 *
 * @param amount        satoshi amount as a string (e.g. "100")
 * @param currency      currency code, typically "BTC"
 * @param description   human-readable description; nullable (omitted from wire format when null)
 * @param methodDetails lightning-specific payment details
 */
public record LightningChargeRequest(
        String amount,
        String currency,
        String description,
        MethodDetails methodDetails
) {

    /**
     * Compact constructor — validates required (non-null) fields.
     */
    public LightningChargeRequest {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(methodDetails, "methodDetails must not be null");
    }

    /**
     * Lightning-specific payment details nested inside a charge request.
     *
     * @param invoice     BOLT-11 invoice string
     * @param paymentHash lowercase hex payment hash
     * @param network     one of "mainnet", "testnet", "signet"
     */
    public record MethodDetails(
            String invoice,
            String paymentHash,
            String network
    ) {

        /**
         * Compact constructor — all fields are required.
         */
        public MethodDetails {
            Objects.requireNonNull(invoice, "invoice must not be null");
            Objects.requireNonNull(paymentHash, "paymentHash must not be null");
            Objects.requireNonNull(network, "network must not be null");
        }

        /**
         * Returns a JCS-ordered map of these method details.
         * Key order: invoice, network, paymentHash (lexicographic).
         */
        Map<String, Object> toJcsMap() {
            var map = new LinkedHashMap<String, Object>(3);
            map.put("invoice", invoice);
            map.put("network", network);
            map.put("paymentHash", paymentHash);
            return map;
        }
    }

    /**
     * Converts this charge request to a {@link Map} suitable for
     * {@code JcsSerializer.serialize()}.
     *
     * <p>Keys are in JCS (RFC 8785) lexicographic order:
     * {@code amount}, {@code currency}, {@code description} (if non-null),
     * {@code methodDetails}.
     *
     * @return an insertion-ordered map whose iteration order matches JCS key sorting
     */
    public Map<String, Object> toJcsMap() {
        // 4 entries max (description is optional)
        var map = new LinkedHashMap<String, Object>(4);
        map.put("amount", amount);
        map.put("currency", currency);
        if (description != null) {
            map.put("description", description);
        }
        map.put("methodDetails", methodDetails.toJcsMap());
        return map;
    }
}
