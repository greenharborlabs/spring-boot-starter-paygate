package com.greenharborlabs.paygate.core.protocol;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests L402Credential.parse() with various malformed Authorization headers.
 * Covers T068: empty, wrong scheme, missing colon, bad preimage length, invalid base64.
 */
@DisplayName("MalformedHeader")
class MalformedHeaderTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private String validMacaroonBase64;
    private String validPreimageHex;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        byte[] rootKey = new byte[32];
        RANDOM.nextBytes(rootKey);

        byte[] preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

        byte[] tokenIdBytes = new byte[32];
        RANDOM.nextBytes(tokenIdBytes);

        MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());

        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        validMacaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        validPreimageHex = HEX.formatHex(preimageBytes);
    }

    private void assertMalformedHeader(String header) {
        assertThatThrownBy(() -> L402Credential.parse(header))
                .isInstanceOf(L402Exception.class)
                .satisfies(e -> assertThat(((L402Exception) e).getErrorCode())
                        .isEqualTo(ErrorCode.MALFORMED_HEADER));
    }

    @Nested
    @DisplayName("null or empty header")
    class NullOrEmpty {

        @Test
        @DisplayName("null authorization header throws MALFORMED_HEADER")
        void nullHeader() {
            assertMalformedHeader(null);
        }

        @Test
        @DisplayName("empty string throws MALFORMED_HEADER")
        void emptyHeader() {
            assertMalformedHeader("");
        }
    }

    @Nested
    @DisplayName("wrong scheme")
    class WrongScheme {

        @Test
        @DisplayName("Bearer scheme throws MALFORMED_HEADER")
        void bearerScheme() {
            assertMalformedHeader("Bearer token123");
        }

        @Test
        @DisplayName("Basic scheme throws MALFORMED_HEADER")
        void basicScheme() {
            assertMalformedHeader("Basic dXNlcjpwYXNz");
        }
    }

    @Nested
    @DisplayName("missing colon separator")
    class MissingColon {

        @Test
        @DisplayName("L402 token without colon throws MALFORMED_HEADER")
        void noColonSeparator() {
            assertMalformedHeader("L402 abc123");
        }

        @Test
        @DisplayName("LSAT token without colon throws MALFORMED_HEADER")
        void lsatNoColonSeparator() {
            assertMalformedHeader("LSAT abc123");
        }
    }

    @Nested
    @DisplayName("preimage not 64 hex chars")
    class BadPreimageLength {

        @Test
        @DisplayName("short preimage hex throws MALFORMED_HEADER")
        void shortPreimage() {
            assertMalformedHeader("L402 " + validMacaroonBase64 + ":abcd1234");
        }

        @Test
        @DisplayName("preimage of 62 hex chars throws MALFORMED_HEADER")
        void preimage62Chars() {
            String shortHex = validPreimageHex.substring(0, 62);
            assertMalformedHeader("L402 " + validMacaroonBase64 + ":" + shortHex);
        }

        @Test
        @DisplayName("preimage of 66 hex chars throws MALFORMED_HEADER")
        void preimage66Chars() {
            String longHex = validPreimageHex + "ab";
            assertMalformedHeader("L402 " + validMacaroonBase64 + ":" + longHex);
        }
    }

    @Nested
    @DisplayName("invalid base64 macaroon")
    class InvalidBase64 {

        @Test
        @DisplayName("non-base64 characters in macaroon data throws MALFORMED_HEADER")
        void invalidBase64Characters() {
            assertMalformedHeader("L402 !!!invalid-base64!!!:" + validPreimageHex);
        }
    }
}
