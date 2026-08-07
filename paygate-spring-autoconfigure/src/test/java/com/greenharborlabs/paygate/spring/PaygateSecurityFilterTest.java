package com.greenharborlabs.paygate.spring;

import static com.greenharborlabs.paygate.spring.PaygateTestSupport.createStubInvoice;
import static com.greenharborlabs.paygate.spring.PaygateTestSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Metadata;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;
import com.greenharborlabs.paygate.spring.PaygateTestSupport.InMemoryTestCredentialStore;
import com.greenharborlabs.paygate.spring.PaygateTestSupport.InMemoryTestRootKeyStore;
import com.greenharborlabs.paygate.spring.PaygateTestSupport.StubLightningBackend;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring integration tests for {@link PaygateSecurityFilter}.
 *
 * <p>Tests the filter behavior for protected and unprotected endpoints covering:
 *
 * <ul>
 *   <li>No auth header on protected endpoint returns 402 with WWW-Authenticate
 *   <li>Valid L402 credential returns 200 with token headers
 *   <li>Non-protected endpoint passes through without authentication
 *   <li>Lightning backend unavailable returns 503
 * </ul>
 *
 * <p>Uses a test-specific configuration that manually wires all required beans, avoiding dependency
 * on PaygateAutoConfiguration which does not yet exist.
 */
