package com.greenharborlabs.paygate.api;

import com.greenharborlabs.paygate.api.crypto.CryptoUtils;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-agnostic data record carrying all information needed to create a payment challenge.
 * Produced by {@code PaygateChallengeService} and consumed by protocol-specific formatters (L402
 * macaroon minting, MPP header generation, etc.).
 *
 * <p>Defensive copies are made for all mutable fields ({@code paymentHash}, {@code rootKeyBytes},
 * {@code opaque}) both on construction and on access.
 *
 * <p>{@code routePattern} is the canonical framework route pattern and {@code requestMethod} is the
 * actual request method used to mint the challenge. Both are optional request-boundary metadata and
 * are intentionally omitted from {@link #toString()}. The eleven-argument compatibility constructor
 * leaves both components {@code null} for non-L402 and receipt-only callers.
 */
public record ChallengeContext(
    byte[] paymentHash,
    String tokenId,
    String bolt11Invoice,
    long priceSats,
    String description,
    String serviceName,
    long timeoutSeconds,
    String capability,
    byte[] rootKeyBytes,
    Map<String, String> opaque,
    String digest,
    String routePattern,
    String requestMethod) {

  public ChallengeContext {
    Objects.requireNonNull(paymentHash, "paymentHash must not be null");
    Objects.requireNonNull(tokenId, "tokenId must not be null");
    Objects.requireNonNull(bolt11Invoice, "bolt11Invoice must not be null");
    if (priceSats <= 0) {
      throw new IllegalArgumentException("priceSats must be positive, got " + priceSats);
    }

    // Defensive copies of mutable fields
    paymentHash = paymentHash.clone();
    rootKeyBytes = rootKeyBytes != null ? rootKeyBytes.clone() : null;
    opaque = opaque != null ? Map.copyOf(opaque) : null;
  }

  /**
   * Creates a challenge without request-boundary metadata.
   *
   * <p>This constructor preserves the original public constructor descriptor for existing non-L402
   * and receipt-only callers. {@link #routePattern()} and {@link #requestMethod()} return {@code
   * null} for contexts created through it.
   */
  public ChallengeContext(
      byte[] paymentHash,
      String tokenId,
      String bolt11Invoice,
      long priceSats,
      String description,
      String serviceName,
      long timeoutSeconds,
      String capability,
      byte[] rootKeyBytes,
      Map<String, String> opaque,
      String digest) {
    this(
        paymentHash,
        tokenId,
        bolt11Invoice,
        priceSats,
        description,
        serviceName,
        timeoutSeconds,
        capability,
        rootKeyBytes,
        opaque,
        digest,
        null,
        null);
  }

  @Override
  public byte[] paymentHash() {
    return paymentHash.clone();
  }

  @Override
  public byte[] rootKeyBytes() {
    return rootKeyBytes != null ? rootKeyBytes.clone() : null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ChallengeContext that)) return false;
    return priceSats == that.priceSats
        && timeoutSeconds == that.timeoutSeconds
        && CryptoUtils.constantTimeEquals(paymentHash, that.paymentHash)
        && Objects.equals(tokenId, that.tokenId)
        && Objects.equals(bolt11Invoice, that.bolt11Invoice)
        && Objects.equals(description, that.description)
        && Objects.equals(serviceName, that.serviceName)
        && Objects.equals(capability, that.capability)
        && ((rootKeyBytes == null && that.rootKeyBytes == null)
            || (rootKeyBytes != null
                && that.rootKeyBytes != null
                && CryptoUtils.constantTimeEquals(rootKeyBytes, that.rootKeyBytes)))
        && Objects.equals(opaque, that.opaque)
        && Objects.equals(digest, that.digest)
        && Objects.equals(routePattern, that.routePattern)
        && Objects.equals(requestMethod, that.requestMethod);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            tokenId,
            bolt11Invoice,
            priceSats,
            description,
            serviceName,
            timeoutSeconds,
            capability,
            opaque,
            digest,
            routePattern,
            requestMethod);
    result = 31 * result + Arrays.hashCode(paymentHash);
    result = 31 * result + Arrays.hashCode(rootKeyBytes);
    return result;
  }

  @Override
  public String toString() {
    return "ChallengeContext[tokenId="
        + tokenId
        + ", priceSats="
        + priceSats
        + ", serviceName="
        + serviceName
        + "]";
  }
}
