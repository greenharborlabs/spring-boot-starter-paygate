package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L402Validator")
class L402ValidatorTest {

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
    private String validAuthHeader;

    /** Simple in-memory RootKeyStore backed by a map keyed on hex tokenId. */
    private final Map<String, byte[]> rootKeyMap = new HashMap<>();
    private final RootKeyStore rootKeyStore = new RootKeyStore() {
        @Override
        public GenerationResult generateRootKey() {
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            byte[] tokenId = new byte[32];
            RANDOM.nextBytes(tokenId);
            return new GenerationResult(new com.greenharborlabs.l402.core.macaroon.SensitiveBytes(key.clone()), tokenId);
        }

        @Override
        public com.greenharborlabs.l402.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
            byte[] stored = rootKeyMap.get(HEX.formatHex(keyId));
            return stored == null ? null : new com.greenharborlabs.l402.core.macaroon.SensitiveBytes(stored.clone());
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

        // Build a valid Authorization header: L402 <base64-macaroon>:<hex-preimage>
        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        String preimageHex = HEX.formatHex(preimageBytes);
        validAuthHeader = "L402 " + macaroonBase64 + ":" + preimageHex;
    }

    @Nested
    @DisplayName("valid credential")
    class ValidCredential {

        @Test
        @DisplayName("returns credential when macaroon signature and preimage are valid")
        void validCredentialPasses() {
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            L402Validator.ValidationResult result = validator.validate(validAuthHeader);

            assertThat(result).isNotNull();
            assertThat(result.freshValidation()).isTrue();
            assertThat(result.credential().tokenId()).isEqualTo(tokenIdHex);
            assertThat(result.credential().preimage().toHex()).isEqualTo(HEX.formatHex(preimageBytes));
        }
    }

    @Nested
    @DisplayName("invalid macaroon signature")
    class InvalidMacaroonSignature {

