package com.greenharborlabs.paygate.core.protocol;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LSAT backward compatibility — L402Credential.parse()")
class LsatCompatibilityTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private byte[] rootKey;
    private byte[] preimageBytes;
    private byte[] paymentHash;
    private byte[] tokenIdBytes;
    private MacaroonIdentifier identifier;
    private Macaroon macaroon;
    private String macaroonBase64;
    private String preimageHex;
    private String tokenIdHex;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        rootKey = new byte[32];
        RANDOM.nextBytes(rootKey);

        preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

        tokenIdBytes = new byte[32];
        RANDOM.nextBytes(tokenIdBytes);

        identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());

        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        preimageHex = HEX.formatHex(preimageBytes);
        tokenIdHex = HEX.formatHex(tokenIdBytes);
    }

    @Test
    @DisplayName("T095: LSAT-prefixed header parses successfully")
    void lsatPrefixParsesSuccessfully() {
        String header = "LSAT " + macaroonBase64 + ":" + preimageHex;

        L402Credential credential = L402Credential.parse(header);

        assertThat(credential).isNotNull();
        assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
        assertThat(credential.preimage().toHex()).isEqualTo(preimageHex);
        assertThat(credential.macaroon().identifier()).isEqualTo(macaroon.identifier());
    }

    @Test
    @DisplayName("T097: LSAT and L402 prefixes produce identical parsed credentials")
    void lsatAndL402ProduceIdenticalCredentials() {
        String lsatHeader = "LSAT " + macaroonBase64 + ":" + preimageHex;
        String l402Header = "L402 " + macaroonBase64 + ":" + preimageHex;

        L402Credential lsatCredential = L402Credential.parse(lsatHeader);
        L402Credential l402Credential = L402Credential.parse(l402Header);

        assertThat(lsatCredential.tokenId()).isEqualTo(l402Credential.tokenId());
        assertThat(lsatCredential.preimage().toHex()).isEqualTo(l402Credential.preimage().toHex());
        assertThat(lsatCredential.macaroon().identifier()).isEqualTo(l402Credential.macaroon().identifier());
    }

    @Test
    @DisplayName("LSAT credential preserves macaroon signature identically to L402")
    void lsatPreservesMacaroonSignature() {
        String lsatHeader = "LSAT " + macaroonBase64 + ":" + preimageHex;
        String l402Header = "L402 " + macaroonBase64 + ":" + preimageHex;

        L402Credential lsatCredential = L402Credential.parse(lsatHeader);
        L402Credential l402Credential = L402Credential.parse(l402Header);

        assertThat(lsatCredential.macaroon().signature())
                .isEqualTo(macaroon.signature())
                .isEqualTo(l402Credential.macaroon().signature());
    }

    @Test
    @DisplayName("LSAT credential preserves macaroon location identically to L402")
    void lsatPreservesMacaroonLocation() {
        String lsatHeader = "LSAT " + macaroonBase64 + ":" + preimageHex;
        String l402Header = "L402 " + macaroonBase64 + ":" + preimageHex;

        L402Credential lsatCredential = L402Credential.parse(lsatHeader);
        L402Credential l402Credential = L402Credential.parse(l402Header);

        assertThat(lsatCredential.macaroon().location())
                .isEqualTo("https://example.com")
                .isEqualTo(l402Credential.macaroon().location());
    }
}
