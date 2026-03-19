package com.greenharborlabs.l402.core.credential;

import com.greenharborlabs.l402.core.protocol.L402Credential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CredentialCacheEvictionListener")
class CredentialCacheEvictionListenerTest {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Simple recording stub that captures revoke calls.
     */
    private static final class RecordingCredentialStore implements CredentialStore {
        private final List<String> revokedTokenIds = new ArrayList<>();

        @Override
        public void store(String tokenId, L402Credential credential, long ttlSeconds) {
            // not needed for these tests
        }

        @Override
        public L402Credential get(String tokenId) {
            return null;
        }

        @Override
        public void revoke(String tokenId) {
            revokedTokenIds.add(tokenId);
        }

        @Override
        public long activeCount() {
            return 0;
        }

        List<String> revokedTokenIds() {
            return revokedTokenIds;
        }
    }

    @Test
    @DisplayName("revoke called on credential store with hex-encoded keyId")
    void revokeCalledWithHexEncodedKeyId() {
        var store = new RecordingCredentialStore();
        var listener = new CredentialCacheEvictionListener(store);

        byte[] keyId = new byte[]{0x0a, 0x1b, 0x2c};
        listener.onRootKeyRevoked(keyId);

        assertThat(store.revokedTokenIds()).containsExactly("0a1b2c");
    }

    @Test
    @DisplayName("correct hex encoding via round-trip with known bytes")
    void correctHexEncodingRoundTrip() {
        var store = new RecordingCredentialStore();
        var listener = new CredentialCacheEvictionListener(store);

        // 32-byte key ID simulating a real token ID
        byte[] keyId = new byte[32];
        for (int i = 0; i < keyId.length; i++) {
            keyId[i] = (byte) (i * 7 + 3);
        }

        listener.onRootKeyRevoked(keyId);

        String expected = HEX.formatHex(keyId);
        assertThat(store.revokedTokenIds()).containsExactly(expected);

        // Verify round-trip: parsing the hex back yields the original bytes
        byte[] roundTripped = HEX.parseHex(store.revokedTokenIds().getFirst());
        assertThat(roundTripped).isEqualTo(keyId);
    }

    @Test
    @DisplayName("revoke called even for keyId not present in store (no-op is fine)")
    void revokeCalledForUnknownKeyId() {
        var store = new RecordingCredentialStore();
        var listener = new CredentialCacheEvictionListener(store);

        // This keyId has never been stored — revoke should still be called without error
        byte[] unknownKeyId = new byte[]{(byte) 0xff, (byte) 0xee, (byte) 0xdd};
        listener.onRootKeyRevoked(unknownKeyId);

        assertThat(store.revokedTokenIds()).containsExactly("ffeedd");
    }

    @Test
    @DisplayName("constructor rejects null credential store")
    void constructorRejectsNullCredentialStore() {
        assertThatThrownBy(() -> new CredentialCacheEvictionListener(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("credentialStore");
    }
}