        @Test
        @DisplayName("throws INVALID_MACAROON when macaroon signature is tampered")
        void tamperedSignatureReturnsInvalidMacaroon() {
            // Tamper the macaroon signature by flipping a byte
            byte[] tamperedSig = macaroon.signature();
            tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
            Macaroon tampered = new Macaroon(
                    macaroon.identifier(), macaroon.location(), macaroon.caveats(), tamperedSig);

            byte[] serialized = MacaroonSerializer.serializeV2(tampered);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_MACAROON);
        }
    }

    @Nested
    @DisplayName("wrong preimage")
    class WrongPreimage {

        @Test
        @DisplayName("throws INVALID_PREIMAGE when preimage does not hash to paymentHash")
        void wrongPreimageReturnsInvalidPreimage() {
            // Use a different random preimage that won't match the payment hash
            byte[] wrongPreimage = new byte[32];
            RANDOM.nextBytes(wrongPreimage);
            String wrongPreimageHex = HEX.formatHex(wrongPreimage);

            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String header = "L402 " + macaroonBase64 + ":" + wrongPreimageHex;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PREIMAGE);
        }
    }

    @Nested
    @DisplayName("cached credential")
    class CachedCredential {

        @Test
        @DisplayName("returns cached credential when presented credential matches and caveats are valid")
        void returnsCachedWhenPresentedCredentialMatches() {
            // Pre-populate the credential store with a cached credential
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Root key exists — cache hit should return without full macaroon re-verification
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            L402Validator.ValidationResult result = validator.validate(validAuthHeader);

            assertThat(result.freshValidation()).isFalse();
            assertThat(result.credential()).isSameAs(cached);
        }

        @Test
        @DisplayName("cached credential is not served after credential store revocation")
        void cachedCredentialNotServedAfterCredentialStoreRevocation() {
            // First validate to cache the credential
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);
            validator.validate(validAuthHeader);

            // Revoke via credential store (simulates what revokeRootKey callers should do)
            credentialStore.revoke(tokenIdHex);
            // Also revoke the root key so full re-validation fails
            rootKeyStore.revokeRootKey(tokenIdBytes);

            // Second validate should fall through to full validation and fail
            assertThatThrownBy(() -> validator.validate(validAuthHeader))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                        assertThat(l402Ex.getMessage()).contains("No root key found");
                        assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
                    });
        }

        @Test
        @DisplayName("throws INVALID_MACAROON when presented macaroon signature does not match cached")
        void rejectsCachedCredentialWithTamperedSignature() {
            // Pre-populate the credential store with the legitimate credential
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Build a header with the same tokenId but a tampered macaroon signature
            byte[] tamperedSig = macaroon.signature();
            tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
            Macaroon tampered = new Macaroon(
                    macaroon.identifier(), macaroon.location(), macaroon.caveats(), tamperedSig);

            byte[] serialized = MacaroonSerializer.serializeV2(tampered);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                        assertThat(l402Ex.getMessage()).contains("signature");
                        assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
                    });
        }

        @Test
        @DisplayName("throws INVALID_PREIMAGE when presented preimage does not match cached")
        void rejectsCachedCredentialWithWrongPreimage() {
            // Pre-populate the credential store with the legitimate credential
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Build a header with the correct macaroon but a different preimage.
            // The attacker knows the tokenId (from a response header) but not the real preimage.
            byte[] wrongPreimageBytes = new byte[32];
            RANDOM.nextBytes(wrongPreimageBytes);
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String wrongPreimageHex = HEX.formatHex(wrongPreimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + wrongPreimageHex;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
                        assertThat(l402Ex.getMessage()).contains("preimage");
                        assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
                    });
        }

        @Test
        @DisplayName("throws EXPIRED_CREDENTIAL and revokes cache when cached credential has expired caveat")
        void rejectsCachedCredentialWithExpiredCaveat() {
            // Create a macaroon with a valid_until caveat set in the past
            long pastEpoch = Instant.now().minusSeconds(60).getEpochSecond();
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(pastEpoch))
            );
            Macaroon expiredMacaroon = MacaroonMinter.mint(
                    rootKey, identifier, "https://example.com", caveats);

            // Pre-populate the credential store as if this was cached before expiry
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(expiredMacaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Build header with matching macaroon and preimage (attacker replays a real credential)
            byte[] serialized = MacaroonSerializer.serializeV2(expiredMacaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(validUntilVerifier()), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
                        assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
                    });

            // Credential should be evicted from cache after expiry detection
            assertThat(credentialStore.get(tokenIdHex)).isNull();
        }

        @Test
        @DisplayName("skips unknown caveats in cached credential instead of revoking")
        void skipsUnknownCaveatsInCachedCredential() {
            // Create a macaroon with an unknown caveat
            List<Caveat> caveats = List.of(
                    new Caveat("custom_app_data", "xyz")
            );
            Macaroon macWithUnknown = com.greenharborlabs.l402.core.macaroon.MacaroonMinter.mint(
                    rootKey, identifier, "https://example.com", caveats);

            // Pre-populate the credential store
            com.greenharborlabs.l402.core.lightning.PaymentPreimage preimage =
                    com.greenharborlabs.l402.core.lightning.PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(macWithUnknown, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Build header with matching macaroon
            byte[] serialized = com.greenharborlabs.l402.core.macaroon.MacaroonSerializer.serializeV2(macWithUnknown);
            String macaroonBase64 = java.util.Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            // No verifiers registered — unknown caveat should be skipped, not cause rejection
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            L402Validator.ValidationResult result = validator.validate(header);

            assertThat(result.freshValidation()).isFalse();
            assertThat(result.credential()).isSameAs(cached);
            // Credential should NOT be revoked
            assertThat(credentialStore.get(tokenIdHex)).isNotNull();
        }
    }

    @Nested
    @DisplayName("expired caveat")
    class ExpiredCaveat {

        @Test
        @DisplayName("throws EXPIRED_CREDENTIAL when valid_until caveat is in the past")
        void expiredCaveatReturnsExpiredCredential() throws NoSuchAlgorithmException {
            // Create a macaroon with an expired valid_until caveat
            long pastEpochSeconds = Instant.now().minusSeconds(3600).getEpochSecond();
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(pastEpochSeconds))
            );

            // Re-mint with caveats
            Macaroon macaroonWithExpiry = MacaroonMinter.mint(
                    rootKey, identifier, "https://example.com", caveats);

            byte[] serialized = MacaroonSerializer.serializeV2(macaroonWithExpiry);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            // Create a valid_until caveat verifier that throws EXPIRED_CREDENTIAL for past timestamps
            CaveatVerifier validUntilVerifier = new CaveatVerifier() {
                @Override
                public String getKey() {
                    return SERVICE_NAME + "_valid_until";
                }

                @Override
                public void verify(Caveat caveat, L402VerificationContext context) {
                    long expiryEpoch = Long.parseLong(caveat.value());
                    Instant expiry = Instant.ofEpochSecond(expiryEpoch);
                    if (!expiry.isAfter(context.getCurrentTime())) {
                        throw new L402Exception(
                                ErrorCode.EXPIRED_CREDENTIAL,
                                "Credential expired at " + expiry,
                                null);
                    }
                }
            };

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(validUntilVerifier), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
        }
    }

    @Nested
    @DisplayName("cache TTL derived from valid_until caveat")
    class CacheTtlFromValidUntil {

        @Test
        @DisplayName("uses remaining seconds from valid_until caveat as cache TTL when shorter than default")
        void shortValidUntilGetsShorterCacheTtl() {
            AtomicLong capturedTtl = new AtomicLong(-1);
            CredentialStore ttlCapturingStore = ttlCapturingStore(capturedTtl);

            // Macaroon with valid_until set to 120 seconds from now
            long futureEpoch = Instant.now().plusSeconds(120).getEpochSecond();
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(futureEpoch))
            );

            String header = buildAuthHeader(caveats);

            L402Validator validator = new L402Validator(
                    rootKeyStore, ttlCapturingStore, List.of(validUntilVerifier()), SERVICE_NAME);

            validator.validate(header);

            // TTL should be approximately 90 seconds (120 - 30s safety margin),
            // definitely not the default 3600. Allow a few seconds of tolerance for test execution time.
            assertThat(capturedTtl.get())
                    .isGreaterThan(0)
                    .isLessThanOrEqualTo(90)
                    .isGreaterThanOrEqualTo(85);
        }

        @Test
        @DisplayName("uses default TTL when no valid_until caveat is present")
        void noValidUntilUsesDefaultTtl() {
            AtomicLong capturedTtl = new AtomicLong(-1);
            CredentialStore ttlCapturingStore = ttlCapturingStore(capturedTtl);

            L402Validator validator = new L402Validator(
                    rootKeyStore, ttlCapturingStore, List.of(), SERVICE_NAME);

            validator.validate(validAuthHeader);

            assertThat(capturedTtl.get()).isEqualTo(3600);
        }

        @Test
        @DisplayName("uses minimum of multiple valid_until caveats as cache TTL")
        void multipleValidUntilUsesMinimum() {
            AtomicLong capturedTtl = new AtomicLong(-1);
            CredentialStore ttlCapturingStore = ttlCapturingStore(capturedTtl);

            // Two valid_until caveats: 120s and 60s from now — TTL should be ~60s
            long laterEpoch = Instant.now().plusSeconds(120).getEpochSecond();
            long soonerEpoch = Instant.now().plusSeconds(60).getEpochSecond();
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(laterEpoch)),
                    new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(soonerEpoch))
            );

            String header = buildAuthHeader(caveats);

            L402Validator validator = new L402Validator(
                    rootKeyStore, ttlCapturingStore, List.of(validUntilVerifier()), SERVICE_NAME);

            validator.validate(header);

            // TTL should be approximately 30 seconds (minimum 60 - 30s safety margin),
            // not 120 or the default 3600.
            assertThat(capturedTtl.get())
                    .isGreaterThan(0)
                    .isLessThanOrEqualTo(30)
                    .isGreaterThanOrEqualTo(25);
        }
    }

    @Nested
    @DisplayName("root key lifecycle")
    class RootKeyLifecycle {

        @Test
        @DisplayName("root key SensitiveBytes is destroyed after successful validation")
        void rootKeyIsDestroyedAfterSuccessfulValidation() {
            var issuedKeys = new java.util.concurrent.ConcurrentLinkedQueue<com.greenharborlabs.l402.core.macaroon.SensitiveBytes>();
            RootKeyStore trackingStore = new RootKeyStore() {
                @Override
                public GenerationResult generateRootKey() {
                    return rootKeyStore.generateRootKey();
                }

                @Override
                public com.greenharborlabs.l402.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
                    var sb = rootKeyStore.getRootKey(keyId);
                    if (sb != null) issuedKeys.add(sb);
                    return sb;
                }

                @Override
                public void revokeRootKey(byte[] keyId) {
                    rootKeyStore.revokeRootKey(keyId);
                }
            };

            L402Validator validator = new L402Validator(
                    trackingStore, credentialStore, List.of(), SERVICE_NAME);
            validator.validate(validAuthHeader);

            assertThat(issuedKeys).hasSize(1);
            assertThat(issuedKeys.peek().isDestroyed()).isTrue();
        }

        @Test
        @DisplayName("root key SensitiveBytes is destroyed after failed validation")
        void rootKeyIsDestroyedAfterFailedValidation() {
            // Tamper the macaroon signature so verification fails
            byte[] tamperedSig = macaroon.signature();
            tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
            Macaroon tampered = new Macaroon(
                    macaroon.identifier(), macaroon.location(), macaroon.caveats(), tamperedSig);
            byte[] serialized = MacaroonSerializer.serializeV2(tampered);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            var issuedKeys = new java.util.concurrent.ConcurrentLinkedQueue<com.greenharborlabs.l402.core.macaroon.SensitiveBytes>();
            RootKeyStore trackingStore = new RootKeyStore() {
                @Override
                public GenerationResult generateRootKey() {
                    return rootKeyStore.generateRootKey();
                }

                @Override
                public com.greenharborlabs.l402.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
                    var sb = rootKeyStore.getRootKey(keyId);
                    if (sb != null) issuedKeys.add(sb);
                    return sb;
                }

                @Override
                public void revokeRootKey(byte[] keyId) {
                    rootKeyStore.revokeRootKey(keyId);
                }
            };

            L402Validator validator = new L402Validator(
                    trackingStore, credentialStore, List.of(), SERVICE_NAME);

            try {
                validator.validate(header);
            } catch (L402Exception expected) {
                // Expected — tampered signature
            }

            assertThat(issuedKeys).hasSize(1);
            assertThat(issuedKeys.peek().isDestroyed()).isTrue();
        }
    }

    @Nested
    @DisplayName("validate with external context")
    class ValidateWithExternalContext {

        @Test
        @DisplayName("cached credential with escalating caveats is rejected and revoked")
        void cachedEscalatingCaveatsRejectedAndRevoked() {
            // Create a macaroon with two capabilities caveats where the second EXPANDS access
            // (escalation: "search" -> "search,analyze,admin"), which should be detected
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "search"),
                    new Caveat(SERVICE_NAME + "_capabilities", "search,analyze,admin")
            );
            Macaroon escalatingMacaroon = MacaroonMinter.mint(
                    rootKey, identifier, "https://example.com", caveats);

            // Pre-populate the credential store as if this was cached
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(escalatingMacaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Build header with matching macaroon
            byte[] serialized = MacaroonSerializer.serializeV2(escalatingMacaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            CapabilitiesCaveatVerifier capVerifier = new CapabilitiesCaveatVerifier(SERVICE_NAME);
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(capVerifier), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                        assertThat(l402Ex.getMessage()).containsIgnoringCase("caveat escalation");
                        assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
                    });

            // Credential should be revoked from cache
            assertThat(credentialStore.get(tokenIdHex)).isNull();
        }

        @Test
        @DisplayName("validate with context enforces requested capability — matching capability passes")
        void contextWithMatchingCapabilityPasses() {
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "search,analyze")
            );
            String header = buildAuthHeader(caveats);

            CapabilitiesCaveatVerifier capVerifier = new CapabilitiesCaveatVerifier(SERVICE_NAME);
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(capVerifier), SERVICE_NAME);

            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(Instant.now())
                    .requestedCapability("search")
                    .build();

            L402Validator.ValidationResult result = validator.validate(header, context);

            assertThat(result).isNotNull();
            assertThat(result.freshValidation()).isTrue();
        }

        @Test
        @DisplayName("validate with context enforces requested capability — missing capability fails")
        void contextWithMissingCapabilityFails() {
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_capabilities", "search,analyze")
            );
            String header = buildAuthHeader(caveats);

            CapabilitiesCaveatVerifier capVerifier = new CapabilitiesCaveatVerifier(SERVICE_NAME);
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(capVerifier), SERVICE_NAME);

            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(Instant.now())
                    .requestedCapability("admin")
                    .build();

            assertThatThrownBy(() -> validator.validate(header, context))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                        assertThat(l402Ex.getMessage()).contains("admin");
                        assertThat(l402Ex.getTokenId()).isEqualTo(tokenIdHex);
                    });
        }

        @Test
        @DisplayName("validate(String) backward compatible — delegates with default context")
        void validateStringBackwardCompatible() {
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            L402Validator.ValidationResult result = validator.validate(validAuthHeader);

            assertThat(result).isNotNull();
            assertThat(result.freshValidation()).isTrue();
            assertThat(result.credential().tokenId()).isEqualTo(tokenIdHex);
        }

        @Test
        @DisplayName("external context flows through to fresh path verifier")
        void contextFlowsThroughToFreshPath() {
            // A context-capturing verifier that records the context it receives
            AtomicReference<L402VerificationContext> capturedContext = new AtomicReference<>();
            CaveatVerifier capturingVerifier = new CaveatVerifier() {
                @Override
                public String getKey() {
                    return SERVICE_NAME + "_marker";
                }

                @Override
                public void verify(Caveat caveat, L402VerificationContext ctx) {
                    capturedContext.set(ctx);
                }
            };

            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_marker", "test-value"),
                    new Caveat(SERVICE_NAME + "_capabilities", "custom-cap")
            );
            String header = buildAuthHeader(caveats);

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore,
                    List.of(capturingVerifier, new CapabilitiesCaveatVerifier(SERVICE_NAME)),
                    SERVICE_NAME);

            L402VerificationContext externalContext = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(Instant.now())
                    .requestedCapability("custom-cap")
                    .build();

            validator.validate(header, externalContext);

            // The verifier should have received the external context, not a locally-built one
            assertThat(capturedContext.get()).isSameAs(externalContext);
            assertThat(capturedContext.get().getRequestedCapability()).isEqualTo("custom-cap");
        }

        @Test
        @DisplayName("validate with pre-parsed L402HeaderComponents returns fresh ValidationResult")
        void validateWithPreParsedComponentsReturnsFreshResult() {
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            L402HeaderComponents components = L402HeaderComponents.extractOrThrow(validAuthHeader);
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(Instant.now())
                    .build();

            L402Validator.ValidationResult result = validator.validate(components, context);

            assertThat(result).isNotNull();
            assertThat(result.freshValidation()).isTrue();
            assertThat(result.credential().tokenId()).isEqualTo(tokenIdHex);
            assertThat(result.credential().preimage().toHex()).isEqualTo(HEX.formatHex(preimageBytes));
        }
    }

    /** Creates a CredentialStore that captures the TTL passed to store(). */
    private CredentialStore ttlCapturingStore(AtomicLong capturedTtl) {
        return new CredentialStore() {
            private final Map<String, L402Credential> map = new HashMap<>();

            @Override
            public void store(String tokenId, L402Credential credential, long ttlSeconds) {
                capturedTtl.set(ttlSeconds);
                map.put(tokenId, credential);
            }

            @Override
            public L402Credential get(String tokenId) {
                return map.get(tokenId);
            }

            @Override
            public void revoke(String tokenId) {
                map.remove(tokenId);
            }

            @Override
            public long activeCount() {
                return map.size();
            }
        };
    }

    /** Creates a CaveatVerifier for valid_until caveats that rejects expired timestamps. */
    private CaveatVerifier validUntilVerifier() {
        return new CaveatVerifier() {
            @Override
            public String getKey() {
                return SERVICE_NAME + "_valid_until";
            }

            @Override
            public void verify(Caveat caveat, L402VerificationContext context) {
                long expiryEpoch = Long.parseLong(caveat.value());
                Instant expiry = Instant.ofEpochSecond(expiryEpoch);
                if (!expiry.isAfter(context.getCurrentTime())) {
                    throw new L402Exception(
                            ErrorCode.EXPIRED_CREDENTIAL,
                            "Credential expired at " + expiry,
                            null);
                }
            }
        };
    }

    /** Builds an L402 Authorization header from a macaroon minted with the given caveats. */
    private String buildAuthHeader(List<Caveat> caveats) {
        Macaroon mac = MacaroonMinter.mint(rootKey, identifier, "https://example.com", caveats);
        byte[] serialized = MacaroonSerializer.serializeV2(mac);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        String preimageHex = HEX.formatHex(preimageBytes);
        return "L402 " + macaroonBase64 + ":" + preimageHex;
    }
}
