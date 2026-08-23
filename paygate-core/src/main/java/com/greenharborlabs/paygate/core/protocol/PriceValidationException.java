package com.greenharborlabs.paygate.core.protocol;

/**
 * A typed L402 validation failure concerning paid-price evidence.
 *
 * <p>The kind deliberately distinguishes challengeable underpayment from unavailable evidence so
 * HTTP integrations can fail closed without turning infrastructure failures into invoices.
 */
public final class PriceValidationException extends L402Exception {

  /** Stable, protocol-neutral paid-price outcomes. */
  public enum Kind {
    /** The credential contains authenticated paid-price evidence below the current price. */
    INSUFFICIENT_PRICE,
    /** A request-dependent policy received a legacy credential without signed price evidence. */
    MISSING_PRICE_EVIDENCE,
    /** A route-stable legacy credential was issued below the configured current price. */
    ROUTE_STABLE_PRICE_INCREASE,
    /** Authoritative legacy invoice evidence could not be obtained or trusted. */
    EVIDENCE_UNAVAILABLE
  }

  private final Kind kind;

  /**
   * Creates a typed price failure.
   *
   * @param kind stable paid-price failure kind
   * @param tokenId sanitized credential token identifier
   */
  public PriceValidationException(Kind kind, String tokenId) {
    super(
        kind == Kind.EVIDENCE_UNAVAILABLE
            ? ErrorCode.LIGHTNING_UNAVAILABLE
            : ErrorCode.INVALID_SERVICE,
        "L402 paid-price validation failed",
        tokenId);
    this.kind = java.util.Objects.requireNonNull(kind, "kind must not be null");
  }

  /** Returns the stable paid-price outcome. */
  public Kind kind() {
    return kind;
  }

  /** Returns whether an integration may create a fresh full-price payment challenge. */
  public boolean isChallengeable() {
    return kind != Kind.EVIDENCE_UNAVAILABLE;
  }
}
