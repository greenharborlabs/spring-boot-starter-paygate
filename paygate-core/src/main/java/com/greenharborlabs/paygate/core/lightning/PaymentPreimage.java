package com.greenharborlabs.paygate.core.lightning;

import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;
import javax.security.auth.Destroyable;

/**
 * A 32-byte payment preimage used in Lightning Network HTLC settlement.
 * The corresponding payment hash is SHA-256(preimage).
 *
 * <p>Implements {@link AutoCloseable} and {@link Destroyable} so that the
 * sensitive preimage bytes can be zeroized when no longer needed. After
 * {@link #destroy()} or {@link #close()}, all accessors throw
 * {@link IllegalStateException}.
 */
public final class PaymentPreimage implements AutoCloseable, Destroyable {

    private static final int PREIMAGE_LENGTH = 32;
    private static final int SHA256_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final ReentrantLock lock = new ReentrantLock();

    private final byte[] data;
    private volatile boolean destroyed;

    /**
     * Creates a new {@code PaymentPreimage} from the given 32-byte value.
     * The input array is defensively copied; the caller's array is NOT zeroized.
     *
     * @param value the 32-byte preimage (must not be null)
     * @throws IllegalArgumentException if {@code value} is null or not exactly 32 bytes
     */
    public PaymentPreimage(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Preimage value must not be null");
        }
        if (value.length != PREIMAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "Preimage must be exactly " + PREIMAGE_LENGTH + " bytes, got " + value.length);
        }
        this.data = value.clone();
    }

    /**
     * Returns a defensive copy of the preimage bytes.
     *
     * @return a fresh copy of the internal byte array
     * @throws IllegalStateException if this instance has been destroyed
     */
    public byte[] value() {
        lock.lock();
        try {
            if (destroyed) {
                throw new IllegalStateException("Preimage has been destroyed");
            }
            return Arrays.copyOf(data, data.length);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifies that SHA-256(this preimage) equals the given payment hash,
     * using constant-time comparison to prevent timing side-channels.
     *
     * @throws IllegalStateException    if this instance has been destroyed
     * @throws IllegalArgumentException if paymentHash is null or not 32 bytes
     */
    public boolean matchesHash(byte[] paymentHash) {
        lock.lock();
        try {
            if (destroyed) {
                throw new IllegalStateException("Preimage has been destroyed");
            }
            if (paymentHash == null) {
                throw new IllegalArgumentException("Payment hash must not be null");
            }
            if (paymentHash.length != SHA256_LENGTH) {
                throw new IllegalArgumentException(
                        "Payment hash must be exactly " + SHA256_LENGTH + " bytes, got " + paymentHash.length);
            }
            byte[] computed = sha256(data);
            return MacaroonCrypto.constantTimeEquals(computed, paymentHash);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the preimage as a 64-character lowercase hex string.
     *
     * @throws IllegalStateException if this instance has been destroyed
     */
    public String toHex() {
        lock.lock();
        try {
            if (destroyed) {
                throw new IllegalStateException("Preimage has been destroyed");
            }
            return HEX.formatHex(data);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Parses a 64-character hex string (case-insensitive) into a PaymentPreimage.
     */
    public static PaymentPreimage fromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            throw new IllegalArgumentException("Hex string must not be null or empty");
        }
        if (hex.length() != PREIMAGE_LENGTH * 2) {
            throw new IllegalArgumentException(
                    "Hex string must be exactly " + (PREIMAGE_LENGTH * 2) + " characters, got " + hex.length());
        }
        try {
            byte[] decoded = HEX.parseHex(hex.toLowerCase());
            return new PaymentPreimage(decoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid hex string: " + e.getMessage(), e);
        }
    }

    /**
     * Zeroizes the internal preimage bytes using {@link KeyMaterial#zeroize(byte[])}.
     * Idempotent -- safe to call multiple times.
     */
    @Override
    public void destroy() {
        lock.lock();
        try {
            if (!destroyed) {
                KeyMaterial.zeroize(data);
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
     * Constant-time equality comparison using {@link MacaroonCrypto#constantTimeEquals}.
     * Two destroyed instances are never equal. Only locks {@code this}; reading
     * {@code other.destroyed} (volatile) and {@code other.data} without locking
     * {@code other} is safe — at worst, a concurrent destruction causes comparison
     * against zeroed bytes, returning {@code false}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentPreimage other)) return false;

        lock.lock();
        try {
            if (this.destroyed || other.destroyed) return false;
            return MacaroonCrypto.constantTimeEquals(this.data, other.data);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int hashCode() {
        lock.lock();
        try {
            if (destroyed) return 0;
            // Constant hashCode prevents preimage leakage through hash codes
            return 0x5052_4549; // "PREI" — PaymentPreimage marker
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a safe string representation that never reveals the preimage bytes.
     */
    @Override
    public String toString() {
        return destroyed ? "PaymentPreimage[destroyed]" : "PaymentPreimage[**]";
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
