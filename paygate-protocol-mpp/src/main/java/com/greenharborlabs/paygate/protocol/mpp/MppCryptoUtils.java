package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.crypto.CryptoUtils;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared cryptographic utilities for the MPP protocol module.
 *
 * <p>Package-private to prevent external use. Zero external dependencies.
 */
final class MppCryptoUtils {

  private MppCryptoUtils() {} // prevent instantiation

  /**
   * Constant-time byte array comparison using XOR accumulation.
   *
   * <p>Returns {@code false} immediately when array lengths differ. This is acceptable because
   * callers in this module compare fixed-length cryptographic outputs (HMAC-SHA256 or SHA-256)
   * whose length (32 bytes) is public knowledge, not a secret. This matches the behavior of Go's
   * {@code crypto/subtle.ConstantTimeCompare} and Java's {@code MessageDigest.isEqual()}.
   *
   * <p>When lengths match, performs a fixed-iteration XOR loop over all bytes so that the execution
   * time depends only on the array length, never on where the first difference occurs.
   *
   * <p>When both arrays are empty (length 0), returns {@code true}.
   *
   * @param a first byte array (never null)
   * @param b second byte array (never null)
   * @return true if arrays are equal, false otherwise
   */
  static boolean constantTimeEquals(byte[] a, byte[] b) {
    return CryptoUtils.constantTimeEquals(a, b);
  }

  /**
   * Computes SHA-256 hash of the given data.
   *
   * @param data input bytes (never null)
   * @return 32-byte SHA-256 digest
   */
  static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandatory in every conformant JRE
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
