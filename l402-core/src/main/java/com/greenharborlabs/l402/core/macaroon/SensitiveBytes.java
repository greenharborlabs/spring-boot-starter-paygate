package com.greenharborlabs.l402.core.macaroon;

import java.util.Arrays;
import java.util.Objects;
import javax.security.auth.Destroyable;

/**
 * Secure wrapper for key material that must be zeroized when no longer needed.
 *
 * <p>The constructor defensively copies the input and fills the original with zeros.
 * On {@link #close()} or {@link #destroy()}, the internal copy is also zeroized.
 * After destruction, {@link #value()} throws {@link IllegalStateException}.
 *
 * <p>This class is {@code final} and does not implement {@code Cloneable} or
 * {@code Serializable} to prevent bypassing the zeroization lifecycle.
 */
public final class SensitiveBytes implements AutoCloseable, Destroyable {

    private final byte[] data;
    private volatile boolean destroyed;

    /**
     * Creates a new {@code SensitiveBytes} from the given raw key material.
     * The input array is defensively copied and then filled with zeros.
     *
     * @param raw the key material (must not be null or empty)
     * @throws NullPointerException     if {@code raw} is null
     * @throws IllegalArgumentException if {@code raw} is zero-length
     */
    public SensitiveBytes(byte[] raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length == 0) {
            throw new IllegalArgumentException("Key material must not be empty");
        }
        this.data = Arrays.copyOf(raw, raw.length);
        KeyMaterial.zeroize(raw);
    }

    /**
     * Returns a defensive copy of the key material.
     *
     * @return a fresh copy of the internal byte array
     * @throws IllegalStateException if this instance has been destroyed
     */
    public synchronized byte[] value() {
        if (destroyed) {
            throw new IllegalStateException("Key material has been destroyed");
        }
        return Arrays.copyOf(data, data.length);
    }

    /**
     * Zeroizes the internal key material. Idempotent — safe to call multiple times.
     */
    @Override
    public synchronized void destroy() {
        if (!destroyed) {
            KeyMaterial.zeroize(data);
            destroyed = true;
        }
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Delegates to {@link #destroy()}.
     */
    @Override
    public void close() {
        destroy();
    }

    /**
     * Constant-time equality comparison using {@link MacaroonCrypto#constantTimeEquals}.
     * Two destroyed instances are never equal.
     */
    @Override
    public synchronized boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SensitiveBytes other)) return false;
        if (this.destroyed || other.destroyed) return false;
        return MacaroonCrypto.constantTimeEquals(this.data, other.data);
    }

    @Override
    public synchronized int hashCode() {
        if (destroyed) return 0;
        return Arrays.hashCode(data);
    }

    /**
     * Returns a safe string representation that never reveals the key material.
     */
    @Override
    public String toString() {
        return "SensitiveBytes[length=" + data.length + "]";
    }
}
