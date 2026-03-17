package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacaroonMinterTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final byte[] PAYMENT_HASH = new byte[32];
    private static final byte[] TOKEN_ID = new byte[32];
    private static final String LOCATION = "https://api.example.com";

    static {
        Arrays.fill(ROOT_KEY, (byte) 0x01);
        Arrays.fill(PAYMENT_HASH, (byte) 0xAA);
        Arrays.fill(TOKEN_ID, (byte) 0xBB);
    }

    private MacaroonIdentifier identifier;
    private byte[] identifierBytes;
    private byte[] derivedKey;

    @BeforeEach
    void setUp() {
        identifier = new MacaroonIdentifier(0, PAYMENT_HASH, TOKEN_ID);
        identifierBytes = MacaroonIdentifier.encode(identifier);
        derivedKey = MacaroonCrypto.deriveKey(ROOT_KEY);
    }

    @Nested
    @DisplayName("mint with no caveats")
    class MintNoCaveats {

        @Test
        @DisplayName("signature matches HMAC-SHA256(derivedKey, identifierBytes)")
        void signatureMatchesExpected() {
            byte[] expectedSig = MacaroonCrypto.hmac(derivedKey, identifierBytes);

            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            assertThat(macaroon.signature()).isEqualTo(expectedSig);
        }
    }

    @Nested
    @DisplayName("mint with one caveat")
    class MintOneCaveat {

        @Test
        @DisplayName("signature is HMAC chain through single caveat")
        void signatureMatchesSingleCaveatChain() {
            Caveat caveat = new Caveat("services", "my_api:0");
            byte[] sig0 = MacaroonCrypto.hmac(derivedKey, identifierBytes);
            byte[] expectedSig = MacaroonCrypto.hmac(sig0, "services=my_api:0".getBytes(StandardCharsets.UTF_8));

            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(caveat));

            assertThat(macaroon.signature()).isEqualTo(expectedSig);
        }
    }

    @Nested
    @DisplayName("mint with multiple caveats")
    class MintMultipleCaveats {

        @Test
        @DisplayName("signature is HMAC chain through all caveats in order")
        void signatureMatchesMultiCaveatChain() {
            Caveat caveat1 = new Caveat("services", "my_api:0");
            Caveat caveat2 = new Caveat("my_api_valid_until", "1735689600");

            byte[] sig0 = MacaroonCrypto.hmac(derivedKey, identifierBytes);
            byte[] sig1 = MacaroonCrypto.hmac(sig0, "services=my_api:0".getBytes(StandardCharsets.UTF_8));
            byte[] expectedSig = MacaroonCrypto.hmac(sig1, "my_api_valid_until=1735689600".getBytes(StandardCharsets.UTF_8));

            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(caveat1, caveat2));

            assertThat(macaroon.signature()).isEqualTo(expectedSig);
        }
    }

    @Nested
    @DisplayName("minted macaroon fields")
    class MintedFields {

        @Test
        @DisplayName("identifier matches encoded MacaroonIdentifier")
        void identifierIsSetCorrectly() {
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            assertThat(macaroon.identifier()).isEqualTo(identifierBytes);
        }

        @Test
        @DisplayName("location matches input")
        void locationIsSet() {
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            assertThat(macaroon.location()).isEqualTo(LOCATION);
        }

        @Test
        @DisplayName("caveats list matches input")
        void caveatsListMatchesInput() {
            Caveat caveat1 = new Caveat("services", "my_api:0");
            Caveat caveat2 = new Caveat("my_api_valid_until", "1735689600");
            List<Caveat> caveats = List.of(caveat1, caveat2);

            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, caveats);

            assertThat(macaroon.caveats()).containsExactly(caveat1, caveat2);
        }

        @Test
        @DisplayName("null location is preserved")
        void nullLocationIsPreserved() {
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, List.of());

            assertThat(macaroon.location()).isNull();
        }
    }

    @Nested
    @DisplayName("sig zeroization")
    class SigZeroization {

        @Test
        @DisplayName("macaroon signature is correct after mint — proves clone preserved value before zeroization")
        void signaturePreservedAfterSigZeroization() {
            Caveat caveat1 = new Caveat("services", "my_api:0");
            Caveat caveat2 = new Caveat("my_api_valid_until", "1735689600");
            List<Caveat> caveats = List.of(caveat1, caveat2);

            // Independently compute the expected final signature
            byte[] sig = MacaroonCrypto.hmac(derivedKey, identifierBytes);
            for (Caveat caveat : caveats) {
                sig = MacaroonCrypto.hmac(sig, caveat.toString().getBytes(StandardCharsets.UTF_8));
            }
            byte[] expectedSig = sig;

            // Mint the macaroon — internally, sig is zeroized in finally block
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, caveats);

            // The macaroon's signature must match, proving the Macaroon constructor
            // cloned the sig array before MacaroonMinter zeroized it
            assertThat(macaroon.signature()).isEqualTo(expectedSig);
        }

        @Test
        @DisplayName("macaroon signature correct with no caveats — sig zeroization does not corrupt result")
        void signaturePreservedNoCaveats() {
            byte[] expectedSig = MacaroonCrypto.hmac(derivedKey, identifierBytes);

            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            assertThat(macaroon.signature()).isEqualTo(expectedSig);
        }
    }

    @Nested
    @DisplayName("null argument validation")
    class NullValidation {

        @Test
        @DisplayName("null rootKey throws NullPointerException or IllegalArgumentException")
        void nullRootKeyThrows() {
            assertThatThrownBy(() -> MacaroonMinter.mint(null, identifier, LOCATION, List.of()))
                    .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null identifier throws NullPointerException or IllegalArgumentException")
        void nullIdentifierThrows() {
            assertThatThrownBy(() -> MacaroonMinter.mint(ROOT_KEY, null, LOCATION, List.of()))
                    .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null caveats list throws NullPointerException or IllegalArgumentException")
        void nullCaveatsThrows() {
            assertThatThrownBy(() -> MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, null))
                    .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
        }
    }
}
