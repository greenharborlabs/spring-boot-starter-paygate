package com.greenharborlabs.l402.core.macaroon;

import java.util.Arrays;

/**
 * Static utilities for zeroizing sensitive key material in raw byte arrays.
 * Use for intermediate HMAC chain values and other contexts where wrapping
 * in {@code SensitiveBytes} is impractical.
 */
public final class KeyMaterial {

    /**
     * Volatile write fence to prevent the JIT compiler from dead-store-eliminating
     * the {@link Arrays#fill} call. The volatile write after the fill acts as a
     * compiler barrier, ensuring the zeroing is not optimized away.
     */
    @SuppressWarnings("unused")
    private static volatile int FENCE = 0;

    private KeyMaterial() {
        // utility class
    }

    /**
     * Fills the given array with zeros. Null-safe — a null argument is a no-op.
     *
     * <p>A volatile write fence follows the fill to prevent the JIT from
     * eliminating the store as a dead write.
     *
     * @param data the array to zeroize, may be null
     */
    public static void zeroize(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
            FENCE = data.length;
        }
    }

    /**
     * Zeroizes multiple arrays. Null entries are silently skipped.
     *
     * @param arrays the arrays to zeroize, individual entries may be null
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
