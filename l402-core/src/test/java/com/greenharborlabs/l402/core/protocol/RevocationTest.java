package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RevocationTest — T070: revoked root key causes REVOKED_CREDENTIAL")
class RevocationTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SERVICE_NAME = "test-service";

    private InMemoryRootKeyStore rootKeyStore;
    private InMemoryCredentialStore credentialStore;
    private byte[] rootKey;
    private byte[] tokenIdBytes;
    private byte[] preimageBytes;
    private byte[] paymentHash;
    private String authHeader;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        rootKeyStore = new InMemoryRootKeyStore();
        credentialStore = new InMemoryCredentialStore();

        rootKey = rootKeyStore.generateRootKey();
        tokenIdBytes = rootKeyStore.getLastGeneratedKeyId();

        preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

        MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, List.of());
        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        String preimageHex = HEX.formatHex(preimageBytes);
        authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;
    }

    @Nested
    @DisplayName("baseline — valid root key")
    class Baseline {

        @Test
        @DisplayName("validation succeeds when root key is present")
        void validationSucceedsWithRootKeyPresent() {
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatCode(() -> validator.validate(authHeader)).doesNotThrowAnyException();

            L402Credential credential = validator.validate(authHeader);
            assertThat(credential).isNotNull();
            assertThat(credential.tokenId()).isEqualTo(HEX.formatHex(tokenIdBytes));
        }
    }

    @Nested
    @DisplayName("revoked root key")
    class RevokedRootKey {

        @Test
        @DisplayName("throws REVOKED_CREDENTIAL after root key is revoked")
        void revokedRootKeyReturnsRevokedCredential() {
            rootKeyStore.revokeRootKey(tokenIdBytes);

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(authHeader))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                        assertThat(l402Ex.getMessage()).contains("No root key found");
                    });
        }

        @Test
        @DisplayName("validation succeeds before revocation but fails after")
        void validBeforeRevocationFailsAfter() {
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            // Succeeds before revocation
            assertThatCode(() -> validator.validate(authHeader)).doesNotThrowAnyException();

            // Revoke the credential from cache so re-validation is forced
            String tokenIdHex = HEX.formatHex(tokenIdBytes);
            credentialStore.revoke(tokenIdHex);

            // Revoke the root key
            rootKeyStore.revokeRootKey(tokenIdBytes);

            // Fails after revocation
            assertThatThrownBy(() -> validator.validate(authHeader))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                    });
        }
    }
}
