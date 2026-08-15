package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.MappingMatch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit tests for path normalization and deployment-relative enforcement in the servlet filter. */
@DisplayName("PaygateSecurityFilter path normalization")
class PaygateSecurityFilterPathNormalizationTest {

  private static final String PROTECTED_PATH = "/api/protected";

  @Nested
  @DisplayName("normalizePath")
  class NormalizePath {

    @Test
    @DisplayName("null returns /")
    void nullReturnsRoot() {
      assertThat(PaygateSecurityFilter.normalizePath(null)).isEqualTo("/");
    }

    @Test
    @DisplayName("empty string returns /")
    void emptyReturnsRoot() {
      assertThat(PaygateSecurityFilter.normalizePath("")).isEqualTo("/");
    }

    @Test
    @DisplayName("root path returns /")
    void rootReturnsRoot() {
      assertThat(PaygateSecurityFilter.normalizePath("/")).isEqualTo("/");
    }

    @Test
    @DisplayName("simple path passes through")
    void simplePathPassesThrough() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/protected")).isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("dot-dot segments are collapsed")
    void dotDotCollapsed() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/public/../protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("single dot segments are removed")
    void singleDotRemoved() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/./protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("percent-encoded dot-dot (%2e%2e) is decoded and collapsed")
    void percentEncodedDotDot() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/public/%2e%2e/protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("uppercase percent-encoded dot-dot (%2E%2E) is decoded and collapsed")
    void uppercasePercentEncodedDotDot() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/public/%2E%2E/protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("double-encoded dot-dot (%252e%252e) is decoded and collapsed")
    void doubleEncodedDotDot() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/public/%252e%252e/protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("percent-encoded single dot (%2e) is decoded and removed")
    void percentEncodedSingleDot() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/%2e/protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("plus sign is treated as literal, not as space")
    void plusIsLiteral() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/c++/resource"))
          .isEqualTo("/api/c++/resource");
    }

    @Test
    @DisplayName("traversal above root is clamped")
    void traversalAboveRootClamped() {
      assertThat(PaygateSecurityFilter.normalizePath("/../../../api/protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("empty segments from multiple slashes are collapsed")
    void emptySegmentsCollapsed() {
      assertThat(PaygateSecurityFilter.normalizePath("/api///protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("multiple dot-dot segments resolve correctly")
    void multipleDotDots() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/foo/bar/../../protected"))
          .isEqualTo("/api/protected");
    }

    @Test
    @DisplayName("all dot-dot resolves to root")
    void allDotDotsToRoot() {
      assertThat(PaygateSecurityFilter.normalizePath("/a/b/../../..")).isEqualTo("/");
    }

    @ParameterizedTest
    @CsvSource({
      "/api/public/%2e%2e/protected, /api/protected",
      "/api/public/%2E%2E/protected, /api/protected",
      "/api/public/%252e%252e/protected, /api/protected",
      "/api/%2e/protected, /api/protected",
      "/api/c++/resource, /api/c++/resource",
      "/../../../api/protected, /api/protected",
      "/api///protected, /api/protected",
    })
    @DisplayName("parameterized normalization cases")
    void parameterizedCases(String input, String expected) {
      assertThat(PaygateSecurityFilter.normalizePath(input)).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("normalizePath — %2F preservation (FR-003b)")
  class NormalizePathEncodedSlash {

    @Test
    @DisplayName("FR-003b: %2F preserved through full normalization")
    void encodedSlashPreservedThroughNormalization() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/v1%2Fbypass"))
          .isEqualTo("/api/v1%2Fbypass");
    }

    @Test
    @DisplayName("FR-003b: %2f (lowercase) uppercased and preserved through full normalization")
    void lowercaseEncodedSlashPreservedThroughNormalization() {
      // PathNormalizer uppercases reserved hex digits per RFC 3986 Section 2.1
      assertThat(PaygateSecurityFilter.normalizePath("/api/v1%2fbypass"))
          .isEqualTo("/api/v1%2Fbypass");
    }

    @Test
    @DisplayName("FR-003b: %2E traversal still decoded and collapsed with %2F preserved")
    void traversalDecodedWhileSlashPreserved() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/%2e%2e/v1%2Fbypass"))
          .isEqualTo("/v1%2Fbypass");
    }
  }

  @Nested
  @DisplayName("normalizePath — reserved delimiter preservation")
  class NormalizePathReservedDelimiters {

    @Test
    @DisplayName("reserved ? (%3F) preserved through normalization")
    void encodedQuestionMarkPreserved() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/a%3Fb")).isEqualTo("/api/a%3Fb");
    }

    @Test
    @DisplayName("reserved ? (%3f lowercase) uppercased and preserved")
    void lowercaseEncodedQuestionMarkPreserved() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/a%3fb")).isEqualTo("/api/a%3Fb");
    }

    @Test
    @DisplayName("reserved # (%23) preserved through normalization")
    void encodedHashPreserved() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/a%23b")).isEqualTo("/api/a%23b");
    }

    @Test
    @DisplayName("reserved : (%3A) preserved through normalization")
    void encodedColonPreserved() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/a%3Ab")).isEqualTo("/api/a%3Ab");
    }

    @Test
    @DisplayName("reserved : (%3a lowercase) uppercased and preserved")
    void lowercaseEncodedColonPreserved() {
      assertThat(PaygateSecurityFilter.normalizePath("/api/a%3ab")).isEqualTo("/api/a%3Ab");
    }
  }

