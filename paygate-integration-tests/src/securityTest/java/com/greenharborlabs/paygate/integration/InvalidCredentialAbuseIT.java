package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import com.greenharborlabs.paygate.spring.PaygateSecurityFilter;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@Tag("integration")
@SpringBootTest(classes = InvalidCredentialAbuseIT.TestApplication.class)
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.security-mode=servlet",
      "paygate.service-name=abuse-test",
      "paygate.protocols.mpp.challenge-binding-secret="
    })
@DisplayName("Invalid presented credential abuse")
class InvalidCredentialAbuseIT {

  private static final int ATTEMPTS = 10_000;
  private static final String PROTECTED_PATH = "/abuse/protected";
  private static final String INVALID_AUTHORIZATION = "Abuse invalid-credential";
  private static final String SAFE_RESPONSE_BODY =
      "{\"type\": \"https://paymentauth.org/problems/invalid\", \"title\": \"INVALID\", "
          + "\"status\": 402, \"detail\": \"Payment validation failed: INVALID\"}";

  private MockMvc mockMvc;

  @Autowired private WorkCounters counters;

  @Autowired private WebApplicationContext applicationContext;

  @Autowired private PaygateSecurityFilter paygateSecurityFilter;

  @BeforeEach
  void resetCounters() {
    counters.reset();
    mockMvc =
        MockMvcBuilders.webAppContextSetup(applicationContext)
            .addFilters(paygateSecurityFilter)
            .build();
  }

  @Test
  @DisplayName(
      "10,000 presented-invalid credentials use a fixed response and create no recovery state")
  void tenThousandPresentedInvalidCredentialsCreateNoRecoveryState() throws Exception {
    var canonicalResponse = performInvalidAttempt();
    assertSoftly(
        softly -> {
          softly.assertThat(canonicalResponse.status()).isEqualTo(402);
          softly.assertThat(canonicalResponse.contentType()).isEqualTo("application/problem+json");
          softly.assertThat(canonicalResponse.cacheControl()).isEqualTo("no-store");
          softly
              .assertThat(canonicalResponse.wwwAuthenticate())
              .as("presented-invalid responses must not advertise a replacement challenge")
              .isEmpty();
          softly.assertThat(canonicalResponse.body()).isEqualTo(SAFE_RESPONSE_BODY);
        });

    for (int attempt = 1; attempt < ATTEMPTS; attempt++) {
      var currentResponse = performInvalidAttempt();
      if (!canonicalResponse.equals(currentResponse)) {
        throw new AssertionError(
            "presented-invalid attempt "
                + (attempt + 1)
                + " differed from the canonical safe response: "
                + currentResponse);
      }
    }

    assertSoftly(
        softly -> {
          softly
              .assertThat(counters.invoiceCreations())
              .as("presented-invalid requests must not create replacement invoices")
              .isZero();
          softly
              .assertThat(counters.rootKeyGenerations())
              .as("presented-invalid requests must not persist replacement root keys")
              .isZero();
          softly
              .assertThat(counters.handlerExecutions())
              .as("invalid credentials must never reach the protected handler")
              .isZero();
        });
  }

  private SafeResponse performInvalidAttempt() throws Exception {
    return SafeResponse.from(
        mockMvc
            .perform(get(PROTECTED_PATH).header("Authorization", INVALID_AUTHORIZATION))
            .andReturn()
            .getResponse());
  }