@SpringBootTest(classes = PaygateSecurityFilterTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("PaygateSecurityFilter")
class PaygateSecurityFilterTest {

  private static final byte[] ROOT_KEY = new byte[32];
  private static final HexFormat HEX = HexFormat.of();
  private static final long PRICE_SATS = 10;
  private static final String PROTECTED_PATH = "/api/protected";
  private static final String CAPABILITY_PROTECTED_PATH = "/api/capability-protected";
  private static final String EXPLICIT_HEAD_PATH = "/api/explicit-head";
  private static final String PUBLIC_PATH = "/api/public";
  private static final String SERVICE_NAME = "test-service";
  private static final long TIMEOUT_SECONDS = 600;

  static {
    new SecureRandom().nextBytes(ROOT_KEY);
  }

  @Autowired private MockMvc mockMvc;

  @Autowired private LightningBackend lightningBackend;

  @Autowired private PaygateEarningsTracker earningsTracker;

  @Autowired private PaygateSecurityFilter paygateSecurityFilter;

  @Autowired private CapturingPaymentProtocol capturingPaymentProtocol;

  @Autowired private TestController testController;

  @Test
  @DisplayName("returns sanitized 500 when endpoint policy resolution fails")
  void resolutionFailureReturnsInternalErrorWithoutSideEffects() throws Exception {
    PaygateEndpointRegistry registry = mock(PaygateEndpointRegistry.class);
    PaygateChallengeService challengeService = mock(PaygateChallengeService.class);
    jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);
    when(registry.resolve("GET", "/items/1"))
        .thenThrow(new IllegalStateException("secret policy detail"));
    var filter =
        new PaygateSecurityFilter(
            registry, List.of(), challengeService, SERVICE_NAME, null, null, null, null);
    var request = new MockHttpServletRequest("GET", "/items/1");
    request.setRequestURI("/items/1");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString())
        .contains("INTERNAL_ERROR")
        .doesNotContain("secret policy detail", "/items/1");
    verify(chain, never()).doFilter(any(), any());
    verify(challengeService, never()).acquireChallengeRateLimit(any());
    verify(challengeService, never()).createChallenge(any(), any(ResolvedEndpoint.class), any());
  }

  @Test
  @DisplayName("does not log credential markers from unexpected validation exceptions")
  void unexpectedValidationFailureDoesNotLogCredentialMarker() throws Exception {
    String credentialMarker = "AUTOCONFIG_SECRET_MACAROON_MARKER";
    capturingPaymentProtocol.throwUnexpectedValidationFailure(credentialMarker);

    try (var logCapture = LogCapture.attach(PaygateSecurityFilter.class.getName())) {
      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", "Payment " + credentialMarker))
          .andExpect(status().isServiceUnavailable());

      assertThat(logCapture.contents())
          .contains(
              "Unexpected credential validation error; failing closed with service unavailable")
          .doesNotContain(credentialMarker);
    }
  }

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
      return List.of(
          new ServicesCaveatVerifier(50),
          new com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier(50),
          new com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier(50),
          new ValidUntilCaveatVerifier("test-service"),
          new CapabilitiesCaveatVerifier("test-service", 50));
    }

    @Bean
    PaygateEndpointRegistry paygateEndpointRegistry() {
      var registry = new PaygateEndpointRegistry();
      registry.register(
          new PaygateEndpointConfig(
              "GET", PROTECTED_PATH, PRICE_SATS, 600, "Test protected endpoint", "", ""));
      registry.register(
          new PaygateEndpointConfig(
              "GET",
              CAPABILITY_PROTECTED_PATH,
              PRICE_SATS,
              600,
              "Capability protected endpoint",
              "",
              "search"));
      registry.register(
          new PaygateEndpointConfig(
              "GET", EXPLICIT_HEAD_PATH, PRICE_SATS, 600, "GET policy", "", "get"));
      registry.register(
          new PaygateEndpointConfig(
              "HEAD", EXPLICIT_HEAD_PATH, 25, 120, "HEAD policy", "", "head"));
      return registry;
    }

    @Bean
    PaygateEarningsTracker paygateEarningsTracker() {
      return new PaygateEarningsTracker();
    }

    @Bean
    PaygateProperties paygateProperties() {
      var props = new PaygateProperties();
      props.setServiceName("test-service");
      return props;
    }

    @Bean
    PaygateSecurityFilter paygateSecurityFilter(
        PaygateEndpointRegistry endpointRegistry,
        LightningBackend lightningBackendBean,
        RootKeyStore rootKeyStore,
        CredentialStore credentialStore,
        List<CaveatVerifier> caveatVerifiers,
        PaygateEarningsTracker paygateEarningsTracker,
        PaygateProperties paygateProperties,
        CapturingPaymentProtocol capturingPaymentProtocol) {
      var validator =
          new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, "test-service");
      var l402Protocol = new L402Protocol(validator, "test-service");
      var challengeService =
          new PaygateChallengeService(
              rootKeyStore,
              lightningBackendBean,
              paygateProperties,
              null,
              paygateEarningsTracker,
              null,
              null,
              null);
      return new PaygateSecurityFilter(
          endpointRegistry,
          List.of(l402Protocol, capturingPaymentProtocol),
          challengeService,
          "test-service",
          null,
          null,
          paygateEarningsTracker,
          null);
    }

    @Bean
    CapturingPaymentProtocol capturingPaymentProtocol() {
      return new CapturingPaymentProtocol();
    }

    @Bean
    TestController testController() {
      return new TestController();
    }
  }

  @RestController
  static class TestController {

    private final AtomicInteger protectedInvocations = new AtomicInteger();
    private final AtomicInteger explicitHeadInvocations = new AtomicInteger();

    @PaymentRequired(priceSats = 10, description = "Test protected endpoint")
    @GetMapping(PROTECTED_PATH)
    String protectedEndpoint() {
      protectedInvocations.incrementAndGet();
      return "protected-content";
    }

    @GetMapping(EXPLICIT_HEAD_PATH)
    String explicitHeadEndpoint() {
      explicitHeadInvocations.incrementAndGet();
      return "explicit-head-content";
    }

    @PaymentRequired(
        priceSats = 10,
        description = "Capability protected endpoint",
        capability = "search")
    @GetMapping(CAPABILITY_PROTECTED_PATH)
    String capabilityProtectedEndpoint() {
      return "capability-protected-content";
    }

    @GetMapping(PUBLIC_PATH)
    String publicEndpoint() {
      return "public-content";
    }

    void resetInvocations() {
      protectedInvocations.set(0);
      explicitHeadInvocations.set(0);
    }
  }

  static class CapturingPaymentProtocol implements PaymentProtocol {
    private final AtomicReference<ChallengeContext> lastChallengeContext = new AtomicReference<>();
    private final AtomicReference<RuntimeException> unexpectedValidationFailure =
        new AtomicReference<>();

    @Override
    public String scheme() {
      return "Payment";
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      return unexpectedValidationFailure.get() != null;
    }

    @Override
    public PaymentCredential parseCredential(String authorizationHeader)
        throws PaymentValidationException {
      RuntimeException failure = unexpectedValidationFailure.get();
      if (failure != null) {
        throw failure;
      }
      throw new PaymentValidationException(
          PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL,
          "Payment credentials are not parsed in this boundary test",
          (String) null);
    }

    @Override
    public ChallengeResponse formatChallenge(ChallengeContext context) {
      if (context.digest() == null || context.digest().isBlank()) {
        throw new IllegalArgumentException("MPP challenge digest must not be null or blank");
      }
      lastChallengeContext.set(context);
      return new ChallengeResponse(
          "Payment digest=\"" + context.digest() + "\"",
          "Payment",
          Map.of("digest", context.digest()));
    }

    @Override
    public void validate(PaymentCredential credential, Map<String, String> requestContext)
        throws PaymentValidationException {
      // Not used because canHandle returns false.
    }

    void reset() {
      lastChallengeContext.set(null);
      unexpectedValidationFailure.set(null);
    }

    void throwUnexpectedValidationFailure(String credentialMarker) {
      unexpectedValidationFailure.set(new IllegalStateException(credentialMarker));
    }

    ChallengeContext lastChallengeContext() {
      return lastChallengeContext.get();
    }
  }

  private static final class LogCapture extends Handler implements AutoCloseable {
    private final Logger logger;
    private final List<LogRecord> records = new java.util.ArrayList<>();

    private LogCapture(Logger logger) {
      this.logger = logger;
    }

    static LogCapture attach(String loggerName) {
      Logger logger = Logger.getLogger(loggerName);
      var capture = new LogCapture(logger);
      logger.addHandler(capture);
      return capture;
    }

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      logger.removeHandler(this);
    }

    String contents() {
      return records.stream()
          .map(
              record ->
                  record.getMessage()
                      + java.util.Arrays.toString(record.getParameters())
                      + record.getThrown())
          .collect(java.util.stream.Collectors.joining("\n"));
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
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
      capturingPaymentProtocol.reset();
    }

    @Test
    @DisplayName("returns 402 with WWW-Authenticate header")
    void returns402WithWwwAuthenticate() throws Exception {
      mockMvc
          .perform(get(PROTECTED_PATH))
          .andExpect(status().isPaymentRequired())
          .andExpect(header().exists("WWW-Authenticate"))
          .andExpect(header().string("WWW-Authenticate", containsString("L402")))
          .andExpect(header().string("WWW-Authenticate", containsString("version=\"0\"")))
          .andExpect(header().string("WWW-Authenticate", containsString("token=")))
          .andExpect(header().string("WWW-Authenticate", containsString("macaroon=")))
          .andExpect(header().string("WWW-Authenticate", containsString("invoice=")));
    }

    @Test
    @DisplayName("returns 402 with Payment digest challenge when MPP is enabled")
    void returns402WithPaymentDigestChallengeWhenMppEnabled() throws Exception {
      var result =
          mockMvc
              .perform(get(PROTECTED_PATH))
              .andExpect(status().isPaymentRequired())
              .andExpect(jsonPath("$.protocols.Payment.digest", notNullValue()))
              .andReturn();

      List<String> wwwAuthHeaders = result.getResponse().getHeaders("WWW-Authenticate");
      assertThat(wwwAuthHeaders).hasSize(2);
      assertThat(wwwAuthHeaders.get(0)).startsWith("L402");
      assertThat(wwwAuthHeaders.get(1)).startsWith("Payment").contains("digest=");

      ChallengeContext capturedContext = capturingPaymentProtocol.lastChallengeContext();
      assertThat(capturedContext).isNotNull();
      assertThat(capturedContext.digest()).isNotBlank();
      assertThat(result.getResponse().getContentAsString()).contains(capturedContext.digest());
    }

    @Test
    @DisplayName("returns JSON body with payment details")
    void returns402JsonBody() throws Exception {
      mockMvc
          .perform(get(PROTECTED_PATH))
          .andExpect(status().isPaymentRequired())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.code", is(402)))
          .andExpect(jsonPath("$.message", is("Payment required")))
          .andExpect(jsonPath("$.price_sats", is(10)))
          .andExpect(jsonPath("$.invoice", notNullValue()));
    }
  }

  @Nested
  @DisplayName("HEAD policy inheritance")
  class HeadPolicyInheritance {

    @BeforeEach
    void setUp() {
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
      capturingPaymentProtocol.reset();
      testController.resetInvocations();
    }

    @Test
    @DisplayName("challenges unpaid HEAD using GET policy before invoking the handler")
    void challengesUnpaidHeadUsingGetPolicyBeforeHandler() throws Exception {
      mockMvc
          .perform(request(HttpMethod.HEAD, PROTECTED_PATH))
          .andExpect(status().isPaymentRequired());

      ChallengeContext challenge = capturingPaymentProtocol.lastChallengeContext();
      assertThat(challenge).isNotNull();
      assertThat(challenge.priceSats()).isEqualTo(PRICE_SATS);
      assertThat(challenge.routePattern()).isEqualTo(PROTECTED_PATH);
      assertThat(challenge.requestMethod()).isEqualTo("HEAD");
      assertThat(testController.protectedInvocations).hasValue(0);
    }

    @Test
    @DisplayName("challenges prefixed unpaid HEAD using the canonical application route")
    void challengesUnpaidHeadUnderDeploymentPrefixBeforeHandler() throws Exception {
      mockMvc
          .perform(request(HttpMethod.HEAD, "/gateway" + PROTECTED_PATH).contextPath("/gateway"))
          .andExpect(status().isPaymentRequired());

      ChallengeContext challenge = capturingPaymentProtocol.lastChallengeContext();
      assertThat(challenge).isNotNull();
      assertThat(challenge.routePattern()).isEqualTo(PROTECTED_PATH);
      assertThat(challenge.requestMethod()).isEqualTo("HEAD");
      assertThat(testController.protectedInvocations).hasValue(0);
    }

    @Test
    @DisplayName("accepts a credential bound to the actual HEAD request")
    void acceptsCorrectlyHeadBoundCredential() throws Exception {
      String credential = mintCredentialWithCaveats(requestBoundaryCaveats(PROTECTED_PATH, "HEAD"));

      mockMvc
          .perform(request(HttpMethod.HEAD, PROTECTED_PATH).header("Authorization", credential))
          .andExpect(status().isOk())
          .andExpect(header().doesNotExist("WWW-Authenticate"));

      assertThat(testController.protectedInvocations).hasValue(1);
    }

    @Test
    @DisplayName("rejects a GET-bound credential on HEAD before invoking the handler")
    void rejectsGetBoundCredentialOnHeadBeforeHandler() throws Exception {
      String credential = mintCredentialWithCaveats(requestBoundaryCaveats(PROTECTED_PATH, "GET"));

      mockMvc
          .perform(request(HttpMethod.HEAD, PROTECTED_PATH).header("Authorization", credential))
          .andExpect(status().isPaymentRequired());

      assertThat(testController.protectedInvocations).hasValue(0);
    }

    @Test
    @DisplayName("prefers an explicit HEAD policy over the GET policy")
    void prefersExplicitHeadPolicyOverGetPolicy() throws Exception {
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(25));

      mockMvc
          .perform(request(HttpMethod.HEAD, EXPLICIT_HEAD_PATH))
          .andExpect(status().isPaymentRequired());

      ChallengeContext challenge = capturingPaymentProtocol.lastChallengeContext();
      assertThat(challenge).isNotNull();
      assertThat(challenge.priceSats()).isEqualTo(25);
      assertThat(challenge.description()).isEqualTo("HEAD policy");
      assertThat(challenge.timeoutSeconds()).isEqualTo(120);
      assertThat(challenge.capability()).isEqualTo("head");
      assertThat(challenge.routePattern()).isEqualTo(EXPLICIT_HEAD_PATH);
      assertThat(challenge.requestMethod()).isEqualTo("HEAD");
      assertThat(testController.explicitHeadInvocations).hasValue(0);
    }

    @Test
    @DisplayName("does not apply GET payment policy to OPTIONS")
    void doesNotApplyGetPaymentPolicyToOptions() throws Exception {
      mockMvc
          .perform(request(HttpMethod.OPTIONS, PROTECTED_PATH))
          .andExpect(status().isOk())
          .andExpect(header().doesNotExist("WWW-Authenticate"));

      assertThat(capturingPaymentProtocol.lastChallengeContext()).isNull();
      assertThat(testController.protectedInvocations).hasValue(0);
    }
  }

  @Nested
  @DisplayName("malformed auth header on protected endpoint")
  class MalformedAuthHeader {

    @BeforeEach
    void setUp() {
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
    }

    @Test
    @DisplayName("returns 402 when Authorization header is not L402 scheme")
    void nonL402SchemeReturns402() throws Exception {
      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", "Bearer some-token"))
          .andExpect(status().isPaymentRequired());
    }

    @Test
    @DisplayName("returns 400 when Authorization header has malformed L402 value")
    void malformedL402Returns400() throws Exception {
      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", "L402 not-valid-format"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code", is(400)))
          .andExpect(jsonPath("$.error", is("MALFORMED_HEADER")));
    }

    @Test
    @DisplayName("returns 400 when L402 header has special chars that fail regex extraction")
    void l402HeaderWithSpecialCharsReturns400() throws Exception {
      // Header starts with "L402 " but contains chars outside the allowed base64+hex charset,
      // so L402HeaderComponents.extract() returns empty while isL402Header() returns true.
      String badHeader = "L402 bad-chars!@#:" + "ab".repeat(32);
      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", badHeader))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code", is(400)))
          .andExpect(jsonPath("$.error", is("MALFORMED_HEADER")));
    }
  }

  @Nested
  @DisplayName("valid credential on protected endpoint")
  class ValidCredential {

    @Test
    @DisplayName("returns 200 with X-L402-Credential-Expires matching valid_until caveat")
    void validCredentialReturns200WithHeaders() throws Exception {
      ((StubLightningBackend) lightningBackend).setHealthy(true);

      // Generate a preimage and its corresponding payment hash
      byte[] preimage = new byte[32];
      new SecureRandom().nextBytes(preimage);
      byte[] paymentHash = sha256(preimage);

      // Generate a token ID
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);

      // Use a specific valid_until epoch so we can assert the header value
      long validUntilEpoch = Instant.now().plusSeconds(TIMEOUT_SECONDS).getEpochSecond();
      List<Caveat> caveats =
          List.of(
              new Caveat("services", SERVICE_NAME + ":0"),
              new Caveat("route", PROTECTED_PATH),
              new Caveat("method", "GET"),
              new Caveat(SERVICE_NAME + "_capabilities", "~"),
              new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntilEpoch)));

      // Mint a real macaroon using the known root key
      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, caveats);

      // Serialize the macaroon to V2 binary and base64 encode
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

      // Format preimage as hex
      String preimageHex = HEX.formatHex(preimage);

      // Build the L402 Authorization header: L402 <base64-macaroon>:<hex-preimage>
      String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

      var result =
          mockMvc
              .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
              .andExpect(status().isOk())
              .andExpect(header().doesNotExist("X-L402-Token-Id"))
              .andExpect(header().exists("X-L402-Credential-Expires"))
              .andExpect(content().string("protected-content"))
              .andReturn();

      // The header must reflect the actual valid_until caveat, not a hardcoded default
      String expiresHeader = result.getResponse().getHeader("X-L402-Credential-Expires");
      Instant expiresInstant = Instant.parse(expiresHeader);
      assertThat(expiresInstant).isEqualTo(Instant.ofEpochSecond(validUntilEpoch));
    }

    @Test
    @DisplayName("falls back to default timeout when no valid_until caveat present")
    void fallsBackToDefaultTimeoutWhenNoValidUntilCaveat() throws Exception {
      ((StubLightningBackend) lightningBackend).setHealthy(true);

      byte[] preimage = new byte[32];
      new SecureRandom().nextBytes(preimage);
      byte[] paymentHash = sha256(preimage);
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);

      // Caveats without valid_until — only service and mandatory request-boundary caveats
      List<Caveat> caveats =
          List.of(
              new Caveat("services", SERVICE_NAME + ":0"),
              new Caveat("route", PROTECTED_PATH),
              new Caveat("method", "GET"),
              new Caveat(SERVICE_NAME + "_capabilities", "~"));

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, caveats);
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimage);
      String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

      Instant before = Instant.now().plusSeconds(TIMEOUT_SECONDS);
      var result =
          mockMvc
              .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
              .andExpect(status().isOk())
              .andExpect(header().exists("X-L402-Credential-Expires"))
              .andReturn();
      Instant after = Instant.now().plusSeconds(TIMEOUT_SECONDS);

      String expiresHeader = result.getResponse().getHeader("X-L402-Credential-Expires");
      Instant expiresInstant = Instant.parse(expiresHeader);
      // Fallback should be approximately now + timeoutSeconds
      assertThat(expiresInstant).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    @Test
    @DisplayName("uses earliest valid_until when multiple caveats present")
    void usesEarliestValidUntilCaveat() throws Exception {
      ((StubLightningBackend) lightningBackend).setHealthy(true);

      byte[] preimage = new byte[32];
      new SecureRandom().nextBytes(preimage);
      byte[] paymentHash = sha256(preimage);
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);

      long earlierEpoch = Instant.now().plusSeconds(300).getEpochSecond();
      long laterEpoch = Instant.now().plusSeconds(900).getEpochSecond();

      List<Caveat> caveats =
          List.of(
              new Caveat("services", SERVICE_NAME + ":0"),
              new Caveat("route", PROTECTED_PATH),
              new Caveat("method", "GET"),
              new Caveat(SERVICE_NAME + "_capabilities", "~"),
              new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(laterEpoch)),
              new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(earlierEpoch)));

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, caveats);
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimage);
      String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

      var result =
          mockMvc
              .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
              .andExpect(status().isOk())
              .andExpect(header().exists("X-L402-Credential-Expires"))
              .andReturn();

      String expiresHeader = result.getResponse().getHeader("X-L402-Credential-Expires");
      Instant expiresInstant = Instant.parse(expiresHeader);
      assertThat(expiresInstant).isEqualTo(Instant.ofEpochSecond(earlierEpoch));
    }

    @Test
    @DisplayName("falls back to default timeout when valid_until caveat value is unparseable")
    void fallsBackToDefaultTimeoutWhenValidUntilCaveatUnparseable() {
      byte[] preimage = new byte[32];
      new SecureRandom().nextBytes(preimage);
      byte[] paymentHash = sha256(preimage);
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);

      List<Caveat> caveats =
          List.of(
              new Caveat("services", SERVICE_NAME + ":0"),
              new Caveat(SERVICE_NAME + "_valid_until", "not-a-number"));

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, caveats);
      PaymentCredential credential =
          new PaymentCredential(
              paymentHash,
              preimage,
              HEX.formatHex(tokenId),
              "L402",
              null,
              new L402Metadata(macaroon, List.of(), "L402 dummy"));

      PaygateEndpointConfig config =
          new PaygateEndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, TIMEOUT_SECONDS, "", "", "");

      Instant before = Instant.now().plusSeconds(TIMEOUT_SECONDS);
      Instant result = paygateSecurityFilter.resolveCredentialExpiry(credential, config);
      Instant after = Instant.now().plusSeconds(TIMEOUT_SECONDS);

      assertThat(result).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    @Test
    @DisplayName("uses valid caveat when mixed with unparseable caveat")
    void usesValidCaveatWhenMixedWithUnparseableCaveat() {
      byte[] preimage = new byte[32];
      new SecureRandom().nextBytes(preimage);
      byte[] paymentHash = sha256(preimage);
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);

      long validEpoch = Instant.now().plusSeconds(500).getEpochSecond();
      List<Caveat> caveats =
          List.of(
              new Caveat("services", SERVICE_NAME + ":0"),
              new Caveat(SERVICE_NAME + "_valid_until", "garbage"),
              new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validEpoch)));

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, caveats);
      PaymentCredential credential =
          new PaymentCredential(
              paymentHash,
              preimage,
              HEX.formatHex(tokenId),
              "L402",
              null,
              new L402Metadata(macaroon, List.of(), "L402 dummy"));

      PaygateEndpointConfig config =
          new PaygateEndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, TIMEOUT_SECONDS, "", "", "");

      Instant result = paygateSecurityFilter.resolveCredentialExpiry(credential, config);

      assertThat(result).isEqualTo(Instant.ofEpochSecond(validEpoch));
    }
  }

  @Nested
  @DisplayName("unprotected endpoint")
  class UnprotectedEndpoint {

    @Test
    @DisplayName("passes through without authentication")
    void publicEndpointReturns200WithoutAuth() throws Exception {
      mockMvc
          .perform(get(PUBLIC_PATH))
          .andExpect(status().isOk())
          .andExpect(content().string("public-content"));
    }

    @Test
    @DisplayName("does not add L402 response headers")
    void publicEndpointHasNoL402Headers() throws Exception {
      mockMvc
          .perform(get(PUBLIC_PATH))
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
      mockMvc
          .perform(get(PROTECTED_PATH))
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

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, validCaveats());
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimage);
      String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
          .andExpect(status().isOk())
          .andExpect(header().doesNotExist("X-L402-Token-Id"))
          .andExpect(content().string("protected-content"));
    }

    @Test
    @DisplayName("unprotected endpoint still works when Lightning is down")
    void publicEndpointStillWorksWhenLightningDown() throws Exception {
      mockMvc
          .perform(get(PUBLIC_PATH))
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
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
    }

    @Test
    @DisplayName("increments invoices created after 402 challenge")
    void incrementsInvoicesCreatedAfter402() throws Exception {
      long before = earningsTracker.getTotalInvoicesCreated();

      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired());

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

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, validCaveats());
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimage);
      String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
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
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
    }

    @Test
    @DisplayName("402 challenge macaroon contains services and valid_until caveats")
    void challengeMacaroonContainsCaveats() throws Exception {
      var result =
          mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isPaymentRequired()).andReturn();

      String wwwAuth = result.getResponse().getHeader("WWW-Authenticate");
      assertThat(wwwAuth).isNotNull();

      // Extract macaroon from WWW-Authenticate header
      String macaroonB64 = wwwAuth.split("macaroon=\"")[1].split("\"")[0];
      byte[] macaroonBytes = Base64.getDecoder().decode(macaroonB64);
      Macaroon macaroon = MacaroonSerializer.deserializeV2(macaroonBytes);

      assertThat(macaroon.caveats()).hasSize(5);
      assertThat(macaroon.caveats().get(0).key()).isEqualTo("services");
      assertThat(macaroon.caveats().get(0).value()).isEqualTo(SERVICE_NAME + ":0");
      assertThat(macaroon.caveats().get(1)).isEqualTo(new Caveat("route", PROTECTED_PATH));
      assertThat(macaroon.caveats().get(2)).isEqualTo(new Caveat("method", "GET"));
      assertThat(macaroon.caveats().get(3))
          .isEqualTo(new Caveat(SERVICE_NAME + "_capabilities", "~"));
      assertThat(macaroon.caveats().get(4).key()).isEqualTo(SERVICE_NAME + "_valid_until");
      // valid_until should be a numeric epoch seconds value in the future
      long epochSeconds = Long.parseLong(macaroon.caveats().get(4).value());
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

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, expiredCaveats());
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimage);
      String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
          .andExpect(status().isPaymentRequired())
          .andExpect(jsonPath("$.title", is("EXPIRED_CREDENTIAL")));
    }

    @Test
    @DisplayName("wrong service name in caveat is rejected as INVALID_SERVICE")
    void wrongServiceIsRejected() throws Exception {
      byte[] preimage = new byte[32];
      new SecureRandom().nextBytes(preimage);
      byte[] paymentHash = sha256(preimage);
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);

      MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
      Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, wrongServiceCaveats());
      byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
      String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
      String preimageHex = HEX.formatHex(preimage);
      String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
          .andExpect(status().isPaymentRequired())
          .andExpect(jsonPath("$.title", is("INVALID_CHALLENGE_BINDING")));
    }
  }

  @Nested
  @DisplayName("X-Forwarded-For header trust")
  class ForwardedHeaderTrust {

    @Test
    @DisplayName("trustForwardedHeaders defaults to false in PaygateProperties")
    void trustForwardedHeadersDefaultsToFalse() {
      var props = new PaygateProperties();
      assertThat(props.isTrustForwardedHeaders()).isFalse();
    }

    @Test
    @DisplayName("trustForwardedHeaders can be set to true via setter")
    void trustForwardedHeadersCanBeSetToTrue() {
      var props = new PaygateProperties();
      props.setTrustForwardedHeaders(true);
      assertThat(props.isTrustForwardedHeaders()).isTrue();
    }

    @Test
    @DisplayName("filter created without properties ignores XFF (backward compat)")
    void filterWithoutPropertiesIgnoresXff() throws Exception {
      // The test app uses the backward-compatible constructor (no properties),
      // so XFF should be ignored. Requests with XFF should still work normally.
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));

      mockMvc
          .perform(get(PROTECTED_PATH).header("X-Forwarded-For", "10.0.0.1"))
          .andExpect(status().isPaymentRequired());
    }
  }

  @Nested
  @DisplayName("bolt11 sanitization")
  class Bolt11Sanitization {

    @Test
    @DisplayName("rejects malicious bolt11 with control chars — returns 503 (fail closed)")
    void rejectsMaliciousBolt11() throws Exception {
      ((StubLightningBackend) lightningBackend).setHealthy(true);

      // Craft a malicious bolt11 with header injection characters
      String maliciousBolt11 = "lnbc100n1p0test\r\nEvil-Header: injected\"\r\nAnother: bad";
      byte[] paymentHash = new byte[32];
      new SecureRandom().nextBytes(paymentHash);
      Instant now = Instant.now();
      Invoice maliciousInvoice =
          new Invoice(
              paymentHash,
              maliciousBolt11,
              PRICE_SATS,
              "Test invoice",
              InvoiceStatus.PENDING,
              null,
              now,
              now.plus(1, ChronoUnit.HOURS));
      ((StubLightningBackend) lightningBackend).setNextInvoice(maliciousInvoice);

      // sanitizeBolt11ForHeader now rejects (throws) instead of stripping,
      // so the filter's generic catch block returns 503 — fail closed
      mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isServiceUnavailable());
    }
  }

  @Nested
  @DisplayName("path traversal prevention")
  class PathTraversalPrevention {

    @BeforeEach
    void setUp() {
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
    }

    @Test
    @DisplayName("path traversal to protected endpoint is blocked with 402")
    void pathTraversalToProtectedEndpointIsBlocked() throws Exception {
      // /api/public/../protected normalizes to /api/protected which is protected
      mockMvc
          .perform(get("/api/public/../protected"))
          .andExpect(status().isPaymentRequired())
          .andExpect(header().exists("WWW-Authenticate"))
          .andExpect(header().string("WWW-Authenticate", containsString("L402")));
    }

    @Test
    @DisplayName("double dot segments in path are normalized before registry lookup")
    void doubleDotsNormalizedBeforeLookup() throws Exception {
      // /api/foo/bar/../../protected normalizes to /api/protected
      mockMvc
          .perform(get("/api/foo/bar/../../protected"))
          .andExpect(status().isPaymentRequired())
          .andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    @DisplayName("percent-encoded path traversal to protected endpoint is blocked with 402")
    void percentEncodedTraversalIsBlocked() throws Exception {
      // %2e%2e is percent-encoded ".."
      mockMvc
          .perform(get("/api/public/%2e%2e/protected"))
          .andExpect(status().isPaymentRequired())
          .andExpect(header().exists("WWW-Authenticate"))
          .andExpect(header().string("WWW-Authenticate", containsString("L402")));
    }

    @Test
    @DisplayName("uppercase percent-encoded path traversal is blocked with 402")
    void uppercasePercentEncodedTraversalIsBlocked() throws Exception {
      mockMvc
          .perform(get("/api/public/%2E%2E/protected"))
          .andExpect(status().isPaymentRequired())
          .andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    @DisplayName("double-encoded path traversal is blocked with 402")
    void doubleEncodedTraversalIsBlocked() throws Exception {
      // %252e%252e double-encodes ".."
      mockMvc
          .perform(get("/api/public/%252e%252e/protected"))
          .andExpect(status().isPaymentRequired())
          .andExpect(header().exists("WWW-Authenticate"));
    }

    @Test
    @DisplayName("traversal that resolves outside protected paths is not challenged")
    void traversalToUnprotectedPathIsNotChallenged() throws Exception {
      // /api/protected/../public normalizes to /api/public which is NOT protected.
      // The filter should pass through (no 402); the downstream dispatch may or may
      // not find a handler for the raw URI, but the key assertion is no L402 challenge.
      int status =
          mockMvc.perform(get("/api/protected/../public")).andReturn().getResponse().getStatus();
      assertThat(status).isNotEqualTo(402);
    }
  }

  @Nested
  @DisplayName("capability enforcement")
  class CapabilityEnforcement {

    @BeforeEach
    void setUp() {
      ((StubLightningBackend) lightningBackend).setHealthy(true);
      ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice(PRICE_SATS));
    }

    @Test
    @DisplayName("credential with matching capability passes on capability-protected endpoint")
    void matchingCapabilityPasses() throws Exception {
      String authHeader = mintCredentialWithCaveats(caveatsWithCapabilities("search,analyze"));

      mockMvc
          .perform(get(CAPABILITY_PROTECTED_PATH).header("Authorization", authHeader))
          .andExpect(status().isOk())
          .andExpect(content().string("capability-protected-content"));
    }

    @Test
    @DisplayName(
        "credential without matching capability is rejected on capability-protected endpoint")
    void missingCapabilityRejected() throws Exception {
      String authHeader = mintCredentialWithCaveats(caveatsWithCapabilities("analyze"));

      mockMvc
          .perform(get(CAPABILITY_PROTECTED_PATH).header("Authorization", authHeader))
          .andExpect(status().isPaymentRequired())
          .andExpect(jsonPath("$.title", is("INVALID_CHALLENGE_BINDING")));
    }

    @Test
    @DisplayName(
        "credential without capabilities caveat is rejected on capability-protected endpoint")
    void credentialWithoutCapabilitiesCaveatRejectedOnCapabilityEndpoint() throws Exception {
      String authHeader = mintCredentialWithCaveats(caveatsWithoutCapabilityCeiling());

      mockMvc
          .perform(get(CAPABILITY_PROTECTED_PATH).header("Authorization", authHeader))
          .andExpect(status().isPaymentRequired())
          .andExpect(jsonPath("$.title", is("INVALID_CHALLENGE_BINDING")));
    }

    @Test
    @DisplayName("credential without capabilities caveat passes on endpoint with empty capability")
    void emptyCapabilityPermissive() throws Exception {
      // Use the existing /api/protected endpoint which has capability = ""
      String authHeader = mintCredentialWithCaveats(validCaveats());

      mockMvc
          .perform(get(PROTECTED_PATH).header("Authorization", authHeader))
          .andExpect(status().isOk())
          .andExpect(content().string("protected-content"));
    }
  }

  // -----------------------------------------------------------------------
  // Test helpers
  // -----------------------------------------------------------------------

  private static List<Caveat> validCaveats() {
    Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
    return List.of(
        new Caveat("services", SERVICE_NAME + ":0"),
        new Caveat("route", PROTECTED_PATH),
        new Caveat("method", "GET"),
        new Caveat(SERVICE_NAME + "_capabilities", "~"),
        new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntil.getEpochSecond())));
  }

  private static List<Caveat> requestBoundaryCaveats(String route, String method) {
    Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
    return List.of(
        new Caveat("services", SERVICE_NAME + ":0"),
        new Caveat("route", route),
        new Caveat("method", method),
        new Caveat(SERVICE_NAME + "_capabilities", "~"),
        new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntil.getEpochSecond())));
  }

  private static List<Caveat> caveatsWithoutCapabilityCeiling() {
    Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
    return List.of(
        new Caveat("services", SERVICE_NAME + ":0"),
        new Caveat("route", PROTECTED_PATH),
        new Caveat("method", "GET"),
        new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntil.getEpochSecond())));
  }

  private static List<Caveat> expiredCaveats() {
    Instant expired = Instant.now().minusSeconds(60);
    return List.of(
        new Caveat("services", SERVICE_NAME + ":0"),
        new Caveat("route", PROTECTED_PATH),
        new Caveat("method", "GET"),
        new Caveat(SERVICE_NAME + "_capabilities", "~"),
        new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(expired.getEpochSecond())));
  }

  private static List<Caveat> wrongServiceCaveats() {
    Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
    return List.of(
        new Caveat("services", "wrong-service:0"),
        new Caveat("route", PROTECTED_PATH),
        new Caveat("method", "GET"),
        new Caveat(SERVICE_NAME + "_capabilities", "~"),
        new Caveat("wrong-service_valid_until", String.valueOf(validUntil.getEpochSecond())));
  }

  private static List<Caveat> caveatsWithCapabilities(String capabilities) {
    Instant validUntil = Instant.now().plusSeconds(TIMEOUT_SECONDS);
    return List.of(
        new Caveat("services", SERVICE_NAME + ":0"),
        new Caveat("route", CAPABILITY_PROTECTED_PATH),
        new Caveat("method", "GET"),
        new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(validUntil.getEpochSecond())),
        new Caveat(SERVICE_NAME + "_capabilities", capabilities));
  }

  private static String mintCredentialWithCaveats(List<Caveat> caveats) {
    byte[] preimage = new byte[32];
    new SecureRandom().nextBytes(preimage);
    byte[] paymentHash = sha256(preimage);
    byte[] tokenId = new byte[32];
    new SecureRandom().nextBytes(tokenId);

    MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
    Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, caveats);
    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    String preimageHex = HEX.formatHex(preimage);
    return "L402 " + macaroonBase64 + ":" + preimageHex;
  }
}
