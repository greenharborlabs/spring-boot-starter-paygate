package com.greenharborlabs.paygate.api.crypto;

import java.util.Arrays;

/**
 * Canonical constant-time comparison and best-effort zeroization utility for the paygate API.
 *
 * <p>All modules that need timing-safe byte comparisons or secure memory clearing should delegate
 * to this class rather than implementing their own.
 *
 * <p>This class has zero external dependencies — JDK only.
 */
public final class CryptoUtils {

  /**
   * Volatile fence to prevent dead-store elimination of {@link #zeroize} calls. The JIT compiler
   * might otherwise optimize away the {@link Arrays#fill} as a "dead store" since the array
   * contents are never read after zeroing.
   */
  @SuppressWarnings("unused")
  private static volatile int FENCE = 0;

  private CryptoUtils() {
    // utility class
  }

  /**
   * Compares two byte arrays in constant time (with respect to content).
   *
   * <p>If the arrays differ in length, this method returns {@code false} immediately. This leaks
   * the length difference but not the content — which matches the standard practice for HMAC
   * comparison where lengths are publicly known.
   *
   * @param a first byte array (must not be null)
   * @param b second byte array (must not be null)
   * @return {@code true} if both arrays have the same length and identical content
   */
  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    if (a.length != b.length) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length; i++) {
      result |= a[i] ^ b[i];
    }
    return result == 0;
  }

  /**
   * Fills the given caller-supplied byte array with zeros and writes a volatile fence to prevent
   * the JIT from eliminating the fill as a dead store. The cleared contents are immediately
   * observable through every reference to that array. This is best-effort zeroization: the JVM
   * cannot guarantee erasure of copies made by callers, native code, or garbage collectors.
   *
   * <p>Null-safe: passing {@code null} is a no-op.
   *
   * @param data the byte array to zeroize, or {@code null}
   */
  public static void zeroize(byte[] data) {
    if (data != null) {
      Arrays.fill(data, (byte) 0);
      FENCE = data.length;
    }
  }

  /**
   * Zeroizes all non-null caller-supplied arrays in the given varargs. Null entries are skipped.
   * Each supplied array is cleared independently, including repeated references to the same array.
   *
   * <p>Null-safe: passing {@code null} as the varargs array itself is a no-op.
   *
   * @param arrays the byte arrays to zeroize
   */
  public static void zeroize(byte[]... arrays) {
    if (arrays == null) {
      return;
    }
    for (byte[] data : arrays) {
      zeroize(data);
    }
  }
}