  @Nested
  @DisplayName("deployment prefixes")
  class DeploymentPrefixes {

    @Test
    @DisplayName("challenges a protected route under a context path before invoking the handler")
    void challengesProtectedRouteUnderContextPathBeforeHandler() throws Exception {
      var request = request("/shop/api/protected");
      request.setContextPath("/shop");

      assertProtectedBeforeHandler(request);
    }

    @Test
    @DisplayName(
        "challenges a protected route under a path servlet mapping before invoking the handler")
    void challengesProtectedRouteUnderPathServletMappingBeforeHandler() throws Exception {
      assertProtectedBeforeHandler(pathMappedRequest("/gateway/api/protected", "", "/gateway"));
    }

    @Test
    @DisplayName("preserves enforcement for a root deployment")
    void preservesRootDeploymentEnforcement() throws Exception {
      assertProtectedBeforeHandler(request(PROTECTED_PATH));
    }

    @Test
    @DisplayName("challenges a protected route under combined deployment prefixes")
    void challengesProtectedRouteUnderCombinedPrefixesBeforeHandler() throws Exception {
      assertProtectedBeforeHandler(
          pathMappedRequest("/shop/gateway/api/protected", "/shop", "/gateway"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "context path boundary mismatch, /application/api/orders, /app, ''",
      "servlet mapping boundary mismatch, /gateway-other/api/orders, '', /gateway"
    })
    @DisplayName("rejects ambiguous deployment prefixes before endpoint lookup")
    void rejectsAmbiguousDeploymentPrefixesBeforeEndpointLookup(
        String description, String requestUri, String contextPath, String servletPath)
        throws Exception {
      var request = request(requestUri);
      request.setContextPath(contextPath);
      if (!servletPath.isEmpty()) {
        request.setServletPath(servletPath);
        request.setHttpServletMapping(
            new MockHttpServletMapping("", servletPath + "/*", "dispatcher", MappingMatch.PATH));
      }
      var registry = mock(PaygateEndpointRegistry.class);
      var challengeService = mock(PaygateChallengeService.class);
      var filter =
          new PaygateSecurityFilter(
              registry, List.of(), challengeService, "test-service", null, null, null, null);
      var response = new MockHttpServletResponse();
      var chain = mock(FilterChain.class);

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentAsString())
          .isEqualTo(
              "{\"code\": 400, \"error\": \"MALFORMED_URI\", \"message\": \"Invalid request URI\"}");
      verifyNoInteractions(registry, challengeService, chain);
    }

    private void assertProtectedBeforeHandler(MockHttpServletRequest request) throws Exception {
      var config = new PaygateEndpointConfig("GET", PROTECTED_PATH, 10L, 600L, "test", "", "");
      var registry = new PaygateEndpointRegistry();
      registry.register(config);
      var challengeService = mock(PaygateChallengeService.class);
      when(challengeService.createChallenge(any(), any(ResolvedEndpoint.class), any()))
          .thenReturn(challengeContext());
      var protocol = mock(PaymentProtocol.class);
      when(protocol.formatChallenge(any()))
          .thenReturn(new ChallengeResponse("L402 challenge", "L402", Map.of()));
      var filter =
          new PaygateSecurityFilter(
              registry,
              List.of(protocol),
              challengeService,
              "test-service",
              null,
              null,
              null,
              null);
      var response = new MockHttpServletResponse();
      var chain = mock(FilterChain.class);

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).isEqualTo(402);
      verify(chain, never()).doFilter(any(), any());
      var resolvedEndpointCaptor = ArgumentCaptor.forClass(ResolvedEndpoint.class);
      verify(challengeService).createChallenge(any(), resolvedEndpointCaptor.capture(), any());
      assertThat(resolvedEndpointCaptor.getValue().config()).isEqualTo(config);
      assertThat(resolvedEndpointCaptor.getValue().routePattern()).isEqualTo(PROTECTED_PATH);
      assertThat(resolvedEndpointCaptor.getValue().policyMethod()).isEqualTo("GET");
    }

    private ChallengeContext challengeContext() {
      return new ChallengeContext(
          new byte[32],
          "token-id",
          "lnbc1test",
          10L,
          "test",
          "test-service",
          600L,
          "",
          new byte[32],
          Map.of(),
          null);
    }

    private MockHttpServletRequest request(String requestUri) {
      return new MockHttpServletRequest("GET", requestUri);
    }

    private MockHttpServletRequest pathMappedRequest(
        String requestUri, String contextPath, String servletPath) {
      var request = request(requestUri);
      request.setContextPath(contextPath);
      request.setServletPath(servletPath);
      request.setHttpServletMapping(
          new MockHttpServletMapping("", servletPath + "/*", "dispatcher", MappingMatch.PATH));
      return request;
    }
  }
}