  private record SafeResponse(
      int status,
      String contentType,
      String cacheControl,
      List<String> wwwAuthenticate,
      String body) {

    private static SafeResponse from(org.springframework.mock.web.MockHttpServletResponse response)
        throws java.io.UnsupportedEncodingException {
      return new SafeResponse(
          response.getStatus(),
          response.getContentType(),
          response.getHeader("Cache-Control"),
          List.copyOf(response.getHeaders("WWW-Authenticate")),
          response.getContentAsString());
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(TestConfiguration.class)
  static class TestApplication {}

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class TestConfiguration {

    @Bean
    WorkCounters workCounters() {
      return new WorkCounters();
    }

    @Bean
    RootKeyStore rootKeyStore(WorkCounters counters) {
      return new CountingRootKeyStore(counters);
    }

    @Bean
    LightningBackend lightningBackend(WorkCounters counters) {
      return new CountingLightningBackend(counters);
    }

    @Bean
    PaygateRateLimiter paygateRateLimiter() {
      return _ -> true;
    }

    @Bean
    PaymentProtocol abuseProtocol() {
      return new InvalidPresentedCredentialProtocol();
    }

    @Bean
    AbuseController abuseController(WorkCounters counters) {
      return new AbuseController(counters);
    }
  }

  @RestController
  static class AbuseController {
    private final WorkCounters counters;

    AbuseController(WorkCounters counters) {
      this.counters = counters;
    }

    @PaymentRequired(priceSats = 1, description = "Abuse boundary")
    @GetMapping(PROTECTED_PATH)
    Map<String, String> protectedHandler() {
      counters.handlerExecutions.incrementAndGet();
      return Map.of("status", "unexpected");
    }
  }

  static final class InvalidPresentedCredentialProtocol implements PaymentProtocol {

    @Override
    public String scheme() {
      return "Abuse";
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      return authorizationHeader != null && authorizationHeader.startsWith("Abuse ");
    }

    @Override
    public PaymentCredential parseCredential(String authorizationHeader) {
      return new PaymentCredential(
          new byte[32], new byte[32], "invalid-token", scheme(), null, new ProtocolMetadata() {});
    }

    @Override
    public ChallengeResponse formatChallenge(ChallengeContext context) {
      return new ChallengeResponse("Abuse recovery=unavailable", scheme(), null);
    }

    @Override
    public void validate(PaymentCredential credential, Map<String, String> requestContext) {
      throw new PaymentValidationException(
          PaymentValidationException.ErrorCode.INVALID, "invalid presented credential");
    }
  }

  static final class CountingRootKeyStore implements RootKeyStore {
    private final WorkCounters counters;

    CountingRootKeyStore(WorkCounters counters) {
      this.counters = counters;
    }

    @Override
    public GenerationResult generateRootKey() {
      counters.rootKeyGenerations.incrementAndGet();
      return new GenerationResult(new SensitiveBytes(new byte[32]), new byte[32]);
    }

    @Override
    public SensitiveBytes getRootKey(byte[] keyId) {
      return null;
    }

    @Override
    public void revokeRootKey(byte[] keyId) {}
  }

  static final class CountingLightningBackend implements LightningBackend {
    private final WorkCounters counters;

    CountingLightningBackend(WorkCounters counters) {
      this.counters = counters;
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      counters.invoiceCreations.incrementAndGet();
      var now = Instant.EPOCH;
      return new Invoice(
          new byte[32],
          "lnbc1abuseboundary",
          amountSats,
          memo,
          InvoiceStatus.PENDING,
          null,
          now,
          now.plusSeconds(60));
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
      throw new AssertionError("invalid presented credentials must not trigger invoice lookup");
    }

    @Override
    public boolean isHealthy() {
      return true;
    }
  }

  static final class WorkCounters {
    private final AtomicInteger invoiceCreations = new AtomicInteger();
    private final AtomicInteger rootKeyGenerations = new AtomicInteger();
    private final AtomicInteger handlerExecutions = new AtomicInteger();

    int invoiceCreations() {
      return invoiceCreations.get();
    }

    int rootKeyGenerations() {
      return rootKeyGenerations.get();
    }

    int handlerExecutions() {
      return handlerExecutions.get();
    }

    void reset() {
      invoiceCreations.set(0);
      rootKeyGenerations.set(0);
      handlerExecutions.set(0);
    }
  }
}
