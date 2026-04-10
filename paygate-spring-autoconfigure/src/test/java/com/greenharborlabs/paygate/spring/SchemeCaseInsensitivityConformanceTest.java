package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;
import com.greenharborlabs.paygate.protocol.mpp.MppProtocol;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Protocol conformance: case-insensitive auth scheme dispatch (servlet filter)")
class SchemeCaseInsensitivityConformanceTest {

  private static final String SERVICE_NAME = "test-service";
  private static final byte[] PAYMENT_HASH = new byte[32];
  private static final byte[] PREIMAGE = new byte[32];
  private static final PaymentCredential L402_CREDENTIAL =
      new PaymentCredential(
          PAYMENT_HASH, PREIMAGE, "l402-token", "L402", null, new ProtocolMetadata() {});
  private static final PaymentCredential MPP_CREDENTIAL =
      new PaymentCredential(
          PAYMENT_HASH, PREIMAGE, "mpp-token", "Payment", null, new ProtocolMetadata() {});

  private PaygateEndpointRegistry registry;
  private FilterChain filterChain;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void setUp() {
    registry = new PaygateEndpointRegistry();
    registry.register(
        new PaygateEndpointConfig("GET", "/api/protected", 100L, 3600L, "test", "", ""));
    filterChain = mock(FilterChain.class);
    request = new MockHttpServletRequest("GET", "/api/protected");
    response = new MockHttpServletResponse();
  }

  @ParameterizedTest(name = "[{index}] header=''{0}'' -> {1}")
  @CsvSource({
    "L402 abc:def,L402",
    "l402 abc:def,L402",
    "LsAt abc:def,L402",
    "lsat abc:def,L402",
    "Payment token=xyz,Payment",
    "payment token=xyz,Payment",
    "PAYMENT token=xyz,Payment",
    "PaYmEnT token=xyz,Payment"
  })
  void dispatchesToExpectedProtocolRegardlessOfSchemeCase(
      String authorization, String expectedScheme) throws Exception {
    var l402Delegate = new L402Protocol(mock(L402Validator.class), SERVICE_NAME);
    var mppDelegate = new MppProtocol(new SensitiveBytes(new byte[32]));

    var l402Counter = new AtomicInteger();
    var mppCounter = new AtomicInteger();

    var l402Counting =
        new CountingPaymentProtocol(l402Delegate, l402Counter, L402_CREDENTIAL, "L402");
    var mppCounting =
        new CountingPaymentProtocol(mppDelegate, mppCounter, MPP_CREDENTIAL, "Payment");

    var filter =
        new PaygateSecurityFilter(
            registry,
            List.of(l402Counting, mppCounting),
            mock(PaygateChallengeService.class),
            SERVICE_NAME,
            null,
            null,
            null,
            null);

    request.addHeader("Authorization", authorization);
    filter.doFilter(request, response, filterChain);

    if ("L402".equals(expectedScheme)) {
      assertThat(l402Counter.get()).isEqualTo(1);
      assertThat(mppCounter.get()).isZero();
    } else {
      assertThat(mppCounter.get()).isEqualTo(1);
      assertThat(l402Counter.get()).isZero();
    }
    verify(filterChain).doFilter(any(HttpServletRequest.class), eq(response));
  }

  private static final class CountingPaymentProtocol implements PaymentProtocol {

    private final PaymentProtocol delegateCanHandle;
    private final AtomicInteger parseCount;
    private final PaymentCredential parsedCredential;
    private final String scheme;

    private CountingPaymentProtocol(
        PaymentProtocol delegateCanHandle,
        AtomicInteger parseCount,
        PaymentCredential parsedCredential,
        String scheme) {
      this.delegateCanHandle = delegateCanHandle;
      this.parseCount = parseCount;
      this.parsedCredential = parsedCredential;
      this.scheme = scheme;
    }

    @Override
    public String scheme() {
      return scheme;
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      return delegateCanHandle.canHandle(authorizationHeader);
    }

    @Override
    public PaymentCredential parseCredential(String authorizationHeader)
        throws PaymentValidationException {
      parseCount.incrementAndGet();
      return parsedCredential;
    }

    @Override
    public ChallengeResponse formatChallenge(ChallengeContext context) {
      throw new UnsupportedOperationException("Not used by this conformance test");
    }

    @Override
    public void validate(PaymentCredential credential, Map<String, String> requestContext)
        throws PaymentValidationException {
      // Intentionally no-op: this conformance test targets dispatch only.
    }

    @Override
    public Optional<com.greenharborlabs.paygate.api.PaymentReceipt> createReceipt(
        PaymentCredential credential, ChallengeContext context) {
      return Optional.empty();
    }
  }
}
