package com.greenharborlabs.paygate.api;

import com.greenharborlabs.paygate.api.crypto.CryptoUtils;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import javax.security.auth.Destroyable;

/**
 * Protocol-agnostic representation of a parsed payment credential.
 *
 * <p>A credential exclusively owns private copies of its payment hash and preimage. Callers own
 * each array returned by an accessor and must clear sensitive copies after use. A credential must
 * be closed by the component that successfully parsed it.
 */
public final class PaymentCredential implements AutoCloseable, Destroyable {

  private static final Object EQUALS_TIE_LOCK = new Object();

  private final ReentrantLock lifecycleLock = new ReentrantLock();
  private final String tokenId;
  private final String sourceProtocolScheme;
  private final String source;
  private final ProtocolMetadata metadata;
  private final int stableHashCode;

  private byte[] paymentHash;
  private byte[] preimage;
  private boolean destroyed;

  /**
   * Creates a credential from caller-owned arrays, which are defensively copied.
   *
   * @param paymentHash SHA-256 hash of the preimage
   * @param preimage 32-byte preimage proving payment
   * @param tokenId token/challenge identifier
   * @param sourceProtocolScheme protocol that parsed this credential
   * @param source optional payer identity
   * @param metadata protocol-specific metadata
   */
  public PaymentCredential(
      byte[] paymentHash,
      byte[] preimage,
      String tokenId,
      String sourceProtocolScheme,
      String source,
      ProtocolMetadata metadata) {
    this.paymentHash = Objects.requireNonNull(paymentHash, "paymentHash must not be null").clone();
    this.preimage = Objects.requireNonNull(preimage, "preimage must not be null").clone();
    this.tokenId = Objects.requireNonNull(tokenId, "tokenId must not be null");
    this.sourceProtocolScheme =
        Objects.requireNonNull(sourceProtocolScheme, "sourceProtocolScheme must not be null");
    this.source = source;
    this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    // A stable hash cannot depend on material that is intentionally zeroized at close.
    this.stableHashCode = Objects.hash(tokenId, sourceProtocolScheme, source, metadata);
  }

  /** Returns a fresh caller-owned payment-hash copy. */
  public byte[] paymentHash() {
    lifecycleLock.lock();
    try {
      requireActive();
      return paymentHash.clone();
    } finally {
      lifecycleLock.unlock();
    }
  }

  /** Returns a fresh caller-owned preimage copy. */
  public byte[] preimage() {
    lifecycleLock.lock();
    try {
      requireActive();
      return preimage.clone();
    } finally {
      lifecycleLock.unlock();
    }
  }

  public String tokenId() {
    return tokenId;
  }

  public String sourceProtocolScheme() {
    return sourceProtocolScheme;
  }

  public String source() {
    return source;
  }

  public ProtocolMetadata metadata() {
    return metadata;
  }

  /** Clears owned sensitive arrays. Repeated calls are safe. */
  @Override
  public void destroy() {
    lifecycleLock.lock();
    try {
      if (!destroyed) {
        CryptoUtils.zeroize(paymentHash, preimage);
        destroyed = true;
      }
    } finally {
      lifecycleLock.unlock();
    }
  }

  /** Delegates to {@link #destroy()}. */
  @Override
  public void close() {
    destroy();
  }

  @Override
  public boolean isDestroyed() {
    lifecycleLock.lock();
    try {
      return destroyed;
    } finally {
      lifecycleLock.unlock();
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PaymentCredential that)
        || !tokenId.equals(that.tokenId)
        || !sourceProtocolScheme.equals(that.sourceProtocolScheme)
        || !Objects.equals(source, that.source)
        || !metadata.equals(that.metadata)) {
      return false;
    }

    return compareSensitiveMaterial(that);
  }

  private boolean compareSensitiveMaterial(PaymentCredential that) {
    int thisIdentity = System.identityHashCode(this);
    int thatIdentity = System.identityHashCode(that);
    if (thisIdentity == thatIdentity) {
      synchronized (EQUALS_TIE_LOCK) {
        return compareUnderOrderedLocks(that);
      }
    }
    return compareUnderOrderedLocks(that);
  }

  private boolean compareUnderOrderedLocks(PaymentCredential that) {
    PaymentCredential first =
        System.identityHashCode(this) < System.identityHashCode(that) ? this : that;
    PaymentCredential second = first == this ? that : this;
    first.lifecycleLock.lock();
    second.lifecycleLock.lock();
    try {
      return !destroyed
          && !that.destroyed
          && CryptoUtils.constantTimeEquals(paymentHash, that.paymentHash)
          && CryptoUtils.constantTimeEquals(preimage, that.preimage);
    } finally {
      second.lifecycleLock.unlock();
      first.lifecycleLock.unlock();
    }
  }

  @Override
  public int hashCode() {
    return stableHashCode;
  }

  @Override
  public String toString() {
    return "PaymentCredential[tokenId="
        + tokenId
        + ", sourceProtocolScheme="
        + sourceProtocolScheme
        + ", source="
        + source
        + "]";
  }

  private void requireActive() {
    if (destroyed) {
      throw new IllegalStateException("Payment credential has been destroyed");
    }
  }
}
