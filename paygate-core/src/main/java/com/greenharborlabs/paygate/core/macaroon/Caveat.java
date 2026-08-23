package com.greenharborlabs.paygate.core.macaroon;

/**
 * A first-party caveat restricting macaroon usage. Encoded as {@code key=value} UTF-8 bytes for
 * HMAC chain input.
 *
 * <p>The key and value retain their parsed signed text exactly. Verifier lookup may later use
 * {@link CaveatKey#canonicalize(String)} after signature verification, but this record never trims,
 * rewrites, or serializes a canonical form.
 */
public record Caveat(String key, String value) {

  public Caveat {
    CaveatKey.requireValidConstructedKey(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("value must not be null, empty, or blank");
    }
  }

  @Override
  public String toString() {
    return key + "=" + value;
  }
}
