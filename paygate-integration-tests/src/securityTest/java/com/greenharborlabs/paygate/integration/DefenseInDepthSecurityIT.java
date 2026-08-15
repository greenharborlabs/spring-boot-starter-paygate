package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.lightning.LightningException;
import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@DisplayName("Defense-in-depth release security")
class DefenseInDepthSecurityIT {

  private static final String MPP_SECRET = "release-gate-mpp-secret-at-least-32-bytes";
  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String PAID_PATH = "/release/paid";
  private static final String FORWARD_PATH = "/release/forward-to-paid";

  @Nested
  @SpringBootTest(
      classes = {SecurityExampleApplication.class, SharedTestConfiguration.class},
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.service-name=release-gate",
        "paygate.security-mode=spring-security",
        "paygate.health-cache.enabled=false",
        "paygate.protocols.mpp.challenge-binding-secret=release-gate-mpp-secret-at-least-32-bytes"
      })
  @DisplayName("Spring Security mode")
  class SpringSecurityMode {

    @LocalServerPort private int port;

    @Autowired private BackendControl backend;

    @Autowired private HandlerCalls handlerCalls;

    @Autowired private ProtocolControl protocol;

    @BeforeEach
    void resetState() {
      backend.setHealthy(true);
      handlerCalls.reset();
      protocol.setChallengeFormattingAvailable(true);
    }

    @Test
    @DisplayName("missing payment receives the dual-protocol challenge before authorization")
    void missingPaymentIsRejectedBeforeDownstreamAuthorization() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response = get(client, port, PAID_PATH, null);

        assertDualProtocolChallenge(response);
        assertThat(handlerCalls.get()).isZero();
      }
    }

    @Test
    @DisplayName("accepted Go-compatible L402 and canonical MPP vectors reach the handler")
    void acceptsL402AndMppInteroperabilityVectors() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertAcceptedL402(client, port, handlerCalls);
        assertAcceptedMpp(client, port, handlerCalls);

        assertThat(handlerCalls.get()).isEqualTo(2);
      }
    }

    @Test
    @DisplayName("unrelated authentication cannot waive payment")
    void unrelatedAuthenticationCannotWaivePayment() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response = get(client, port, PAID_PATH, "Bearer unrelated-credential");

        assertRejectedSafely(response);
        assertThat(response.statusCode()).isEqualTo(402);
        assertThat(handlerCalls.get()).isZero();
      }
    }

    @Test
    @DisplayName("MPP challenge formatting failure preserves L402 and later recovers")
    void partialDualProtocolFailurePreservesHealthyChallengeAndRecovers() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        protocol.setChallengeFormattingAvailable(false);
        var partial = get(client, port, PAID_PATH, null);

        assertThat(partial.statusCode()).isEqualTo(402);
        assertThat(partial.headers().allValues("WWW-Authenticate"))
            .singleElement()
            .satisfies(header -> assertThat(header).startsWith("L402"));
        assertThat(partial.body())
            .doesNotContain("PaymentValidationException", "simulated", MPP_SECRET);
        assertThat(handlerCalls.get()).isZero();

        protocol.setChallengeFormattingAvailable(true);
        var recovered = get(client, port, PAID_PATH, null);

        assertDualProtocolChallenge(recovered);
        assertThat(handlerCalls.get()).isZero();
      }
    }

    @Test
    @DisplayName("backend outage fails closed and challenge issuance recovers")
    void backendOutageFailsClosed() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertBackendOutageAndRecovery(client, port, backend, handlerCalls);
      }
    }
  }

  @Nested
  @SpringBootTest(
      classes = ServletApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @TestPropertySource(
      properties = {
        "paygate.enabled=true",
        "paygate.test-mode=true",
        "paygate.root-key-store=memory",
        "paygate.service-name=release-gate",
        "paygate.security-mode=servlet",
        "paygate.health-cache.enabled=false",
        "paygate.protocols.mpp.challenge-binding-secret=release-gate-mpp-secret-at-least-32-bytes"
      })
  @DisplayName("Servlet filter mode")
  class ServletMode {

    @LocalServerPort private int port;

    @Autowired private BackendControl backend;

    @Autowired private HandlerCalls handlerCalls;

    @Autowired private ProtocolControl protocol;

    @BeforeEach
    void resetState() {
      backend.setHealthy(true);
      handlerCalls.reset();
      protocol.setChallengeFormattingAvailable(true);
    }

    @Test
    @DisplayName("missing payment receives the same dual-protocol challenge outcome")
    void missingPaymentMatchesSpringSecurityOutcome() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response = get(client, port, PAID_PATH, null);

        assertDualProtocolChallenge(response);
        assertThat(handlerCalls.get()).isZero();
      }
    }

    @Test
    @DisplayName("accepted Go-compatible L402 and canonical MPP vectors reach the handler")
    void acceptsL402AndMppInteroperabilityVectors() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertAcceptedL402(client, port, handlerCalls);
        assertAcceptedMpp(client, port, handlerCalls);

        assertThat(handlerCalls.get()).isEqualTo(2);
      }
    }

    @Test
    @DisplayName("unrelated authentication has the same fail-closed outcome")
    void unrelatedAuthenticationCannotWaivePayment() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response = get(client, port, PAID_PATH, "Bearer unrelated-credential");

        assertRejectedSafely(response);
        assertThat(response.statusCode()).isEqualTo(402);
        assertThat(handlerCalls.get()).isZero();
      }
    }

    @Test
    @DisplayName("forward redispatch to a paid target is re-enforced")
    void forwardRedispatchCannotBypassPayment() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        var response = get(client, port, FORWARD_PATH, null);

        assertDualProtocolChallenge(response);
        assertThat(handlerCalls.get()).isZero();
      }
    }

    @Test
    @DisplayName("backend outage fails closed and challenge issuance recovers")
    void backendOutageFailsClosed() throws Exception {
      try (var client = HttpClient.newHttpClient()) {
        assertBackendOutageAndRecovery(client, port, backend, handlerCalls);
      }
    }
  }

  private static HttpResponse<String> get(
      HttpClient client, int port, String path, String authorization) throws Exception {
    var builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void assertAcceptedL402(HttpClient client, int port, HandlerCalls handlerCalls)
      throws Exception {
    var challenge = get(client, port, PAID_PATH, null);
    assertDualProtocolChallenge(challenge);
    var body = responseBody(challenge);
    var header =
        challenge.headers().allValues("WWW-Authenticate").stream()
            .filter(value -> value.startsWith("L402"))
            .findFirst()
            .orElseThrow();
    var matcher = MACAROON_PATTERN.matcher(header);
    assertThat(matcher.find()).isTrue();
    var macaroon = matcher.group(1);
    var macaroonBytes = Base64.getDecoder().decode(macaroon);
    assertThat(macaroonBytes[0]).as("go-macaroon V2 wire discriminator").isEqualTo((byte) 0x02);
    var preimage = (String) body.get("test_preimage");
    assertThat(preimage).matches("[0-9a-f]{64}");

    var accepted = get(client, port, PAID_PATH, "L402 " + macaroon + ":" + preimage);

    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(accepted.body()).contains("\"result\":\"paid\"");
    assertThat(handlerCalls.get()).isEqualTo(1);
  }

  @SuppressWarnings("unchecked")
  private static void assertAcceptedMpp(HttpClient client, int port, HandlerCalls handlerCalls)
      throws Exception {
    var challenge = get(client, port, PAID_PATH, null);
    assertDualProtocolChallenge(challenge);
    var body = responseBody(challenge);
    var protocols = (Map<String, Object>) body.get("protocols");
    var paymentChallenge = new TreeMap<>((Map<String, Object>) protocols.get("Payment"));
    var payload = new TreeMap<String, Object>();
    var preimage = (String) body.get("test_preimage");
    assertThat(preimage).matches("[0-9a-f]{64}");
    payload.put("preimage", preimage);
    var credential = new TreeMap<String, Object>();
    credential.put("challenge", paymentChallenge);
    credential.put("payload", payload);
    credential.put("source", "release-vector");
    var credentialJson = MAPPER.writeValueAsBytes(credential);
    var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialJson);
    assertThat(encoded).doesNotContain("=").matches("[A-Za-z0-9_-]+");

    var accepted = get(client, port, PAID_PATH, "Payment " + encoded);

    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(accepted.headers().firstValue("Payment-Receipt")).isPresent();
    assertThat(accepted.body()).contains("\"result\":\"paid\"");
    assertThat(handlerCalls.get()).isEqualTo(2);
  }

  private static void assertBackendOutageAndRecovery(
      HttpClient client, int port, BackendControl backend, HandlerCalls handlerCalls)
      throws Exception {
    backend.setHealthy(false);
    var unavailable = get(client, port, PAID_PATH, null);

    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(unavailable.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(unavailable.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(unavailable.body())
        .contains("\"error\": \"LIGHTNING_UNAVAILABLE\"")
        .doesNotContain(
            MPP_SECRET,
            "ControlledLightningBackend",
            "LightningException",
            "preimage",
            "macaroon",
            "invoice");
    assertThat(handlerCalls.get()).isZero();

    backend.setHealthy(true);
    var recovered = get(client, port, PAID_PATH, null);

    assertDualProtocolChallenge(recovered);
    assertThat(handlerCalls.get()).isZero();
  }

  private static void assertDualProtocolChallenge(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(402);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    List<String> challenges = response.headers().allValues("WWW-Authenticate");
    assertThat(challenges).hasSize(2);
    assertThat(challenges.get(0)).startsWith("L402");
    assertThat(challenges.get(1)).startsWith("Payment");
  }

  private static void assertRejectedSafely(HttpResponse<String> response) {
    assertThat(response.statusCode()).isNotEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    assertThat(response.body())
        .doesNotContain(
            MPP_SECRET,
            "ControlledLightningBackend",
            "PaymentValidationException",
            "preimage",
            "macaroon");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> responseBody(HttpResponse<String> response) throws Exception {
    return MAPPER.readValue(response.body(), Map.class);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({SharedTestConfiguration.class, ServletSecurityConfiguration.class})
  static class ServletApplication {}

  @TestConfiguration(proxyBeanMethods = false)
  static class SharedTestConfiguration {

    @Bean
    HandlerCalls handlerCalls() {
      return new HandlerCalls();
    }

    @Bean
    BackendControl backendControl() {
      return new BackendControl();
    }

    @Bean
    ProtocolControl protocolControl() {
      return new ProtocolControl();
    }

    @Bean
    LightningBackend lightningBackend(BackendControl control) {
      return new ControlledLightningBackend(control);
    }

    @Bean(name = "mppProtocol", destroyMethod = "close")
    @Order(2)
    PaymentProtocol mppProtocol(ProtocolControl control) {
      return new RecoverableMppProtocol(control, MPP_SECRET);
    }

    @Bean
    PaidController paidController(HandlerCalls handlerCalls) {
      return new PaidController(handlerCalls);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class ServletSecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
          .csrf(AbstractHttpConfigurer::disable)
          .build();
    }
  }

  @RestController
  static class PaidController {

    private final HandlerCalls handlerCalls;

    PaidController(HandlerCalls handlerCalls) {
      this.handlerCalls = handlerCalls;
    }

    @PaymentRequired(priceSats = 21, description = "Release security gate")
    @GetMapping(PAID_PATH)
    Map<String, String> paid() {
      handlerCalls.increment();
      return Map.of("result", "paid");
    }

    @GetMapping(FORWARD_PATH)
    void forwardToPaid(HttpServletRequest request, HttpServletResponse response) throws Exception {
      request.getRequestDispatcher(PAID_PATH).forward(request, response);
    }
  }

  static final class HandlerCalls {

    private final AtomicInteger count = new AtomicInteger();

    void increment() {
      count.incrementAndGet();
    }

    int get() {
      return count.get();
    }

    void reset() {
      count.set(0);
    }
  }

  static final class BackendControl {

    private final AtomicBoolean healthy = new AtomicBoolean(true);

    boolean isHealthy() {
      return healthy.get();
    }

    void setHealthy(boolean value) {
      healthy.set(value);
    }
  }

  static final class ProtocolControl {

    private final AtomicBoolean challengeFormattingAvailable = new AtomicBoolean(true);

    boolean isChallengeFormattingAvailable() {
      return challengeFormattingAvailable.get();
    }

    void setChallengeFormattingAvailable(boolean value) {
      challengeFormattingAvailable.set(value);
    }
  }

  static final class RecoverableMppProtocol implements PaymentProtocol, AutoCloseable {

    private final ProtocolControl control;
    private final PaymentProtocol delegate;

    RecoverableMppProtocol(ProtocolControl control, String secretValue) {
      this.control = control;
      var secret = new SensitiveBytes(secretValue.getBytes(StandardCharsets.UTF_8));
      try {
        var type = Class.forName("com.greenharborlabs.paygate.protocol.mpp.MppProtocol");
        delegate = (PaymentProtocol) type.getConstructor(SensitiveBytes.class).newInstance(secret);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("MPP protocol is unavailable to the integration test", e);
      } finally {
        secret.close();
      }
    }

    @Override
    public String scheme() {
      return delegate.scheme();
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      return delegate.canHandle(authorizationHeader);
    }

    @Override
    public PaymentCredential parseCredential(String authorizationHeader) {
      return delegate.parseCredential(authorizationHeader);
    }

    @Override
    public ChallengeResponse formatChallenge(ChallengeContext context) {
      if (!control.isChallengeFormattingAvailable()) {
        throw new PaymentValidationException(
            ErrorCode.UNAVAILABLE, "simulated MPP challenge formatter outage");
      }
      return delegate.formatChallenge(context);
    }

    @Override
    public void validate(PaymentCredential credential, Map<String, String> requestContext) {
      delegate.validate(credential, requestContext);
    }

    @Override
    public Optional<PaymentReceipt> createReceipt(
        PaymentCredential credential, ChallengeContext context) {
      return delegate.createReceipt(credential, context);
    }

    @Override
    public void close() throws Exception {
      if (delegate instanceof AutoCloseable closeable) {
        closeable.close();
      }
    }
  }

  static final class ControlledLightningBackend implements LightningBackend {

    private static final Duration INVOICE_LIFETIME = Duration.ofHours(1);

    private final BackendControl control;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, byte[]> preimages = new ConcurrentHashMap<>();

    ControlledLightningBackend(BackendControl control) {
      this.control = control;
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      if (!control.isHealthy()) {
        throw new LightningException("simulated backend outage");
      }
      var preimage = new byte[32];
      var number = sequence.incrementAndGet();
      preimage[28] = (byte) (number >>> 24);
      preimage[29] = (byte) (number >>> 16);
      preimage[30] = (byte) (number >>> 8);
      preimage[31] = (byte) number;
      var paymentHash = sha256(preimage);
      preimages.put(HexFormat.of().formatHex(paymentHash), preimage.clone());
      var now = Instant.now();
      return new Invoice(
          paymentHash,
          "lntb" + amountSats + "release" + number,
          amountSats,
          memo,
          InvoiceStatus.PENDING,
          preimage,
          now,
          now.plus(INVOICE_LIFETIME));
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
      if (!control.isHealthy()) {
        throw new LightningException("simulated backend outage");
      }
      var preimage = preimages.get(HexFormat.of().formatHex(paymentHash));
      var now = Instant.now();
      return new Invoice(
          paymentHash,
          "lntb21release-settled",
          21,
          "Release security gate",
          preimage == null ? InvoiceStatus.PENDING : InvoiceStatus.SETTLED,
          preimage,
          now.minusSeconds(1),
          now.plus(INVOICE_LIFETIME));
    }

    @Override
    public boolean isHealthy() {
      return control.isHealthy();
    }

    private static byte[] sha256(byte[] value) {
      try {
        return MessageDigest.getInstance("SHA-256").digest(value);
      } catch (NoSuchAlgorithmException e) {
        throw new AssertionError("SHA-256 is required", e);
      }
    }
  }
}
