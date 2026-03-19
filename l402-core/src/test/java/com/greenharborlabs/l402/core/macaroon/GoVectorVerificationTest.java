package com.greenharborlabs.l402.core.macaroon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads go-macaroon-vectors.json and verifies that:
 * <ol>
 *   <li>The HMAC signature chain produces the expected signature</li>
 *   <li>The V2 binary serialization matches the expected bytes</li>
 *   <li>The base64 encoding matches the expected string</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Go macaroon interop test vectors")
class GoVectorVerificationTest {

    private static final HexFormat HEX = HexFormat.of();

    record VectorData(
            String description,
            byte[] rootKey,
            byte[] identifier,
            String location,
            List<String> caveatStrings,
            byte[] expectedSignature,
            byte[] expectedSerializedV2,
            String expectedBase64
    ) {
        @Override
        public String toString() {
            return description;
        }
    }

    private List<VectorData> vectors;

    @BeforeAll
    void loadVectors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getClass().getResourceAsStream("/test-vectors/go-macaroon-vectors.json");
        assertThat(is).as("test vectors JSON resource").isNotNull();
        JsonNode root = mapper.readTree(is);
        JsonNode vectorsNode = root.get("vectors");
        assertThat(vectorsNode).as("vectors array in JSON").isNotNull();

        vectors = new ArrayList<>();
        for (JsonNode v : vectorsNode) {
            List<String> caveatStrings = new ArrayList<>();
            JsonNode caveatsNode = v.get("caveats");
            if (caveatsNode != null && caveatsNode.isArray()) {
                for (JsonNode c : caveatsNode) {
                    caveatStrings.add(c.get("key").asText() + "=" + c.get("value").asText());
                }
            }

            vectors.add(new VectorData(
                    v.get("description").asText(),
                    HEX.parseHex(v.get("rootKey").asText()),
                    HEX.parseHex(v.get("identifier").asText()),
                    v.get("location").isNull() ? null : v.get("location").asText(),
                    caveatStrings,
                    HEX.parseHex(v.get("expectedSignature").asText()),
                    HEX.parseHex(v.get("expectedSerializedV2").asText()),
                    v.get("expectedBase64").asText()
            ));
        }
        assertThat(vectors).as("number of test vectors").hasSizeGreaterThanOrEqualTo(4);
    }

    Stream<VectorData> allVectors() {
        return vectors.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    @DisplayName("HMAC signature chain matches expected signature")
    void signatureChainMatches(VectorData vector) {
        byte[] derivedKey = MacaroonCrypto.deriveKey(vector.rootKey());
        byte[] sig = MacaroonCrypto.hmac(derivedKey, vector.identifier());

        for (String caveat : vector.caveatStrings()) {
            sig = MacaroonCrypto.hmac(sig, caveat.getBytes(StandardCharsets.UTF_8));
        }

        assertThat(HEX.formatHex(sig))
                .as("final signature for: %s", vector.description())
                .isEqualTo(HEX.formatHex(vector.expectedSignature()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    @DisplayName("V2 binary serialization matches expected bytes")
    void serializationMatches(VectorData vector) {
        List<Caveat> caveats = vector.caveatStrings().stream()
                .map(s -> {
                    int eq = s.indexOf('=');
                    return new Caveat(s.substring(0, eq), s.substring(eq + 1));
                })
                .toList();
        Macaroon macaroon = new Macaroon(
                vector.identifier(), vector.location(), caveats, vector.expectedSignature());
        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);

        assertThat(HEX.formatHex(serialized))
                .as("V2 serialization for: %s", vector.description())
                .isEqualTo(HEX.formatHex(vector.expectedSerializedV2()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    @DisplayName("Base64 encoding matches expected string")
    void base64Matches(VectorData vector) {
        String base64 = Base64.getEncoder().encodeToString(vector.expectedSerializedV2());

        assertThat(base64)
                .as("base64 for: %s", vector.description())
                .isEqualTo(vector.expectedBase64());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    @DisplayName("Deserialization round-trip preserves all fields")
    void deserializationRoundTrip(VectorData vector) {
        Macaroon deserialized = MacaroonSerializer.deserializeV2(vector.expectedSerializedV2());

        assertThat(HEX.formatHex(deserialized.identifier()))
                .as("identifier for: %s", vector.description())
                .isEqualTo(HEX.formatHex(vector.identifier()));

        assertThat(deserialized.location())
                .as("location for: %s", vector.description())
                .isEqualTo(vector.location());

        List<String> deserializedCaveatStrings = deserialized.caveats().stream()
                .map(Caveat::toString)
                .toList();
        assertThat(deserializedCaveatStrings)
                .as("caveats for: %s", vector.description())
                .containsExactlyElementsOf(vector.caveatStrings());

        assertThat(HEX.formatHex(deserialized.signature()))
                .as("signature for: %s", vector.description())
                .isEqualTo(HEX.formatHex(vector.expectedSignature()));

        // Re-serialize and verify byte-level equality
        byte[] reserialized = MacaroonSerializer.serializeV2(deserialized);
        assertThat(HEX.formatHex(reserialized))
                .as("re-serialization for: %s", vector.description())
                .isEqualTo(HEX.formatHex(vector.expectedSerializedV2()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    @DisplayName("Identifier is exactly 66 bytes")
    void identifierLength(VectorData vector) {
        assertThat(vector.identifier())
                .as("identifier length for: %s", vector.description())
                .hasSize(66);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    @DisplayName("Signature is exactly 32 bytes")
    void signatureLength(VectorData vector) {
        assertThat(vector.expectedSignature())
                .as("signature length for: %s", vector.description())
                .hasSize(32);
    }

}
