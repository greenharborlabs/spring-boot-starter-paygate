package com.greenharborlabs.paygate.example;

import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the L402 example application.
 *
 * <p>Starts the full application context and exercises the complete L402 flow:
 * unauthenticated challenge, credential presentation, and access grant.
 * Uses {@code paygate.root-key-store=memory} so the test can mint valid macaroons
 * against the same {@link InMemoryRootKeyStore} used by the filter.
 */
@SpringBootTest(classes = ExampleApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory"
})
@DisplayName("Example application integration")
class ExampleAppIntegrationTest {

    private static final HexFormat HEX = HexFormat.of();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RootKeyStore rootKeyStore;

    // -------------------------------------------------------------------
    // 1. Health endpoint (unprotected)
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("health endpoint")
    class HealthEndpoint {

        @Test
        @DisplayName("returns 200 without authentication")
        void returns200WithoutAuth() throws Exception {
            mockMvc.perform(get("/api/v1/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status", is("ok")));
        }
    }

    // -------------------------------------------------------------------
    // 2. Protected data endpoint (402 challenge)
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("data endpoint without authentication")
    class DataEndpointNoAuth {

        @Test
        @DisplayName("returns 402 with WWW-Authenticate header containing L402 challenge")
        void returns402WithChallenge() throws Exception {
            mockMvc.perform(get("/api/v1/data"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(header().string("WWW-Authenticate", startsWith("L402")))
                    .andExpect(header().string("WWW-Authenticate", containsString("macaroon=")))
                    .andExpect(header().string("WWW-Authenticate", containsString("invoice=")));
        }

        @Test
        @DisplayName("returns JSON body with 402 code and price_sats=10")
        void returns402JsonBody() throws Exception {
            mockMvc.perform(get("/api/v1/data"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(402)))
                    .andExpect(jsonPath("$.price_sats", is(10)));
        }
    }

    // -------------------------------------------------------------------
    // 3. Full L402 flow: challenge -> credential -> 200
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("full L402 credential flow on data endpoint")
    class FullL402Flow {

        @Test
        @DisplayName("valid L402 credential grants access with 200 and token headers")
        void validCredentialReturns200() throws Exception {
            // Generate a known preimage and compute its payment hash
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);

            // Use the application's RootKeyStore to generate a root key
            // so the filter's validator can look it up by tokenId
            RootKeyStore.GenerationResult genResult = rootKeyStore.generateRootKey();
            byte[] rootKey = genResult.rootKey().value();
            byte[] tokenId = genResult.tokenId();

            // We mint our own macaroon instead of extracting the server-generated one from
            // the 402 challenge because in test-mode the invoice contains a random payment
            // hash for which we cannot know the preimage (that would require inverting SHA-256).
            // By constructing both the preimage and the macaroon ourselves we can present a
            // valid L402 credential while still exercising the full verification path.
            //
            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, List.of());
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

            // Build the L402 Authorization header
            String preimageHex = HEX.formatHex(preimage);
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            // Request with valid credential should succeed
            mockMvc.perform(get("/api/v1/data")
                            .header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-L402-Token-Id"))
                    .andExpect(header().exists("X-L402-Credential-Expires"))
                    .andExpect(jsonPath("$.data", is("premium content")));
        }
    }

    // -------------------------------------------------------------------
    // 4. Analyze endpoint (dynamic pricing)
    // -------------------------------------------------------------------

    @Nested
    @DisplayName("analyze endpoint without authentication")
    class AnalyzeEndpointNoAuth {

        @Test
        @DisplayName("returns 402 with dynamic pricing reflecting base price")
        void returns402WithDynamicPricing() throws Exception {
            String shortBody = """
                    {"content": "short text"}""";

            mockMvc.perform(post("/api/v1/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(shortBody))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(402)))
                    .andExpect(jsonPath("$.price_sats", greaterThanOrEqualTo(50)));
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
