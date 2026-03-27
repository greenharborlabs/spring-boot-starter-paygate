package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;

import java.io.Closeable;
import java.util.Arrays;

public interface RootKeyStore extends Closeable {

    /**
     * Result of generating a new root key, containing both the root key
     * and the tokenId that identifies it atomically.
     */
    record GenerationResult(SensitiveBytes rootKey, byte[] tokenId) implements AutoCloseable {
        public GenerationResult {
            java.util.Objects.requireNonNull(rootKey, "rootKey");
            tokenId = Arrays.copyOf(tokenId, tokenId.length);
        }

        @Override
        public byte[] tokenId() {
            return Arrays.copyOf(tokenId, tokenId.length);
        }

        @Override
        public void close() {
            rootKey.close();
            KeyMaterial.zeroize(tokenId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GenerationResult other)) return false;
            return rootKey.equals(other.rootKey)
                    && MacaroonCrypto.constantTimeEquals(tokenId, other.tokenId);
        }

        @Override
        public int hashCode() {
            int result = rootKey.hashCode();
            result = 31 * result + Arrays.hashCode(tokenId);
            return result;
        }
    }

    GenerationResult generateRootKey();
    SensitiveBytes getRootKey(byte[] keyId);
    void revokeRootKey(byte[] keyId);

    @Override
    default void close() {
        // Default no-op; implementations override to zeroize held key material
    }
}
