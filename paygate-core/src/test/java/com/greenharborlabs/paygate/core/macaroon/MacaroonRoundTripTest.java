package com.greenharborlabs.paygate.core.macaroon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacaroonRoundTripTest {

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

    @BeforeEach
    void setUp() {
        identifier = new MacaroonIdentifier(0, PAYMENT_HASH, TOKEN_ID);
    }

    @Nested
    @DisplayName("round-trip: mint -> serialize -> deserialize")
    class RoundTrip {

        @Test
        @DisplayName("no caveats: all fields preserved through round-trip")
        void noCaveatsRoundTrip() {
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, null, List.of());

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            Macaroon restored = MacaroonSerializer.deserializeV2(serialized);

            assertThat(restored.identifier()).isEqualTo(original.identifier());
            assertThat(restored.location()).isEqualTo(original.location());
            assertThat(restored.caveats()).isEqualTo(original.caveats());
            assertThat(restored.signature()).isEqualTo(original.signature());
        }

        @Test
        @DisplayName("with location: location string preserved through round-trip")
        void withLocationRoundTrip() {
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            Macaroon restored = MacaroonSerializer.deserializeV2(serialized);

            assertThat(restored.location()).isEqualTo(LOCATION);
            assertThat(restored.identifier()).isEqualTo(original.identifier());
            assertThat(restored.signature()).isEqualTo(original.signature());
        }

        @Test
        @DisplayName("with single caveat: caveat preserved through round-trip")
        void singleCaveatRoundTrip() {
            Caveat caveat = new Caveat("services", "my_api:0");
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(caveat));

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            Macaroon restored = MacaroonSerializer.deserializeV2(serialized);

            assertThat(restored.caveats()).containsExactly(caveat);
            assertThat(restored.identifier()).isEqualTo(original.identifier());
            assertThat(restored.location()).isEqualTo(original.location());
            assertThat(restored.signature()).isEqualTo(original.signature());
        }

        @Test
        @DisplayName("with multiple caveats: all caveats preserved in order")
        void multipleCaveatsRoundTrip() {
            Caveat caveat1 = new Caveat("services", "my_api:0");
            Caveat caveat2 = new Caveat("my_api_valid_until", "1735689600");
            Caveat caveat3 = new Caveat("tier", "premium");
            List<Caveat> caveats = List.of(caveat1, caveat2, caveat3);

            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, caveats);

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            Macaroon restored = MacaroonSerializer.deserializeV2(serialized);

            assertThat(restored.caveats()).containsExactly(caveat1, caveat2, caveat3);
            assertThat(restored.identifier()).isEqualTo(original.identifier());
            assertThat(restored.location()).isEqualTo(original.location());
            assertThat(restored.signature()).isEqualTo(original.signature());
        }

        @Test
        @DisplayName("full pipeline: mint -> serialize -> deserialize -> verify succeeds")
        void fullPipelineVerifySucceeds() {
            Caveat caveat = new Caveat("services", "my_api:0");
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(caveat));

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            Macaroon restored = MacaroonSerializer.deserializeV2(serialized);

            // Should not throw — valid macaroon with correct root key and matching caveat verifier
            MacaroonVerifier.verify(restored, ROOT_KEY, List.of(acceptingVerifier("services")), new L402VerificationContext());
        }

        @Test
        @DisplayName("tamper detection: flipping a byte causes verification failure")
        void tamperDetection() {
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            byte[] serialized = MacaroonSerializer.serializeV2(original);

            // Flip a byte in the middle of the serialized data
            byte[] tampered = serialized.clone();
            int tamperIndex = tampered.length / 2;
            tampered[tamperIndex] = (byte) (tampered[tamperIndex] ^ 0xFF);

            Macaroon restored = MacaroonSerializer.deserializeV2(tampered);

            assertThatThrownBy(() ->
                    MacaroonVerifier.verify(restored, ROOT_KEY, List.of(), new L402VerificationContext())
            ).isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("base64 encoding: standard base64 with padding")
    class Base64Encoding {

        @Test
        @DisplayName("serialize -> base64 encode -> base64 decode -> deserialize preserves all fields")
        void base64RoundTrip() {
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            String encoded = Base64.getEncoder().encodeToString(serialized);
            byte[] decoded = Base64.getDecoder().decode(encoded);
            Macaroon restored = MacaroonSerializer.deserializeV2(decoded);

            assertThat(restored.identifier()).isEqualTo(original.identifier());
            assertThat(restored.location()).isEqualTo(original.location());
            assertThat(restored.caveats()).isEqualTo(original.caveats());
            assertThat(restored.signature()).isEqualTo(original.signature());
        }

        @Test
        @DisplayName("base64 output uses padding characters when needed")
        void base64UsesPadding() {
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            String encoded = Base64.getEncoder().encodeToString(serialized);

            // Standard base64 pads to a multiple of 4 characters.
            // If the serialized length mod 3 is not 0, padding ('=') must be present.
            if (serialized.length % 3 != 0) {
                assertThat(encoded).endsWith("=");
            }
            // Regardless of padding need, length must be a multiple of 4
            assertThat(encoded.length() % 4).isZero();
        }

        @Test
        @DisplayName("base64 output uses standard alphabet, NOT base64url")
        void base64UsesStandardAlphabetNotUrl() {
            // Use a root key and identifier that are likely to produce '+' or '/' in base64.
            // We generate multiple macaroons with varying keys to increase the chance of
            // encountering these characters, then verify none use base64url replacements
            // exclusively — i.e., the encoding is from Base64.getEncoder(), not getUrlEncoder().
            byte[] testRootKey = new byte[32];
            Arrays.fill(testRootKey, (byte) 0xFF);

            Macaroon macaroon = MacaroonMinter.mint(testRootKey, identifier, LOCATION, List.of());
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String encoded = Base64.getEncoder().encodeToString(serialized);

            // Standard base64 uses '+' and '/' whereas base64url uses '-' and '_'.
            // Verify that the encoded output is valid standard base64 by re-encoding with
            // standard encoder and confirming it matches.
            String reEncoded = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(encoded));
            assertThat(encoded).isEqualTo(reEncoded);

            // Also verify the encoded string does NOT match base64url encoding
            // if it contains '+' or '/' characters.
            if (encoded.contains("+") || encoded.contains("/")) {
                String urlEncoded = Base64.getUrlEncoder().encodeToString(serialized);
                assertThat(encoded).isNotEqualTo(urlEncoded);
            }

            // Verify the output only contains valid standard base64 characters
            assertThat(encoded).matches("[A-Za-z0-9+/=]+");
        }

        @Test
        @DisplayName("full pipeline through base64: mint -> serialize -> base64 -> decode -> deserialize -> verify")
        void fullPipelineThroughBase64() {
            Caveat caveat1 = new Caveat("services", "my_api:0");
            Caveat caveat2 = new Caveat("my_api_valid_until", "1735689600");
            Macaroon original = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(caveat1, caveat2));

            byte[] serialized = MacaroonSerializer.serializeV2(original);
            String encoded = Base64.getEncoder().encodeToString(serialized);
            byte[] decoded = Base64.getDecoder().decode(encoded);
            Macaroon restored = MacaroonSerializer.deserializeV2(decoded);

            // Verify should not throw — macaroon is intact through base64 round-trip
            MacaroonVerifier.verify(restored, ROOT_KEY, List.of(acceptingVerifier("services"), acceptingVerifier("my_api_valid_until")), new L402VerificationContext());
        }
    }

    @Nested
    @DisplayName("equals() — indirectly exercises MacaroonCrypto.constantTimeEquals")
    class Equality {

        @Test
        @DisplayName("identical macaroons are equal")
        void identicalMacaroonsAreEqual() {
            Macaroon a = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(new Caveat("service", "test")));
            Macaroon b = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(new Caveat("service", "test")));
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different signature → not equal")
        void differentSignatureMeansNotEqual() {
            byte[] otherRootKey = new byte[32];
            Arrays.fill(otherRootKey, (byte) 0x99);
            Macaroon a = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());
            Macaroon b = MacaroonMinter.mint(otherRootKey, identifier, LOCATION, List.of());
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different identifier → not equal")
        void differentIdentifierMeansNotEqual() {
            byte[] otherPaymentHash = new byte[32];
            Arrays.fill(otherPaymentHash, (byte) 0xCC);
            MacaroonIdentifier otherId = new MacaroonIdentifier(0, otherPaymentHash, TOKEN_ID);
            Macaroon a = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());
            Macaroon b = MacaroonMinter.mint(ROOT_KEY, otherId, LOCATION, List.of());
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different caveats → not equal")
        void differentCaveatsMeansNotEqual() {
            Macaroon a = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(new Caveat("service", "alpha")));
            Macaroon b = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of(new Caveat("service", "beta")));
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different location → not equal")
        void differentLocationMeansNotEqual() {
            Macaroon a = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());
            Macaroon b = MacaroonMinter.mint(ROOT_KEY, identifier, "https://other.example.com", List.of());
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("same instance is equal to itself")
        void sameInstanceIsEqual() {
            Macaroon a = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());
            assertThat(a).isEqualTo(a);
        }

        @Test
        @DisplayName("not equal to null or different type")
        void notEqualToNullOrDifferentType() {
            Macaroon a = MacaroonMinter.mint(ROOT_KEY, identifier, LOCATION, List.of());
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("not a macaroon");
        }
    }

    private static CaveatVerifier acceptingVerifier(String key) {
        return new CaveatVerifier() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public void verify(Caveat caveat, L402VerificationContext ctx) {
                // Accept unconditionally
            }
        };
    }
}
