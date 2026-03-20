package com.greenharborlabs.paygate.core.protocol;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.util.JsonEscaper;

import java.util.Base64;
import java.util.Objects;

/**
 * Represents an L402 payment challenge issued to a client that has not yet paid.
 * Contains the macaroon (pre-authorized but unsigned by payment), the Lightning invoice,
 * the price in satoshis, and a human-readable description.
 */
public record L402Challenge(Macaroon macaroon, String bolt11Invoice, long priceSats, String description) {

    public L402Challenge {
        Objects.requireNonNull(macaroon, "macaroon must not be null");
        Objects.requireNonNull(bolt11Invoice, "bolt11Invoice must not be null");
        if (bolt11Invoice.isEmpty()) {
            throw new IllegalArgumentException("bolt11Invoice must not be empty");
        }
        if (priceSats <= 0) {
            throw new IllegalArgumentException("priceSats must be positive, got " + priceSats);
        }
    }

    @Override
    public String toString() {
        return "L402Challenge[priceSats=" + priceSats + ", description=" + description + "]";
    }

    /**
     * Formats the challenge as a {@code WWW-Authenticate} header value.
     * <p>Example: {@code L402 version="0", token="<base64>", macaroon="<base64>", invoice="<bolt11>"}
     * <p>{@code token=} is the spec-compliant primary parameter name; {@code macaroon=} is kept
     * as a backward-compatible alias carrying the same value.
     *
     * @return the header value string
     */
    public String toWwwAuthenticateHeader() {
        String macaroonBase64 = Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon));
        return "L402 version=\"0\", token=\"" + macaroonBase64
                + "\", macaroon=\"" + macaroonBase64
                + "\", invoice=\"" + sanitizeBolt11ForHeader(bolt11Invoice) + "\"";
    }

    /**
     * Validates that a bolt11 string is safe for inclusion in an HTTP header value
     * per RFC 7230 section 3.2.6. Rejects all control characters (0x00-0x1F and 0x7F DEL)
     * and the double-quote character (which would break the quoted parameter format).
     * <p>
     * HTAB (0x09) is also rejected: while RFC 7230 permits HTAB in generic header values,
     * bolt11 invoices are alphanumeric-only, so HTAB is always invalid in this context.
     * <p>
     * Throws rather than silently stripping, because a modified bolt11 invoice is unpayable.
     */
    private static String sanitizeBolt11ForHeader(String bolt11) {
        for (int i = 0; i < bolt11.length(); i++) {
            char c = bolt11.charAt(i);
            if (c <= 0x1F || c == 0x7F || c == '"') {
                throw new IllegalArgumentException(
                        "bolt11 invoice contains illegal character at index " + i
                                + ": 0x" + Integer.toHexString(c));
            }
        }
        return bolt11;
    }

    /**
     * Builds a JSON response body describing the payment challenge.
     * Uses manual string construction to avoid external JSON library dependencies.
     *
     * @return a JSON string
     */
    public String toJsonBody() {
        String escapedDescription = description == null ? "null" : "\"" + JsonEscaper.escape(description) + "\"";
        return "{\"code\":402,\"message\":\"Payment required\",\"price_sats\":" + priceSats
                + ",\"description\":" + escapedDescription
                + ",\"invoice\":\"" + JsonEscaper.escape(bolt11Invoice) + "\"}";
    }
}
