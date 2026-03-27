package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashMap;

/**
 * In-memory implementation of {@link RootKeyStore} backed by a {@link HashMap}.
 * Keys are lost when the JVM exits. Suitable for testing and short-lived processes.
 */
public final class InMemoryRootKeyStore implements RootKeyStore {

    private static final int KEY_LENGTH = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom secureRandom = new SecureRandom();
    private final HashMap<String, byte[]> keys = new HashMap<>();
    private volatile boolean closed;

    @Override
    public GenerationResult generateRootKey() {
        synchronized (this) {
            ensureOpen();
            byte[] rootKey = new byte[KEY_LENGTH];
            try {
                secureRandom.nextBytes(rootKey);

                byte[] tokenId = new byte[KEY_LENGTH];
                secureRandom.nextBytes(tokenId);

                String hexKeyId = HEX.formatHex(tokenId);
                keys.put(hexKeyId, Arrays.copyOf(rootKey, rootKey.length));

                return new GenerationResult(new SensitiveBytes(rootKey.clone()), tokenId);
            } finally {
                KeyMaterial.zeroize(rootKey);
            }
        }
    }

    @Override
    public SensitiveBytes getRootKey(byte[] keyId) {
        synchronized (this) {
            ensureOpen();
            String hexKeyId = HEX.formatHex(keyId);
            byte[] stored = keys.get(hexKeyId);
            return stored == null ? null : new SensitiveBytes(stored.clone());
        }
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
        synchronized (this) {
            ensureOpen();
            String hexKeyId = HEX.formatHex(keyId);
            keys.computeIfPresent(hexKeyId, (_, value) -> {
                KeyMaterial.zeroize(value);
                return null; // removes the entry
            });
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
            keys.values().forEach(KeyMaterial::zeroize);
            keys.clear();
            closed = true;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Store is closed");
        }
    }
}
