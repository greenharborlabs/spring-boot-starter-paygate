package com.greenharborlabs.paygate.core.protocol;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural extraction of an L402/LSAT Authorization header without full macaroon deserialization.
 * Performs regex-based validation with DoS protection (macaroon portion limited to 8192 characters).
 */
public record L402HeaderComponents(String scheme, String macaroonBase64, String preimageHex) {

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("(LSAT|L402) ([A-Za-z0-9+/=,]{1,8192}):([a-fA-F0-9]{64})");

    public L402HeaderComponents {
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(macaroonBase64, "macaroonBase64 must not be null");
        Objects.requireNonNull(preimageHex, "preimageHex must not be null");
    }

    /**
     * Extracts L402/LSAT header components from a raw Authorization header value.
     *
     * @param authorizationHeader the raw Authorization header value
     * @return components if the header matches L402/LSAT format, empty otherwise
     */
    public static Optional<L402HeaderComponents> extract(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            return Optional.empty();
        }

        Matcher matcher = HEADER_PATTERN.matcher(authorizationHeader);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        return Optional.of(new L402HeaderComponents(
                matcher.group(1),
                matcher.group(2),
                matcher.group(3)
        ));
    }

    /**
     * Extracts L402/LSAT header components or throws on any structural mismatch.
     *
     * @param authorizationHeader the raw Authorization header value
     * @return parsed components
     * @throws L402Exception with {@link ErrorCode#MALFORMED_HEADER} if the header is null, empty, or malformed
     */
    public static L402HeaderComponents extractOrThrow(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Authorization header must not be null or empty", null);
        }

        Matcher matcher = HEADER_PATTERN.matcher(authorizationHeader);
        if (!matcher.matches()) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Authorization header does not match L402/LSAT format", null);
        }

        return new L402HeaderComponents(
                matcher.group(1),
                matcher.group(2),
                matcher.group(3)
        );
    }

    /**
     * Lightweight prefix check for fast rejection of non-L402 headers.
     *
     * @param header the raw Authorization header value
     * @return true if the header starts with "L402 " or "LSAT "
     */
    public static boolean isL402Header(String header) {
        if (header == null || header.isEmpty()) {
            return false;
        }
        return header.startsWith("L402 ") || header.startsWith("LSAT ");
    }

    /**
     * Reconstructs the Authorization header value from components.
     *
     * @return the reconstructed header value
     */
    public String toHeaderValue() {
        return scheme + " " + macaroonBase64 + ":" + preimageHex;
    }
}
