package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.SensitiveBytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the L402 validation ordering invariant: preimage (proof-of-payment) is always
 * checked before macaroon signature verification, on both fresh and cached paths. This
 * prevents oracle attacks where an adversary without proof-of-payment can probe macaroon
 * validity through differential error responses.
 */
@DisplayName("oracle attack prevention")
class OraclePreventionTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SERVICE_NAME = "test-api";

    private byte[] rootKey;
    private byte[] preimageBytes;
    private byte[] paymentHash;
    private byte[] tokenIdBytes;
    private String tokenIdHex;
    private MacaroonIdentifier identifier;
    private Macaroon macaroon;

    /** Simple in-memory RootKeyStore backed by a map keyed on hex tokenId. */
    private final Map<String, byte[]> rootKeyMap = new HashMap<>();
    private final RootKeyStore rootKeyStore = new RootKeyStore() {
        @Override
        public GenerationResult generateRootKey() {
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            byte[] tokenId = new byte[32];
            RANDOM.nextBytes(tokenId);
            return new GenerationResult(new SensitiveBytes(key.clone()), tokenId);
        }

        @Override
        public SensitiveBytes getRootKey(byte[] keyId) {
            byte[] stored = rootKeyMap.get(HEX.formatHex(keyId));
            return stored == null ? null : new SensitiveBytes(stored.clone());
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            rootKeyMap.remove(HEX.formatHex(keyId));
        }
    };

    /** Simple in-memory CredentialStore backed by a map keyed on tokenId. */
    private final Map<String, L402Credential> credentialMap = new HashMap<>();
    private final CredentialStore credentialStore = new CredentialStore() {
        @Override
        public void store(String tokenId, L402Credential credential, long ttlSeconds) {
            credentialMap.put(tokenId, credential);
        }

        @Override
        public L402Credential get(String tokenId) {
            return credentialMap.get(tokenId);
        }

        @Override
        public void revoke(String tokenId) {
            credentialMap.remove(tokenId);
        }

        @Override
        public long activeCount() {
            return credentialMap.size();
        }
    };

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        rootKeyMap.clear();
        credentialMap.clear();

        rootKey = new byte[32];
        RANDOM.nextBytes(rootKey);

        preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

        tokenIdBytes = new byte[32];
        RANDOM.nextBytes(tokenIdBytes);
        tokenIdHex = HEX.formatHex(tokenIdBytes);

        identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());

        // Register the root key so the validator can look it up by tokenId
        rootKeyMap.put(tokenIdHex, rootKey);
    }

    /** Builds an L402 Authorization header from a macaroon and preimage hex. */
    private String buildHeader(Macaroon mac, String preimageHex) {
        byte[] serialized = MacaroonSerializer.serializeV2(mac);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        return "L402 " + macaroonBase64 + ":" + preimageHex;
    }

    /** Generates a random 32-byte preimage hex that does NOT match the payment hash. */
    private String wrongPreimageHex() {
        byte[] wrongPreimage = new byte[32];
        RANDOM.nextBytes(wrongPreimage);
        return HEX.formatHex(wrongPreimage);
    }

    @Nested
    @DisplayName("fresh path — preimage checked before root key lookup")
    class FreshPath {

        @Test
        @DisplayName("root key store is not consulted when preimage is wrong, proving preimage is checked first")
        void rootKeyStoreNotConsultedOnPreimageFailure() {
            // Spy RootKeyStore that records whether getRootKey() was ever called
            AtomicBoolean getRootKeyCalled = new AtomicBoolean(false);
            RootKeyStore spyStore = new RootKeyStore() {
                @Override
                public GenerationResult generateRootKey() {
                    return rootKeyStore.generateRootKey();
                }

                @Override
                public SensitiveBytes getRootKey(byte[] keyId) {
                    getRootKeyCalled.set(true);
                    return rootKeyStore.getRootKey(keyId);
                }

                @Override
                public void revokeRootKey(byte[] keyId) {
                    rootKeyStore.revokeRootKey(keyId);
                }
            };

            String header = buildHeader(macaroon, wrongPreimageHex());

            L402Validator validator = new L402Validator(
                    spyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
                    });

            // The root key store must never have been consulted — preimage failed first
            assertThat(getRootKeyCalled.get())
                    .as("getRootKey() should not be called when preimage fails")
                    .isFalse();
        }

        @Test
        @DisplayName("same error code regardless of macaroon validity — no information leakage about signature")
        void sameErrorCodeRegardlessOfMacaroonValidity() {
            // (a) Valid macaroon + wrong preimage
            String wrongPreimage = wrongPreimageHex();
            String headerValidMac = buildHeader(macaroon, wrongPreimage);

            // (b) Tampered macaroon (minted with a DIFFERENT root key, same identifier) + wrong preimage
            byte[] differentRootKey = new byte[32];
            RANDOM.nextBytes(differentRootKey);
            Macaroon tamperedMacaroon = MacaroonMinter.mint(
                    differentRootKey, identifier, "https://example.com", List.of());
            String headerTamperedMac = buildHeader(tamperedMacaroon, wrongPreimage);

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            // Both must fail with INVALID_PREIMAGE — an attacker cannot distinguish
            // whether the macaroon signature was valid or not
            L402Exception[] exceptions = new L402Exception[2];
            assertThatThrownBy(() -> validator.validate(headerValidMac))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> exceptions[0] = (L402Exception) ex);

            assertThatThrownBy(() -> validator.validate(headerTamperedMac))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> exceptions[1] = (L402Exception) ex);

            assertThat(exceptions[0].getErrorCode())
                    .as("valid macaroon + wrong preimage should yield INVALID_PREIMAGE")
                    .isEqualTo(ErrorCode.INVALID_PREIMAGE);
            assertThat(exceptions[1].getErrorCode())
                    .as("tampered macaroon + wrong preimage should yield INVALID_PREIMAGE")
                    .isEqualTo(ErrorCode.INVALID_PREIMAGE);
            assertThat(exceptions[0].getErrorCode())
                    .as("error codes must be identical — no information leakage")
                    .isEqualTo(exceptions[1].getErrorCode());
        }
    }

    @Nested
    @DisplayName("cached path — preimage checked before signature comparison")
    class CachedPath {

        @Test
        @DisplayName("preimage is checked before signature when credential is cached")
        void preimageCheckedBeforeSignatureOnCachedPath() {
            // Pre-populate cache with the legitimate credential
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Submit correct macaroon signature + wrong preimage
            String header = buildHeader(macaroon, wrongPreimageHex());

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        // Must be INVALID_PREIMAGE, not INVALID_MACAROON —
                        // proves preimage was checked before signature comparison
                        assertThat(l402Ex.getErrorCode())
                                .as("cached path must check preimage before signature")
                                .isEqualTo(ErrorCode.INVALID_PREIMAGE);
                    });
        }

        @Test
        @DisplayName("wrong preimage does not reveal whether signature is valid or tampered")
        void wrongPreimageDoesNotRevealSignatureValidity() {
            // Pre-populate cache with the legitimate credential
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            String wrongPreimage = wrongPreimageHex();

            // (a) Correct signature + wrong preimage
            String headerCorrectSig = buildHeader(macaroon, wrongPreimage);

            // (b) Tampered signature + wrong preimage — flip the last byte of serialized macaroon
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            serialized[serialized.length - 1] = (byte) (serialized[serialized.length - 1] ^ 0xFF);
            String tamperedBase64 = Base64.getEncoder().encodeToString(serialized);
            String headerTamperedSig = "L402 " + tamperedBase64 + ":" + wrongPreimage;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            // Both must fail with INVALID_PREIMAGE — an attacker cannot determine
            // whether their tampered signature would have been accepted
            L402Exception[] exceptions = new L402Exception[2];
            assertThatThrownBy(() -> validator.validate(headerCorrectSig))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> exceptions[0] = (L402Exception) ex);

            assertThatThrownBy(() -> validator.validate(headerTamperedSig))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> exceptions[1] = (L402Exception) ex);

            assertThat(exceptions[0].getErrorCode())
                    .as("correct signature + wrong preimage should yield INVALID_PREIMAGE")
                    .isEqualTo(ErrorCode.INVALID_PREIMAGE);
            assertThat(exceptions[1].getErrorCode())
                    .as("tampered signature + wrong preimage should yield INVALID_PREIMAGE")
                    .isEqualTo(ErrorCode.INVALID_PREIMAGE);
            assertThat(exceptions[0].getErrorCode())
                    .as("error codes must be identical — no signature oracle")
                    .isEqualTo(exceptions[1].getErrorCode());
        }
    }
}
