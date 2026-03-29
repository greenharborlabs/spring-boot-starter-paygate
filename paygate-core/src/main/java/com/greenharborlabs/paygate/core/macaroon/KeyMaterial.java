package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.crypto.CryptoUtils;

/**
 * Static utilities for zeroizing sensitive key material in raw byte arrays. Use for intermediate
 * HMAC chain values and other contexts where wrapping in {@code SensitiveBytes} is impractical.
 */
public final class KeyMaterial {

  private KeyMaterial() {
    // utility class
  }

  /**
   * Fills the given array with zeros. Null-safe — a null argument is a no-op.
   *
   * <p>A volatile write fence follows the fill to prevent the JIT from eliminating the store as a
   * dead write.
   *
   * @param data the array to zeroize, may be null
   */
  public static void zeroize(byte[] data) {
    CryptoUtils.zeroize(data);
  }

  /**
   * Zeroizes multiple arrays. Null entries are silently skipped.
   *
   * @param arrays the arrays to zeroize, individual entries may be null
   */
  public static void zeroize(byte[]... arrays) {
    CryptoUtils.zeroize(arrays);
  }
}
