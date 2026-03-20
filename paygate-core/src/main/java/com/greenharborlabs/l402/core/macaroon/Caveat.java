package com.greenharborlabs.l402.core.macaroon;

/**
 * A first-party caveat restricting macaroon usage.
 * Encoded as {@code key=value} UTF-8 bytes for HMAC chain input.
 */
public record Caveat(String key, String value) {

    public Caveat {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null, empty, or blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be null, empty, or blank");
        }
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }
}
