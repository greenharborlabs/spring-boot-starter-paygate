package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.crypto.CryptoUtils;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;

/**
 * Construction-time checks and defensive copies for MPP challenge-binding secrets.
 *
 * <p>Package-private. Zero external dependencies.
 */
final class MppBindingSecrets {

  private static final int MIN_SECRET_LENGTH = 32;

  private MppBindingSecrets() {} // utility class

  /**
   * Rejects a secret shorter than 32 bytes or made of a single repeated byte. A {@code null} secret
   * is accepted, because the previous secret is optional.
   *
   * @param fieldName the secret's name for the error message
   * @param secret the secret to check, or {@code null}
   * @throws IllegalArgumentException if the secret is too short or is a single repeated byte
   */
  static void validate(String fieldName, SensitiveBytes secret) {
    if (secret == null) {
      return;
    }
    byte[] temp = secret.value();
    try {
      if (temp.length < MIN_SECRET_LENGTH) {
        throw new IllegalArgumentException(
            fieldName + " must be at least " + MIN_SECRET_LENGTH + " bytes, got " + temp.length);
      }
      if (isSingleRepeatedByte(temp)) {
        throw new IllegalArgumentException(
            fieldName + " must not be a single repeated byte; generate it randomly");
      }
    } finally {
      CryptoUtils.zeroize(temp);
    }
  }

  /** Returns an independently owned copy of {@code secret}, or {@code null} for {@code null}. */
  static SensitiveBytes copy(SensitiveBytes secret) {
    if (secret == null) {
      return null;
    }
    return new SensitiveBytes(secret.value());
  }

  /**
   * Detects a placeholder secret such as all zeros. This is not an entropy estimate; it rejects
   * only the degenerate case. It accumulates over every byte, so timing does not depend on where
   * the first differing byte is.
   */
  private static boolean isSingleRepeatedByte(byte[] secret) {
    int difference = 0;
    for (byte value : secret) {
      difference |= value ^ secret[0];
    }
    return difference == 0;
  }
}
