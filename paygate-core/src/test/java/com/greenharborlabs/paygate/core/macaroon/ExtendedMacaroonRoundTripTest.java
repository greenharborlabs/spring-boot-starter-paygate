package com.greenharborlabs.paygate.core.macaroon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extended round-trip tests for macaroon serialization/deserialization.
 * Covers complex cases beyond the Go-generated test vectors:
 * 5 caveats, L402-specific identifier layout, with/without location.
 *
 * Each test constructs a macaroon programmatically, computes the correct
 * signature via the HMAC chain, serializes to V2, deserializes back,
 * and verifies all fields and the signature chain independently.
 */
@DisplayName("Extended macaroon round-trip tests")
class ExtendedMacaroonRoundTripTest {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Builds a 66-byte L402 identifier: [version:2 BE][paymentHash:32][tokenId:32].
     */
    private static byte[] buildIdentifier(int version, byte[] paymentHash, byte[] tokenId) {
        assertThat(paymentHash).hasSize(32);
        assertThat(tokenId).hasSize(32);
        byte[] id = new byte[66];
        id[0] = (byte) ((version >> 8) & 0xFF);
        id[1] = (byte) (version & 0xFF);
        System.arraycopy(paymentHash, 0, id, 2, 32);
        System.arraycopy(tokenId, 0, id, 34, 32);
        return id;
    }

    /**
     * Computes the HMAC-SHA256 signature chain: derive key, HMAC identifier, then each caveat.
     */
    private static byte[] computeSignature(byte[] rootKey, byte[] identifier, List<Caveat> caveats) {
        byte[] derivedKey = MacaroonCrypto.deriveKey(rootKey);
        byte[] sig = MacaroonCrypto.hmac(derivedKey, identifier);
        for (Caveat caveat : caveats) {
            sig = MacaroonCrypto.hmac(sig, caveat.toString().getBytes(StandardCharsets.UTF_8));
        }
        return sig;
    }

    /**
     * Verifies that a macaroon round-trips through serialize/deserialize
     * and that the signature chain is independently correct.
     */
    private static void assertRoundTrip(byte[] rootKey, byte[] identifier, String location,
                                        List<Caveat> caveats, byte[] expectedSignature) {
        Macaroon original = new Macaroon(identifier, location, caveats, expectedSignature);

        // Serialize to V2
        byte[] serialized = MacaroonSerializer.serializeV2(original);

        // Deserialize back
        Macaroon deserialized = MacaroonSerializer.deserializeV2(serialized);

        // Verify all fields preserved
        assertThat(HEX.formatHex(deserialized.identifier()))
                .as("identifier")
                .isEqualTo(HEX.formatHex(identifier));
        assertThat(deserialized.location())
                .as("location")
                .isEqualTo(location);
        assertThat(deserialized.caveats())
                .as("caveats")
                .containsExactlyElementsOf(caveats);
        assertThat(HEX.formatHex(deserialized.signature()))
                .as("signature")
                .isEqualTo(HEX.formatHex(expectedSignature));

        // Re-serialize and verify byte-level equality
        byte[] reserialized = MacaroonSerializer.serializeV2(deserialized);
        assertThat(reserialized)
                .as("re-serialized bytes")
                .isEqualTo(serialized);

        // Base64 round-trip
        String base64 = Base64.getEncoder().encodeToString(serialized);
        byte[] fromBase64 = Base64.getDecoder().decode(base64);
        assertThat(fromBase64)
                .as("base64 decode")
                .isEqualTo(serialized);

        // Independent signature chain verification
        byte[] recomputedSig = computeSignature(rootKey, identifier, caveats);
        assertThat(HEX.formatHex(recomputedSig))
                .as("recomputed signature")
                .isEqualTo(HEX.formatHex(expectedSignature));
    }

    @Test
    @DisplayName("Macaroon with 5 caveats round-trips correctly")
    void fiveCaveatsRoundTrip() {
        byte[] rootKey = HEX.parseHex("0505050505050505050505050505050505050505050505050505050505050505");
        byte[] paymentHash = HEX.parseHex("a1a2a3a4a5a6a7a8a9a0b1b2b3b4b5b6b7b8b9b0c1c2c3c4c5c6c7c8c9c0d1d2");
        byte[] tokenId = HEX.parseHex("e1e2e3e4e5e6e7e8e9e0f1f2f3f4f5f6f7f8f9f0010203040506070809101112");
        byte[] identifier = buildIdentifier(0, paymentHash, tokenId);

        List<Caveat> caveats = List.of(
                new Caveat("services", "my_api:0"),
                new Caveat("my_api_valid_until", "1735689600"),
                new Caveat("my_api_capabilities", "read"),
                new Caveat("ip_addr", "192.168.1.1"),
                new Caveat("request_count", "100")
        );

        byte[] signature = computeSignature(rootKey, identifier, caveats);

        assertRoundTrip(rootKey, identifier, null, caveats, signature);
    }

