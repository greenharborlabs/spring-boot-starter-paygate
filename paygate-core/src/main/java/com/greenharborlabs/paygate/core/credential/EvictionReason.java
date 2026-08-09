package com.greenharborlabs.paygate.core.credential;

public enum EvictionReason {
  EXPIRED(Invalidity.PERMANENT),
  CAPACITY(Invalidity.NOT_APPLICABLE),
  REVOKED(Invalidity.PERMANENT);

  private final Invalidity invalidity;

  EvictionReason(Invalidity invalidity) {
    this.invalidity = invalidity;
  }

  /**
   * States whether the event proves the cached credential is no longer reusable.
   *
   * <p>This classification intentionally contains no credential or bearer data, so callers can make
   * eviction decisions without retaining sensitive request material.
   */
  public Invalidity invalidity() {
    return invalidity;
  }

  public enum Invalidity {
    PERMANENT,
    TRANSIENT,
    NOT_APPLICABLE
  }
}
