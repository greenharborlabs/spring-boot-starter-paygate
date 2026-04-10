package com.greenharborlabs.paygate.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.ProtocolMetadata;
import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Dual-protocol (L402 + MPP) security integration tests.
 *
 * <p>Tests the full filter-to-provider-to-authenticated-token chain for both L402 and
 * protocol-agnostic (MPP) credential flows through the Spring Security layer. Uses unit tests with
 * mocks -- no Spring Boot context is loaded.
 */
@ExtendWith(MockitoExtension.class)
class DualProtocolSecurityTest {

  private static final SecureRandom RNG = new SecureRandom();
  private static final String SERVICE_NAME = "test-service";
  private static final String VALID_PREIMAGE_HEX = "ab".repeat(32);
  private static final String VALID_MACAROON_B64 = "dGVzdG1hY2Fyb29u";

  @Mock private L402Validator l402Validator;

  @Mock private PaymentProtocol mppProtocol;

  @Mock private PaygateEndpointRegistry endpointRegistry;

  @Mock private FilterChain filterChain;

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  private static final PaygateEndpointConfig DEFAULT_CONFIG =
      new PaygateEndpointConfig("GET", "/", 10, 3600, "default", "", null);

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    SecurityContextHolder.clearContext();
  }

  // ========== Scenario 1: L402 credential populates SecurityContext with L402 scheme ==========

  @Nested
  @DisplayName("Scenario 1: L402 credential populates SecurityContext with L402 scheme")
  class L402PopulatesSecurityContext {

    @Test
    void l402CredentialFlowsThroughFilterAndProvider() throws Exception {
      L402Credential credential = createTestL402Credential(List.of(new Caveat("service", "api")));
      when(l402Validator.validate(
              any(L402HeaderComponents.class), any(L402VerificationContext.class)))
          .thenReturn(new L402Validator.ValidationResult(credential, true));
      when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(DEFAULT_CONFIG);

      var provider =
          new PaygateAuthenticationProvider(l402Validator, List.of(mppProtocol), SERVICE_NAME);
      var authManager = new ProviderManager(provider);
      var filter =
          new PaygateAuthenticationFilter(authManager, List.of(mppProtocol), endpointRegistry);

      request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE_HEX);

      filter.doFilterInternal(request, response, filterChain);

      var authentication = SecurityContextHolder.getContext().getAuthentication();
      assertThat(authentication).isNotNull().isInstanceOf(PaygateAuthenticationToken.class);

      var token = (PaygateAuthenticationToken) authentication;
      assertThat(token.isAuthenticated()).isTrue();
      assertThat(token.getProtocolScheme()).isEqualTo("L402");
      assertThat(token.getTokenId()).isEqualTo(credential.tokenId());
      assertThat(token.getL402Credential()).isEqualTo(credential);
      assertThat(token.getAuthorities())
          .extracting(GrantedAuthority::getAuthority)
          .containsExactlyInAnyOrder("ROLE_PAYMENT", "ROLE_L402");

      verify(filterChain).doFilter(request, response);
    }
  }

  // ========== Scenario 2: MPP credential populates SecurityContext with Payment scheme ==========

  @Nested
  @DisplayName("Scenario 2: MPP credential populates SecurityContext with Payment scheme")
  class MppPopulatesSecurityContext {

    @Test
    void mppCredentialFlowsThroughFilterAndProvider() throws Exception {
      String authHeader = "Payment preimage=abc123";
      PaymentCredential credential = createTestPaymentCredential("Payment", null);

      when(mppProtocol.canHandle(authHeader)).thenReturn(true);
      when(mppProtocol.parseCredential(authHeader)).thenReturn(credential);
      when(mppProtocol.scheme()).thenReturn("Payment");
      when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(DEFAULT_CONFIG);

      var provider =
          new PaygateAuthenticationProvider(l402Validator, List.of(mppProtocol), SERVICE_NAME);
      var authManager = new ProviderManager(provider);
      var filter =
          new PaygateAuthenticationFilter(authManager, List.of(mppProtocol), endpointRegistry);

      request.addHeader("Authorization", authHeader);

      filter.doFilterInternal(request, response, filterChain);

      var authentication = SecurityContextHolder.getContext().getAuthentication();
      assertThat(authentication).isNotNull().isInstanceOf(PaygateAuthenticationToken.class);

      var token = (PaygateAuthenticationToken) authentication;
      assertThat(token.isAuthenticated()).isTrue();
      assertThat(token.getProtocolScheme()).isEqualTo("Payment");
      assertThat(token.getTokenId()).isEqualTo(credential.tokenId());
      assertThat(token.getPaymentCredential()).isEqualTo(credential);
      assertThat(token.getL402Credential()).isNull();
      assertThat(token.getAuthorities())
          .extracting(GrantedAuthority::getAuthority)
          .containsExactly("ROLE_PAYMENT");
      assertThat(token.getAuthorities())
          .extracting(GrantedAuthority::getAuthority)
          .doesNotContain("ROLE_L402");

      verify(filterChain).doFilter(any(HttpServletRequest.class), eq(response));
    }
  }

  // ========== Scenario 3: Dual-header 402 from entry point ==========

  @Nested
  @DisplayName("Scenario 3: Dual-header 402 from entry point")
  class DualHeader402EntryPoint {

    @Mock private PaygateChallengeService challengeService;

    @Test
    void entryPointIssuesMultipleWwwAuthenticateHeaders() throws Exception {
      PaymentProtocol l402Protocol = mock(PaymentProtocol.class);
      PaymentProtocol paymentProtocol = mock(PaymentProtocol.class);

      var l402Challenge =
          new ChallengeResponse(
              "L402 token=\"tok\", invoice=\"lnbc1\"", "L402", Map.of("macaroon", "mac-data"));
      var mppChallenge =
          new ChallengeResponse(
              "Payment hash=\"deadbeef\", invoice=\"lnbc1\"",
              "Payment",
              Map.of("payment_hash", "deadbeef"));

      when(l402Protocol.formatChallenge(any())).thenReturn(l402Challenge);
      when(paymentProtocol.formatChallenge(any())).thenReturn(mppChallenge);

      var config =
          new PaygateEndpointConfig("GET", "/api/protected", 100, 3600, "Test", "", "read");
      when(endpointRegistry.findConfig("GET", "/api/protected")).thenReturn(config);

      var challengeContext =
          new ChallengeContext(
              new byte[32],
              "aa".repeat(32),
              "lnbc1000n1test",
              100,
              "Test",
              SERVICE_NAME,
              3600,
              "",
              new byte[32],
              null,
              null);
      when(challengeService.createChallenge(any(), eq(config))).thenReturn(challengeContext);

      var entryPoint =
          new PaygateAuthenticationEntryPoint(
              challengeService, endpointRegistry, List.of(l402Protocol, paymentProtocol));

      request.setMethod("GET");
      request.setRequestURI("/api/protected");

      entryPoint.commence(request, response, new BadCredentialsException("test"));

      assertThat(response.getStatus()).isEqualTo(402);

      List<String> wwwAuthHeaders = response.getHeaders("WWW-Authenticate").stream().toList();
      assertThat(wwwAuthHeaders).hasSize(2);
      assertThat(wwwAuthHeaders.get(0)).startsWith("L402 ");
      assertThat(wwwAuthHeaders.get(1)).startsWith("Payment ");

      String body = response.getContentAsString();
      assertThat(body).contains("\"protocols\":");
    }
  }

  // ========== Scenario 4: Principal carries protocol metadata ==========

  @Nested
  @DisplayName("Scenario 4: Principal carries protocol metadata")
  class PrincipalCarriesProtocolMetadata {

    @Test
    void mppAuthenticatedTokenAttributesContainProtocolSchemeAndTokenId() {
      PaymentCredential cred = createTestPaymentCredential("Payment", null);
      var token = PaygateAuthenticationToken.authenticated(cred, SERVICE_NAME);

      assertThat(token.getAttributes())
          .containsEntry("protocolScheme", "Payment")
          .containsEntry("tokenId", cred.tokenId());
    }

    @Test
    void mppAuthenticatedTokenWithSourceIncludesSourceAttribute() {
      PaymentCredential cred = createTestPaymentCredential("Payment", "did:key:z6MkTest");
      var token = PaygateAuthenticationToken.authenticated(cred, SERVICE_NAME);

      assertThat(token.getAttributes())
          .containsEntry("source", "did:key:z6MkTest")
          .containsEntry("protocolScheme", "Payment")
          .containsEntry("tokenId", cred.tokenId());
    }

    @Test
    void l402AuthenticatedTokenAttributesContainCaveatDerivedValues() {
      L402Credential credential =
          createTestL402Credential(
              List.of(new Caveat("service", "api"), new Caveat("tier", "premium")));
      var token = PaygateAuthenticationToken.authenticated(credential, SERVICE_NAME);

      assertThat(token.getAttributes())
          .containsEntry("service", "api")
          .containsEntry("tier", "premium")
          .containsEntry("tokenId", credential.tokenId())
          .containsEntry("serviceName", SERVICE_NAME);
    }
  }

  // ========== Scenario 5: Filter routes L402 to components path, MPP to authorizationHeader path
  // ==========

  @Nested
  @DisplayName("Scenario 5: Filter routes L402 to components path, MPP to authorizationHeader path")
  class FilterRoutesProtocols {

    @Test
    void l402HeaderProducesTokenWithComponentsNotAuthorizationHeader() throws Exception {
      L402Credential credential = createTestL402Credential(List.of());
      when(l402Validator.validate(
              any(L402HeaderComponents.class), any(L402VerificationContext.class)))
          .thenReturn(new L402Validator.ValidationResult(credential, true));
      when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(DEFAULT_CONFIG);

      var provider =
          new PaygateAuthenticationProvider(l402Validator, List.of(mppProtocol), SERVICE_NAME);
      var authManager = new ProviderManager(provider);
      var filter =
          new PaygateAuthenticationFilter(authManager, List.of(mppProtocol), endpointRegistry);

      request.addHeader("Authorization", "L402 " + VALID_MACAROON_B64 + ":" + VALID_PREIMAGE_HEX);

      filter.doFilterInternal(request, response, filterChain);

      // Capture the unauthenticated token passed to the provider
      ArgumentCaptor<L402HeaderComponents> componentsCaptor =
          ArgumentCaptor.forClass(L402HeaderComponents.class);
      verify(l402Validator)
          .validate(componentsCaptor.capture(), any(L402VerificationContext.class));

      assertThat(componentsCaptor.getValue()).isNotNull();
      assertThat(componentsCaptor.getValue().macaroonBase64()).isEqualTo(VALID_MACAROON_B64);

      // The authenticated token should not have authorizationHeader set
      var token =
          (PaygateAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
      assertThat(token.getAuthorizationHeader()).isNull();
    }

    @Test
    void mppHeaderProducesTokenWithAuthorizationHeaderNotComponents() throws Exception {
      String authHeader = "Payment preimage=abc123";
      PaymentCredential credential = createTestPaymentCredential("Payment", null);

      when(mppProtocol.canHandle(authHeader)).thenReturn(true);
      when(mppProtocol.parseCredential(authHeader)).thenReturn(credential);
      when(mppProtocol.scheme()).thenReturn("Payment");
      when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(DEFAULT_CONFIG);

      var provider =
          new PaygateAuthenticationProvider(l402Validator, List.of(mppProtocol), SERVICE_NAME);
      var authManager = new ProviderManager(provider);
      var filter =
          new PaygateAuthenticationFilter(authManager, List.of(mppProtocol), endpointRegistry);

      request.addHeader("Authorization", authHeader);

      filter.doFilterInternal(request, response, filterChain);

      // Verify the protocol received the header (not components)
      verify(mppProtocol).parseCredential(authHeader);

      // The authenticated token should not have components set
      var token =
          (PaygateAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
      assertThat(token.getComponents()).isNull();
      assertThat(token.getL402Credential()).isNull();
    }
  }

  // ========== Scenario 6: Provider passes requestMetadata to MPP validation ==========

  @Nested
  @DisplayName("Scenario 6: Provider passes requestMetadata to MPP validation")
  class ProviderPassesRequestMetadata {

    @Test
    void mppValidationReceivesRequestMetadataFromFilter() throws Exception {
      String authHeader = "Payment preimage=abc123";
      PaymentCredential credential = createTestPaymentCredential("Payment", null);

      when(mppProtocol.canHandle(authHeader)).thenReturn(true);
      when(mppProtocol.parseCredential(authHeader)).thenReturn(credential);
      when(mppProtocol.scheme()).thenReturn("Payment");
      when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(DEFAULT_CONFIG);

      var provider =
          new PaygateAuthenticationProvider(l402Validator, List.of(mppProtocol), SERVICE_NAME);
      var authManager = new ProviderManager(provider);
      var filter =
          new PaygateAuthenticationFilter(authManager, List.of(mppProtocol), endpointRegistry);

      request.setMethod("POST");
      request.setRequestURI("/api/resource");
      request.addHeader("Authorization", authHeader);

      filter.doFilterInternal(request, response, filterChain);

      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
      verify(mppProtocol).validate(eq(credential), metadataCaptor.capture());

      Map<String, String> metadata = metadataCaptor.getValue();
      assertThat(metadata)
          .containsEntry(VerificationContextKeys.REQUEST_PATH, "/api/resource")
          .containsEntry(VerificationContextKeys.REQUEST_METHOD, "POST");
      assertThat(metadata).containsKey(VerificationContextKeys.REQUEST_CLIENT_IP);
    }

    @Test
    void mppValidationReceivesClientIpInMetadata() throws Exception {
      String authHeader = "Payment preimage=abc123";
      PaymentCredential credential = createTestPaymentCredential("Payment", null);

      when(mppProtocol.canHandle(authHeader)).thenReturn(true);
      when(mppProtocol.parseCredential(authHeader)).thenReturn(credential);
      when(mppProtocol.scheme()).thenReturn("Payment");
      when(endpointRegistry.findConfig(anyString(), anyString())).thenReturn(DEFAULT_CONFIG);

      var provider =
          new PaygateAuthenticationProvider(l402Validator, List.of(mppProtocol), SERVICE_NAME);
      var authManager = new ProviderManager(provider);
      var filter =
          new PaygateAuthenticationFilter(authManager, List.of(mppProtocol), endpointRegistry);

      request.setMethod("GET");
      request.setRequestURI("/api/data");
      request.setRemoteAddr("192.168.1.100");
      request.addHeader("Authorization", authHeader);

      filter.doFilterInternal(request, response, filterChain);

      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
      verify(mppProtocol).validate(eq(credential), metadataCaptor.capture());

      assertThat(metadataCaptor.getValue())
          .containsEntry(VerificationContextKeys.REQUEST_CLIENT_IP, "192.168.1.100");
    }
  }

  // ========== Helpers ==========

  private L402Credential createTestL402Credential(List<Caveat> caveats) {
    byte[] paymentHash = new byte[32];
    byte[] tokenIdBytes = new byte[32];
    RNG.nextBytes(paymentHash);
    RNG.nextBytes(tokenIdBytes);

    var identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
    byte[] idBytes = MacaroonIdentifier.encode(identifier);
    byte[] sig = new byte[32];
    RNG.nextBytes(sig);

    var macaroon = new Macaroon(idBytes, null, caveats, sig);
    var preimage = new PaymentPreimage(paymentHash);

    String tokenId = HexFormat.of().formatHex(tokenIdBytes);
    return new L402Credential(macaroon, preimage, tokenId);
  }

  private PaymentCredential createTestPaymentCredential(String scheme, String source) {
    byte[] paymentHash = new byte[32];
    byte[] preimage = new byte[32];
    byte[] tokenIdBytes = new byte[32];
    RNG.nextBytes(paymentHash);
    RNG.nextBytes(preimage);
    RNG.nextBytes(tokenIdBytes);

    String tokenId = HexFormat.of().formatHex(tokenIdBytes);
    return new PaymentCredential(
        paymentHash, preimage, tokenId, scheme, source, new ProtocolMetadata() {});
  }
}
