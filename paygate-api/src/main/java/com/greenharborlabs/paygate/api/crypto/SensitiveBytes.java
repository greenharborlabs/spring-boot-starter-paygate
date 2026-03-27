package com.greenharborlabs.paygate.api.crypto;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
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

    /**
     * Tie-breaker lock for the rare case when two distinct instances have the same
     * {@code System.identityHashCode}. Acquiring this lock first guarantees a total
     * order even when the identity hash codes collide.
     */
    private static final ReentrantLock TIE_BREAKER_LOCK = new ReentrantLock();

    private final byte[] data;
    private volatile boolean destroyed;
    private final ReentrantLock lock = new ReentrantLock();

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
        CryptoUtils.zeroize(raw);
    }

    /**
     * Returns a defensive copy of the key material.
     *
     * @return a fresh copy of the internal byte array
     * @throws IllegalStateException if this instance has been destroyed
     */
    public byte[] value() {
        lock.lock();
        try {
            if (destroyed) {
                throw new IllegalStateException("Key material has been destroyed");
            }
            return Arrays.copyOf(data, data.length);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Zeroizes the internal key material. Idempotent — safe to call multiple times.
     */
    @Override
    public void destroy() {
        lock.lock();
        try {
            if (!destroyed) {
                CryptoUtils.zeroize(data);
                destroyed = true;
            }
        } finally {
            lock.unlock();
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
     * Constant-time equality comparison using {@link CryptoUtils#constantTimeEquals}.
     * Two destroyed instances are never equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SensitiveBytes other)) return false;

        // Acquire both locks in System.identityHashCode order to prevent deadlock.
        int thisHash = System.identityHashCode(this);
        int otherHash = System.identityHashCode(other);

        if (thisHash < otherHash) {
            this.lock.lock();
            other.lock.lock();
        } else if (thisHash > otherHash) {
            other.lock.lock();
            this.lock.lock();
        } else {
            // Identity hash collision — use global tie-breaker to establish order
            TIE_BREAKER_LOCK.lock();
            try {
                this.lock.lock();
                other.lock.lock();
            } finally {
                TIE_BREAKER_LOCK.unlock();
            }
        }

        try {
            if (this.destroyed || other.destroyed) return false;
            return CryptoUtils.constantTimeEquals(this.data, other.data);
        } finally {
            other.lock.unlock();
            this.lock.unlock();
        }
    }

    @Override
    public int hashCode() {
        lock.lock();
        try {
            if (destroyed) return 0;
            // Constant hashCode prevents key material leakage through hash codes
            return 0x534B_4559; // "SKEY" — SensitiveBytes key marker
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a safe string representation that never reveals the key material.
     */
    @Override
    public String toString() {
        return "SensitiveBytes[length=" + data.length + "]";
    }
}
