package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that exercises the full key material lifecycle end-to-end
 * and verifies zeroization at each stage. Serves as a regression guard for
 * any future changes to sensitive-byte handling.
 */
@DisplayName("Zeroization audit — full key material lifecycle")
class ZeroizationAuditTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    @DisplayName("full lifecycle: generate → mint → verify → close — all secrets zeroized")
    void fullLifecycle_zeroizationAudit() throws Exception {
        // --- references we need to assert on after close ---
        SensitiveBytes generatedRootKey;
        byte[] capturedTokenId;
        SensitiveBytes retrievedKey1;
        SensitiveBytes retrievedKey2;
        PaymentPreimage preimage;
        Macaroon macaroon;

        // --- Phase 1: Generate root key via InMemoryRootKeyStore ---
        var store = new InMemoryRootKeyStore();

        RootKeyStore.GenerationResult genResult = store.generateRootKey();
        generatedRootKey = genResult.rootKey();

        // Capture tokenId before GenerationResult is closed (returns defensive copy)
        capturedTokenId = genResult.tokenId();
        assertThat(capturedTokenId).hasSize(32);
        assertThat(generatedRootKey.isDestroyed()).isFalse();

        // --- Phase 2: Create PaymentPreimage and compute payment hash ---
        byte[] preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        preimage = new PaymentPreimage(preimageBytes);

        byte[] paymentHash = sha256(preimage.value());
        assertThat(paymentHash).hasSize(32);
        assertThat(preimage.matchesHash(paymentHash)).isTrue();

        // --- Phase 3: Build MacaroonIdentifier and mint macaroon ---
        var identifier = new MacaroonIdentifier(0, paymentHash, capturedTokenId);

        var caveat = new Caveat("services", "test-service:0");
        macaroon = MacaroonMinter.mint(
                generatedRootKey.value(), identifier, "https://test.example.com",
                List.of(caveat));

        assertThat(macaroon).isNotNull();
        assertThat(macaroon.caveats()).containsExactly(caveat);

        // --- Phase 4: Retrieve root key from store and verify macaroon ---
        retrievedKey1 = store.getRootKey(capturedTokenId);
        assertThat(retrievedKey1).isNotNull();
        assertThat(retrievedKey1.isDestroyed()).isFalse();

        // Retrieve a second copy to test independent closeability
        retrievedKey2 = store.getRootKey(capturedTokenId);
        assertThat(retrievedKey2).isNotNull();
        assertThat(retrievedKey2.isDestroyed()).isFalse();

        var context = L402VerificationContext.builder()
                .serviceName("test-service")
                .currentTime(Instant.now())
                .requestMetadata(Map.of())
                .build();

        // Verification should succeed — macaroon is valid
        MacaroonVerifier.verify(
                macaroon, retrievedKey1.value(),
                List.of(new ServicesCaveatVerifier(50)), context);

        // --- Phase 5: Close GenerationResult → assert rootKey destroyed, tokenId zeroed ---
        genResult.close();

        assertThat(generatedRootKey.isDestroyed())
                .as("rootKey from GenerationResult must be destroyed after close()")
                .isTrue();
        assertThatThrownBy(generatedRootKey::value)
                .isInstanceOf(IllegalStateException.class);

        // The internal tokenId field should be zeroized. We cannot access the
        // internal field directly, but we can verify via the accessor that the
        // record's stored copy is now all zeros (the accessor returns a copy of
        // the internal field, which was zeroized).
        byte[] tokenIdAfterClose = genResult.tokenId();
        assertThat(tokenIdAfterClose).containsOnly((byte) 0);

        // --- Phase 6: Close retrieved SensitiveBytes independently ---
        // Closing retrievedKey1 should NOT affect retrievedKey2
        retrievedKey1.close();
        assertThat(retrievedKey1.isDestroyed()).isTrue();
        assertThatThrownBy(retrievedKey1::value)
                .isInstanceOf(IllegalStateException.class);

        // retrievedKey2 is still alive — independent copy
        assertThat(retrievedKey2.isDestroyed()).isFalse();
        byte[] key2Value = retrievedKey2.value();
        assertThat(key2Value).hasSize(32);

        retrievedKey2.close();
        assertThat(retrievedKey2.isDestroyed()).isTrue();
        assertThatThrownBy(retrievedKey2::value)
                .isInstanceOf(IllegalStateException.class);

        // --- Phase 7: Close PaymentPreimage → assert value() and matchesHash() throw ISE ---
        preimage.close();

        assertThat(preimage.isDestroyed()).isTrue();
        assertThatThrownBy(preimage::value)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> preimage.matchesHash(paymentHash))
                .isInstanceOf(IllegalStateException.class);

        // --- Phase 8: Close store → assert getRootKey() throws ISE ---
        store.close();

        assertThatThrownBy(() -> store.getRootKey(capturedTokenId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::generateRootKey)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("try-with-resources demonstrates correct lifecycle usage")
    void tryWithResources_correctLifecycleUsage() throws Exception {
        SensitiveBytes capturedRootKey;
        byte[] capturedTokenId;

        var store = new InMemoryRootKeyStore();
        try (store) {

            // Generate and immediately scope the GenerationResult
            try (var genResult = store.generateRootKey()) {
                capturedRootKey = genResult.rootKey();
                capturedTokenId = genResult.tokenId();

                // Create preimage scoped to this block
                byte[] rawPreimage = new byte[32];
                RANDOM.nextBytes(rawPreimage);

                try (var preimage = new PaymentPreimage(rawPreimage)) {
                    byte[] paymentHash = sha256(preimage.value());

                    var identifier = new MacaroonIdentifier(0, paymentHash, capturedTokenId);
                    var caveat = new Caveat("services", "test-service:0");

                    Macaroon macaroon = MacaroonMinter.mint(
                            capturedRootKey.value(), identifier, null, List.of(caveat));

                    // Retrieve key and verify within its own scope
                    try (SensitiveBytes verificationKey = store.getRootKey(capturedTokenId)) {
                        var context = L402VerificationContext.builder()
                                .serviceName("test-service")
                                .currentTime(Instant.now())
                                .build();

                        MacaroonVerifier.verify(
                                macaroon, verificationKey.value(),
                                List.of(new ServicesCaveatVerifier(50)), context);
                    }
                    // verificationKey is destroyed here

                    assertThat(preimage.isDestroyed()).isFalse();
                }
                // preimage is destroyed here

                assertThat(capturedRootKey.isDestroyed()).isFalse();
            }
            // genResult is closed: rootKey destroyed, tokenId zeroized

            assertThat(capturedRootKey.isDestroyed()).isTrue();
        }
        // store is closed: all stored keys zeroized, getRootKey throws ISE

        assertThatThrownBy(() -> store.getRootKey(capturedTokenId))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
