package com.greenharborlabs.paygate.core.macaroon;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable representation of a macaroon bearer credential.
 *
 * <p>The identifier is a 66-byte binary blob: {@code [version:2][paymentHash:32][tokenId:32]}. The
 * signature is a 32-byte HMAC-SHA256 digest computed over the identifier and caveat chain. The
 * location is an optional, unsigned hint and plays no role in verification.
 */
public final class Macaroon {

  static final int IDENTIFIER_LENGTH = 66;
  static final int SIGNATURE_LENGTH = 32;

  private final byte[] identifier;
  private final String location;
  private final List<Caveat> caveats;
  private final byte[] signature;

  /**
   * Constructs an immutable macaroon.
   *
   * @param identifier 66-byte binary identifier
   * @param location optional service URL hint (may be {@code null})
   * @param caveats ordered list of first-party caveats (must not be {@code null})
   * @param signature 32-byte HMAC-SHA256 signature
   * @throws NullPointerException if identifier, caveats, or signature is {@code null}
   * @throws IllegalArgumentException if identifier is not 66 bytes or signature is not 32 bytes
   */
  public Macaroon(byte[] identifier, String location, List<Caveat> caveats, byte[] signature) {
    Objects.requireNonNull(identifier, "identifier must not be null");
    Objects.requireNonNull(caveats, "caveats must not be null");
    Objects.requireNonNull(signature, "signature must not be null");

    if (identifier.length != IDENTIFIER_LENGTH) {
      throw new IllegalArgumentException(
          "identifier must be exactly " + IDENTIFIER_LENGTH + " bytes, got " + identifier.length);
    }
    if (signature.length != SIGNATURE_LENGTH) {
      throw new IllegalArgumentException(
          "signature must be exactly " + SIGNATURE_LENGTH + " bytes, got " + signature.length);
    }

    this.identifier = identifier.clone();
    this.location = location;
    this.caveats = List.copyOf(caveats);
    this.signature = signature.clone();
  }

  public byte[] identifier() {
    return identifier.clone();
  }

  public String location() {
    return location;
  }

  public List<Caveat> caveats() {
    return caveats; // already unmodifiable via List.copyOf
  }

  public byte[] signature() {
    return signature.clone();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Macaroon other)) return false;
    return MacaroonCrypto.constantTimeEquals(identifier, other.identifier)
        && Objects.equals(location, other.location)
        && caveats.equals(other.caveats)
        && MacaroonCrypto.constantTimeEquals(signature, other.signature);
  }

  @Override
  public int hashCode() {
    int result = Arrays.hashCode(identifier);
    result = 31 * result + Objects.hashCode(location);
    result = 31 * result + caveats.hashCode();
    result = 31 * result + Arrays.hashCode(signature);
    return result;
  }

  @Override
  public String toString() {
    return "Macaroon[identifierLength="
        + identifier.length
        + ", location="
        + location
        + ", caveatCount="
        + caveats.size()
        + "]";
  }
}