    @Test
    @DisplayName("Macaroon with 5 caveats and location round-trips correctly")
    void fiveCaveatsWithLocationRoundTrip() {
        byte[] rootKey = HEX.parseHex("0606060606060606060606060606060606060606060606060606060606060606");
        byte[] paymentHash = HEX.parseHex("1111111111111111111111111111111111111111111111111111111111111111");
        byte[] tokenId = HEX.parseHex("2222222222222222222222222222222222222222222222222222222222222222");
        byte[] identifier = buildIdentifier(0, paymentHash, tokenId);

        List<Caveat> caveats = List.of(
                new Caveat("services", "premium_api:0"),
                new Caveat("premium_api_valid_until", "1767225600"),
                new Caveat("premium_api_capabilities", "read,write,admin"),
                new Caveat("ip_addr", "10.0.0.1"),
                new Caveat("nonce", "abc123def456")
        );

        byte[] signature = computeSignature(rootKey, identifier, caveats);

        assertRoundTrip(rootKey, identifier, "https://premium.example.com/api", caveats, signature);
    }

    @Test
    @DisplayName("L402-specific macaroon with typical caveats round-trips correctly")
    void l402TypicalCaveatsRoundTrip() {
        // Simulates a real L402 flow: service name, expiry, and capabilities
        byte[] rootKey = HEX.parseHex("0707070707070707070707070707070707070707070707070707070707070707");
        byte[] paymentHash = HEX.parseHex("deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        byte[] tokenId = HEX.parseHex("cafebabecafebabecafebabecafebabecafebabecafebabecafebabecafebabe");
        byte[] identifier = buildIdentifier(0, paymentHash, tokenId);

        List<Caveat> caveats = List.of(
                new Caveat("services", "lightning_data:0"),
                new Caveat("lightning_data_valid_until", "1800000000"),
                new Caveat("lightning_data_capabilities", "node_info,channel_info")
        );

        byte[] signature = computeSignature(rootKey, identifier, caveats);

        assertRoundTrip(rootKey, identifier, "https://api.lightning-data.com", caveats, signature);
    }

    @Test
    @DisplayName("L402 macaroon without location round-trips correctly")
    void l402NoCaveatsNoLocationRoundTrip() {
        // Minimal L402 macaroon: just identifier and signature, no caveats, no location
        byte[] rootKey = HEX.parseHex("0808080808080808080808080808080808080808080808080808080808080808");
        byte[] paymentHash = HEX.parseHex("abcdef01abcdef01abcdef01abcdef01abcdef01abcdef01abcdef01abcdef01");
        byte[] tokenId = HEX.parseHex("fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98fedcba98");
        byte[] identifier = buildIdentifier(0, paymentHash, tokenId);

        List<Caveat> caveats = List.of();

        byte[] signature = computeSignature(rootKey, identifier, caveats);

        assertRoundTrip(rootKey, identifier, null, caveats, signature);
    }

    @Test
    @DisplayName("Identifier version field is preserved through round-trip")
    void identifierVersionPreserved() {
        // Use version 1 (non-zero) to verify version bytes survive round-trip
        byte[] rootKey = HEX.parseHex("0909090909090909090909090909090909090909090909090909090909090909");
        byte[] paymentHash = HEX.parseHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        byte[] tokenId = HEX.parseHex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        byte[] identifier = buildIdentifier(1, paymentHash, tokenId);

        // Verify version bytes in identifier
        assertThat(identifier[0]).as("version high byte").isEqualTo((byte) 0x00);
        assertThat(identifier[1]).as("version low byte").isEqualTo((byte) 0x01);

        List<Caveat> caveats = List.of(
                new Caveat("services", "test_api:0")
        );

        byte[] signature = computeSignature(rootKey, identifier, caveats);

        assertRoundTrip(rootKey, identifier, null, caveats, signature);
    }

    @Test
    @DisplayName("Caveat with special characters round-trips correctly")
    void caveatWithSpecialCharactersRoundTrip() {
        byte[] rootKey = HEX.parseHex("0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a");
        byte[] paymentHash = HEX.parseHex("ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00");
        byte[] tokenId = HEX.parseHex("00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff");
        byte[] identifier = buildIdentifier(0, paymentHash, tokenId);

        List<Caveat> caveats = List.of(
                new Caveat("services", "api_v2:0"),
                new Caveat("path", "/v2/data?format=json&limit=100"),
                new Caveat("user_agent", "L402Client/1.0 (Linux; x86_64)")
        );

        byte[] signature = computeSignature(rootKey, identifier, caveats);

        assertRoundTrip(rootKey, identifier, "https://api.example.com:8443/v2", caveats, signature);
    }
}
