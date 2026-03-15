package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.l402.core.protocol.L402Credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring integration tests for {@link L402SecurityFilter}.
 *
 * <p>Tests the filter behavior for protected and unprotected endpoints covering:
 * <ul>
 *   <li>No auth header on protected endpoint returns 402 with WWW-Authenticate</li>
 *   <li>Valid L402 credential returns 200 with token headers</li>
 *   <li>Non-protected endpoint passes through without authentication</li>
 *   <li>Lightning backend unavailable returns 503</li>
 * </ul>
 *
 * <p>Uses a test-specific configuration that manually wires all required beans,
 * avoiding dependency on L402AutoConfiguration which does not yet exist.
 */
@SpringBootTest(classes = L402SecurityFilterTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("L402SecurityFilter")
class L402SecurityFilterTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final HexFormat HEX = HexFormat.of();
    private static final long PRICE_SATS = 10;
    private static final String PROTECTED_PATH = "/api/protected";
    private static final String PUBLIC_PATH = "/api/public";
    private static final String SERVICE_NAME = "test-service";
    private static final long TIMEOUT_SECONDS = 600;

    static {
        new SecureRandom().nextBytes(ROOT_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LightningBackend lightningBackend;

    @Autowired
    private L402EarningsTracker earningsTracker;

    // -----------------------------------------------------------------------
    // Test application and configuration
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        LightningBackend lightningBackend() {
            return new StubLightningBackend();
        }

        @Bean
        RootKeyStore rootKeyStore() {
            return new InMemoryTestRootKeyStore(ROOT_KEY);
        }

        @Bean
        CredentialStore credentialStore() {
            return new InMemoryTestCredentialStore();
        }

        @Bean
        List<CaveatVerifier> caveatVerifiers() {
            return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier("test-service"));
        }

        @Bean
        L402EndpointRegistry l402EndpointRegistry() {
            var registry = new L402EndpointRegistry();
            registry.register(
                    new L402EndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, 600, "Test protected endpoint", "")
            );
            return registry;
        }

        @Bean
        L402EarningsTracker l402EarningsTracker() {
            return new L402EarningsTracker();
        }

        @Bean
        L402SecurityFilter l402SecurityFilter(
                L402EndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers,
                L402EarningsTracker l402EarningsTracker
        ) {
            var filter = new L402SecurityFilter(
                    endpointRegistry, lightningBackendBean, rootKeyStore, credentialStore, caveatVerifiers, "test-service"
            );
            filter.setEarningsTracker(l402EarningsTracker);
            return filter;
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @L402Protected(priceSats = 10, description = "Test protected endpoint")
        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "protected-content";
        }

        @GetMapping(PUBLIC_PATH)
        String publicEndpoint() {
            return "public-content";
        }
    }

    // -----------------------------------------------------------------------
    // Test scenarios
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("no auth header on protected endpoint")
    class NoAuthHeader {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("returns 402 with WWW-Authenticate header")
        void returns402WithWwwAuthenticate() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(header().string("WWW-Authenticate", containsString("L402")))
                    .andExpect(header().string("WWW-Authenticate", containsString("version=\"0\"")))
                    .andExpect(header().string("WWW-Authenticate", containsString("token=")))
                    .andExpect(header().string("WWW-Authenticate", containsString("macaroon=")))
                    .andExpect(header().string("WWW-Authenticate", containsString("invoice=")));
        }

        @Test
        @DisplayName("returns JSON body with payment details")
        void returns402JsonBody() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(402)))
                    .andExpect(jsonPath("$.message", is("Payment required")))
                    .andExpect(jsonPath("$.price_sats", is(10)))
                    .andExpect(jsonPath("$.invoice", notNullValue()));
        }
    }

    @Nested
    @DisplayName("malformed auth header on protected endpoint")
    class MalformedAuthHeader {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("returns 402 when Authorization header is not L402 scheme")
        void nonL402SchemeReturns402() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", "Bearer some-token"))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("returns 400 when Authorization header has malformed L402 value")
        void malformedL402Returns400() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", "L402 not-valid-format"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is(400)))
                    .andExpect(jsonPath("$.error", is("MALFORMED_HEADER")));
        }
    }

    @Nested
    @DisplayName("valid credential on protected endpoint")
    class ValidCredential {

        @Test
        @DisplayName("returns 200 with X-L402-Credential-Expires header but no X-L402-Token-Id")
        void validCredentialReturns200WithHeaders() throws Exception {
            ((StubLightningBackend) lightningBackend).setHealthy(true);

            // Generate a preimage and its corresponding payment hash
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);

            // Generate a token ID
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);

            // Mint a real macaroon using the known root key
            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, validCaveats());

            // Serialize the macaroon to V2 binary and base64 encode
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

            // Format preimage as hex
            String preimageHex = HEX.formatHex(preimage);

            // Build the L402 Authorization header: L402 <base64-macaroon>:<hex-preimage>
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-L402-Token-Id"))
                    .andExpect(header().exists("X-L402-Credential-Expires"))
                    .andExpect(content().string("protected-content"));
        }
    }

    @Nested
    @DisplayName("unprotected endpoint")
    class UnprotectedEndpoint {

        @Test
        @DisplayName("passes through without authentication")
        void publicEndpointReturns200WithoutAuth() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }

        @Test
        @DisplayName("does not add L402 response headers")
        void publicEndpointHasNoL402Headers() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-L402-Token-Id"))
                    .andExpect(header().doesNotExist("X-L402-Credential-Expires"))
                    .andExpect(header().doesNotExist("WWW-Authenticate"));
        }
    }

    @Nested
    @DisplayName("Lightning backend unavailable")
    class LightningUnavailable {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(false);
        }

        @Test
        @DisplayName("returns 503 when Lightning is unreachable and no auth header present")
        void returns503WhenLightningDown() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(503)))
                    .andExpect(jsonPath("$.error", is("LIGHTNING_UNAVAILABLE")))
                    .andExpect(jsonPath("$.message", containsString("Lightning backend is not available")));
        }

        @Test
        @DisplayName("valid credential succeeds even when Lightning is down")
        void validCredentialSucceedsWhenLightningDown() throws Exception {
            // Lightning is unhealthy (set in @BeforeEach), but valid credentials
            // should bypass the health check entirely.
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);

            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, validCaveats());
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimage);
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-L402-Token-Id"))
                    .andExpect(content().string("protected-content"));
        }

        @Test
        @DisplayName("unprotected endpoint still works when Lightning is down")
        void publicEndpointStillWorksWhenLightningDown() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }
    }

    @Nested
    @DisplayName("earnings tracker integration")
    class EarningsTrackerIntegration {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("increments invoices created after 402 challenge")
        void incrementsInvoicesCreatedAfter402() throws Exception {
            long before = earningsTracker.getTotalInvoicesCreated();

            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired());

            assertThat(earningsTracker.getTotalInvoicesCreated()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("increments sats earned after successful credential validation")
        void incrementsSatsEarnedAfterValidCredential() throws Exception {
            long satsBefore = earningsTracker.getTotalSatsEarned();
            long settledBefore = earningsTracker.getTotalInvoicesSettled();

            // Generate a valid credential
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);

            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, validCaveats());
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimage);
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isOk());

            assertThat(earningsTracker.getTotalSatsEarned()).isEqualTo(satsBefore + PRICE_SATS);
            assertThat(earningsTracker.getTotalInvoicesSettled()).isEqualTo(settledBefore + 1);
        }
    }

    @Nested
    @DisplayName("caveat enforcement")
    class CaveatEnforcement {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("402 challenge macaroon contains services and valid_until caveats")
        void challengeMacaroonContainsCaveats() throws Exception {
            var result = mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired())
                    .andReturn();

            String wwwAuth = result.getResponse().getHeader("WWW-Authenticate");
            assertThat(wwwAuth).isNotNull();

            // Extract macaroon from WWW-Authenticate header
            String macaroonB64 = wwwAuth.split("macaroon=\"")[1].split("\"")[0];
            byte[] macaroonBytes = Base64.getDecoder().decode(macaroonB64);
            Macaroon macaroon = MacaroonSerializer.deserializeV2(macaroonBytes);

            assertThat(macaroon.caveats()).hasSize(2);
            assertThat(macaroon.caveats().get(0).key()).isEqualTo("services");
            assertThat(macaroon.caveats().get(0).value()).isEqualTo(SERVICE_NAME + ":0");
            assertThat(macaroon.caveats().get(1).key()).isEqualTo(SERVICE_NAME + "_valid_until");
            // valid_until should be a numeric epoch seconds value in the future
            long epochSeconds = Long.parseLong(macaroon.caveats().get(1).value());
            assertThat(Instant.ofEpochSecond(epochSeconds)).isAfter(Instant.now());
        }

        @Test
        @DisplayName("expired valid_until caveat is rejected as EXPIRED_CREDENTIAL")
        void expiredCredentialIsRejected() throws Exception {
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);

            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, expiredCaveats());
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimage);
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error", is("EXPIRED_CREDENTIAL")));
        }

        @Test
        @DisplayName("wrong service name in caveat is rejected as INVALID_SERVICE")
        void wrongServiceIsRejected() throws Exception {
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);

            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, wrongServiceCaveats());
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimage);
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error", is("INVALID_SERVICE")));
        }
    }

    @Nested
    @DisplayName("X-Forwarded-For header trust")
    class ForwardedHeaderTrust {

        @Test
        @DisplayName("trustForwardedHeaders defaults to false in L402Properties")
        void trustForwardedHeadersDefaultsToFalse() {
            var props = new L402Properties();
            assertThat(props.isTrustForwardedHeaders()).isFalse();
        }

        @Test
        @DisplayName("trustForwardedHeaders can be set to true via setter")
        void trustForwardedHeadersCanBeSetToTrue() {
            var props = new L402Properties();
            props.setTrustForwardedHeaders(true);
            assertThat(props.isTrustForwardedHeaders()).isTrue();
        }

        @Test
        @DisplayName("filter created without properties ignores XFF (backward compat)")
        void filterWithoutPropertiesIgnoresXff() throws Exception {
            // The test app uses the backward-compatible constructor (no properties),
            // so XFF should be ignored. Requests with XFF should still work normally.
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("X-Forwarded-For", "10.0.0.1"))
                    .andExpect(status().isPaymentRequired());
        }
    }

    @Nested
    @DisplayName("bolt11 sanitization")
    class Bolt11Sanitization {

        @Test
        @DisplayName("strips header injection chars from bolt11 in WWW-Authenticate and escapes in JSON body")
        void sanitizesMaliciousBolt11() throws Exception {
            ((StubLightningBackend) lightningBackend).setHealthy(true);

            // Craft a malicious bolt11 with header injection and JSON breaking characters
            String maliciousBolt11 = "lnbc100n1p0test\r\nEvil-Header: injected\"\r\nAnother: bad";
            byte[] paymentHash = new byte[32];
            new SecureRandom().nextBytes(paymentHash);
            Instant now = Instant.now();
            Invoice maliciousInvoice = new Invoice(
                    paymentHash,
                    maliciousBolt11,
                    PRICE_SATS,
                    "Test invoice",
                    InvoiceStatus.PENDING,
                    null,
                    now,
                    now.plus(1, ChronoUnit.HOURS)
            );
            ((StubLightningBackend) lightningBackend).setNextInvoice(maliciousInvoice);

            var result = mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired())
                    .andReturn();

            // Verify WWW-Authenticate header has no \r, \n, or " from bolt11
            String wwwAuth = result.getResponse().getHeader("WWW-Authenticate");
            assertThat(wwwAuth).isNotNull();
            // Extract the invoice value from the header
            String invoiceInHeader = wwwAuth.split("invoice=\"")[1];
            invoiceInHeader = invoiceInHeader.substring(0, invoiceInHeader.lastIndexOf('"'));
            // CR, LF, and double-quote must be stripped — these enable header injection
            assertThat(invoiceInHeader).doesNotContain("\r");
            assertThat(invoiceInHeader).doesNotContain("\n");
            assertThat(invoiceInHeader).doesNotContain("\"");
            // The remaining text should be a continuous string with injection chars removed
            assertThat(invoiceInHeader).isEqualTo("lnbc100n1p0testEvil-Header: injectedAnother: bad");

            // Verify JSON body has the bolt11 properly escaped (no raw control chars or unescaped quotes)
            String body = result.getResponse().getContentAsString();
            // Raw CR/LF must not appear in the JSON body
            assertThat(body).doesNotContain("\r");
            assertThat(body).doesNotContain(maliciousBolt11);
            // The JSON should contain escaped versions of the special chars from bolt11
            assertThat(body).contains("\\r\\nEvil-Header: injected\\\"\\r\\nAnother: bad");
        }
    }

    @Nested
    @DisplayName("path traversal prevention")
    class PathTraversalPrevention {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("path traversal to protected endpoint is blocked with 402")
        void pathTraversalToProtectedEndpointIsBlocked() throws Exception {
            // /api/public/../protected normalizes to /api/protected which is protected
            mockMvc.perform(get("/api/public/../protected"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(header().string("WWW-Authenticate", containsString("L402")));
        }

        @Test
        @DisplayName("double dot segments in path are normalized before registry lookup")
        void doubleDotsNormalizedBeforeLookup() throws Exception {
            // /api/foo/bar/../../protected normalizes to /api/protected
            mockMvc.perform(get("/api/foo/bar/../../protected"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"));
        }

        @Test
        @DisplayName("percent-encoded path traversal to protected endpoint is blocked with 402")
        void percentEncodedTraversalIsBlocked() throws Exception {
            // %2e%2e is percent-encoded ".."
            mockMvc.perform(get("/api/public/%2e%2e/protected"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(header().string("WWW-Authenticate", containsString("L402")));
        }

        @Test
        @DisplayName("uppercase percent-encoded path traversal is blocked with 402")
        void uppercasePercentEncodedTraversalIsBlocked() throws Exception {
            mockMvc.perform(get("/api/public/%2E%2E/protected"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"));
        }

        @Test
        @DisplayName("double-encoded path traversal is blocked with 402")
        void doubleEncodedTraversalIsBlocked() throws Exception {
            // %252e%252e double-encodes ".."
            mockMvc.perform(get("/api/public/%252e%252e/protected"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"));
        }

        @Test
        @DisplayName("traversal that resolves outside protected paths is not challenged")
        void traversalToUnprotectedPathIsNotChallenged() throws Exception {
            // /api/protected/../public normalizes to /api/public which is NOT protected.
            // The filter should pass through (no 402); the downstream dispatch may or may
            // not find a handler for the raw URI, but the key assertion is no L402 challenge.
            int status = mockMvc.perform(get("/api/protected/../public"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isNotEqualTo(402);
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private static Invoice createStubInvoice() {
        byte[] paymentHash = new byte[32];
        new SecureRandom().nextBytes(paymentHash);
        Instant now = Instant.now();
        return new Invoice(
                paymentHash,
                "lnbc100n1p0testinvoice",
                PRICE_SATS,
                "Test invoice",
                InvoiceStatus.PENDING,
                null,
                now,
                now.plus(1, ChronoUnit.HOURS)
        );
    }

    private static List<Caveat> validCaveats() {
        Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
        return List.of(
                new Caveat("services", SERVICE_NAME + ":0"),
                new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntil.getEpochSecond()))
        );
    }

    private static List<Caveat> expiredCaveats() {
        Instant expired = Instant.now().minusSeconds(60);
        return List.of(
                new Caveat("services", SERVICE_NAME + ":0"),
                new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(expired.getEpochSecond()))
        );
    }

    private static List<Caveat> wrongServiceCaveats() {
        Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
        return List.of(
                new Caveat("services", "wrong-service:0"),
                new Caveat("wrong-service_valid_until", String.valueOf(validUntil.getEpochSecond()))
        );
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    // -----------------------------------------------------------------------
    // Stub / in-memory implementations for test isolation
    // -----------------------------------------------------------------------

    /**
     * Controllable stub for LightningBackend that allows tests to set health status
     * and pre-configure the next invoice to be returned.
     */
    static class StubLightningBackend implements LightningBackend {

        private volatile boolean healthy = true;
        private volatile Invoice nextInvoice;

        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        void setNextInvoice(Invoice invoice) {
            this.nextInvoice = invoice;
        }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            if (!healthy) {
                throw new RuntimeException("Lightning backend is not available");
            }
            if (nextInvoice != null) {
                return nextInvoice;
            }
            byte[] paymentHash = new byte[32];
            new SecureRandom().nextBytes(paymentHash);
            Instant now = Instant.now();
            return new Invoice(paymentHash, "lnbc" + amountSats + "n1pstub", amountSats,
                    memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            if (!healthy) {
                throw new RuntimeException("Lightning backend is not available");
            }
            return null;
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }
    }

    /**
     * In-memory RootKeyStore backed by a single fixed root key.
     * All calls to {@code generateRootKey()} and {@code getRootKey()} return the same key.
     */
    static class InMemoryTestRootKeyStore implements RootKeyStore {

        private final byte[] rootKey;

        InMemoryTestRootKeyStore(byte[] rootKey) {
            this.rootKey = rootKey.clone();
        }

        @Override
        public GenerationResult generateRootKey() {
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);
            return new GenerationResult(new com.greenharborlabs.l402.core.macaroon.SensitiveBytes(rootKey.clone()), tokenId);
        }

        @Override
        public com.greenharborlabs.l402.core.macaroon.SensitiveBytes getRootKey(byte[] keyId) {
            return new com.greenharborlabs.l402.core.macaroon.SensitiveBytes(rootKey.clone());
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            // no-op for tests
        }
    }

    /**
     * In-memory CredentialStore for test isolation.
     */
    static class InMemoryTestCredentialStore implements CredentialStore {

        private final Map<String, L402Credential> store = new ConcurrentHashMap<>();

        @Override
        public void store(String tokenId, L402Credential credential, long ttlSeconds) {
            store.put(tokenId, credential);
        }

        @Override
        public L402Credential get(String tokenId) {
            return store.get(tokenId);
        }

        @Override
        public void revoke(String tokenId) {
            store.remove(tokenId);
        }

        @Override
        public long activeCount() {
            return store.size();
        }
    }
}
