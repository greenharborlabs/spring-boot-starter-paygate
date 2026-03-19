package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.ServicesCaveatVerifier;

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

@DisplayName("CrossServiceTest — T069: macaroon minted for service A rejected by service B")
class CrossServiceTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private InMemoryRootKeyStore rootKeyStore;
    private InMemoryCredentialStore credentialStore;
    private byte[] rootKey;
    private byte[] tokenIdBytes;
    private byte[] preimageBytes;
    private byte[] paymentHash;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        rootKeyStore = new InMemoryRootKeyStore();
        credentialStore = new InMemoryCredentialStore();

        RootKeyStore.GenerationResult genResult = rootKeyStore.generateRootKey();
        rootKey = genResult.rootKey().value();
        tokenIdBytes = genResult.tokenId();

        preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);
    }

    private String buildAuthHeader(List<Caveat> caveats) {
        MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, caveats);
        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        String preimageHex = HEX.formatHex(preimageBytes);
        return "L402 " + macaroonBase64 + ":" + preimageHex;
    }

    @Nested
    @DisplayName("cross-service rejection")
    class CrossServiceRejection {

        @Test
        @DisplayName("macaroon minted for serviceA is rejected when validated by serviceB")
        void macaroonForServiceAIsRejectedByServiceB() {
            String header = buildAuthHeader(List.of(new Caveat("services", "serviceA")));

            List<CaveatVerifier> verifiers = List.of(new ServicesCaveatVerifier());
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, verifiers, "serviceB");

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                    });
        }

        @Test
        @DisplayName("macaroon minted for serviceA is accepted when validated by serviceA")
        void macaroonForServiceAIsAcceptedByServiceA() {
            String header = buildAuthHeader(List.of(new Caveat("services", "serviceA")));

            List<CaveatVerifier> verifiers = List.of(new ServicesCaveatVerifier());
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, verifiers, "serviceA");

            assertThatCode(() -> validator.validate(header)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("macaroon with multi-service caveat rejects unlisted service")
        void multiServiceCaveatRejectsUnlistedService() {
            String header = buildAuthHeader(List.of(new Caveat("services", "serviceA,serviceB")));

            List<CaveatVerifier> verifiers = List.of(new ServicesCaveatVerifier());
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, verifiers, "serviceC");

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(ex -> {
                        L402Exception l402Ex = (L402Exception) ex;
                        assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                    });
        }

        @Test
        @DisplayName("macaroon with multi-service caveat accepts listed service")
        void multiServiceCaveatAcceptsListedService() {
            String header = buildAuthHeader(List.of(new Caveat("services", "serviceA,serviceB")));

            List<CaveatVerifier> verifiers = List.of(new ServicesCaveatVerifier());
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, verifiers, "serviceB");

            assertThatCode(() -> validator.validate(header)).doesNotThrowAnyException();
        }
    }
}
