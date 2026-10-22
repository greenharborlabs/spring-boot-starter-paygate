package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.MappingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.WebUtils;

@DisplayName("ApplicationRelativeRequestResolver")
class ApplicationRelativeRequestResolverTest {

  @Test
  @DisplayName("removes a context path and a single trailing slash")
  void resolvesContextRelativePath() {
    var request = new MockHttpServletRequest("GET", "/shop/api/orders/");
    request.setContextPath("/shop");

    assertThat(ApplicationRelativeRequestResolver.resolve(request)).isEqualTo("/api/orders");
  }

  @Test
  @DisplayName("handles empty, root, combined, similar, and repeated prefix forms")
  void handlesEmptyRootCombinedSimilarAndRepeatedPrefixForms() {
    assertThat(ApplicationRelativeRequestResolver.resolve(request(""))).isEqualTo("/");
    assertThat(ApplicationRelativeRequestResolver.resolve(request("/"))).isEqualTo("/");

    var combined = pathMappedRequest("/shop/gateway/api/orders", "/shop", "/gateway");
    assertThat(ApplicationRelativeRequestResolver.resolve(combined)).isEqualTo("/api/orders");

    var similarAndRepeated = request("/app/app/application/orders/");
    similarAndRepeated.setContextPath("/app");
    assertThat(ApplicationRelativeRequestResolver.resolve(similarAndRepeated))
        .isEqualTo("/app/application/orders");
  }

  @Test
  @DisplayName("decodes each segment exactly once")
  void decodesSegmentsExactlyOnce() {
    assertThat(ApplicationRelativeRequestResolver.resolve(request("/api/a%20b/%C3%A9")))
        .isEqualTo("/api/a b/é");
    assertThat(ApplicationRelativeRequestResolver.resolve(request("/api/c++/x.y/..z")))
        .isEqualTo("/api/c++/x.y/..z");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/files/private/../public/report",
        "/files/private/%2e%2e/public/report",
        "/files/private/%2E%2E/public/report",
        "/files/private/%252e%252e/public/report",
        "/files/private/%25252e%25252e/public/report",
        "/files/private/..;x=1/public/report",
        "/files/private/x/..;jsessionid=1/../public/report",
        "/files/./public/report",
        "/files/%2e/public/report",
        "/files/%252e/public/report",
        "/files/..",
        "/files//public/report",
        "/files/public//",
        "//files/public/report",
        "/files/public/report;tier=premium",
        "/files/public;x=1/report",
        "/files/public/a%3Bb",
        "/files/public/a%5Cb",
        "/files/public/a%255Cb",
        "/files/public/a%2578"
      })
  @DisplayName("rejects ambiguous once-decoded segments")
  void rejectsAmbiguousSegments(String requestUri) {
    assertThatThrownBy(() -> ApplicationRelativeRequestResolver.resolve(request(requestUri)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects ambiguous segments after context path removal")
  void rejectsAmbiguousSegmentsAfterContextPathRemoval() {
    var request = request("/shop/api/%252e%252e/orders");
    request.setContextPath("/shop");

    assertThatThrownBy(() -> ApplicationRelativeRequestResolver.resolve(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nested percent-encoding");
  }

  @Test
  @DisplayName("removes a path servlet mapping prefix")
  void resolvesPathServletMapping() {
    var request = pathMappedRequest("/gateway/api/orders", "", "/gateway");

    assertThat(ApplicationRelativeRequestResolver.resolve(request)).isEqualTo("/api/orders");
  }

  @Test
  @DisplayName("rejects encoded path separators after prefix removal")
  void rejectsEncodedPathSeparators() {
    var request = request("/shop/api/x/%2f/orders/");
    request.setContextPath("/shop");

    assertThatThrownBy(() -> ApplicationRelativeRequestResolver.resolve(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Encoded path separator");
  }

  @Test
  @DisplayName("uses include dispatch path and mapping without persisting parsed state")
  void resolvesCurrentIncludeDispatchWithoutCaching() {
    var request = request("/shop/original");
    request.setContextPath("/shop");
    var previouslyCachedPath = ServletRequestPathUtils.parseAndCache(request);
    request.setAttribute(WebUtils.INCLUDE_REQUEST_URI_ATTRIBUTE, "/shop/gateway/api/included/");
    request.setAttribute(WebUtils.INCLUDE_SERVLET_PATH_ATTRIBUTE, "/gateway");
    request.setAttribute(
        RequestDispatcher.INCLUDE_MAPPING,
        new MockHttpServletMapping("api/included", "/gateway/*", "dispatcher", MappingMatch.PATH));

    assertThat(ApplicationRelativeRequestResolver.resolve(request)).isEqualTo("/api/included");
    assertThat(request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE))
        .isSameAs(previouslyCachedPath);
  }

  @Test
  @DisplayName("uses current getters rather than the original forward attributes")
  void resolvesCurrentForwardRedispatch() {
    var request = request("/shop/api/forwarded/");
    request.setContextPath("/shop");
    request.setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, "/shop/original");
    request.setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, "/shop");
    request.setAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, "/original");

    assertThat(ApplicationRelativeRequestResolver.resolve(request)).isEqualTo("/api/forwarded");
  }

  @Test
  @DisplayName("rejects malformed or ambiguous prefix state")
  void rejectsMalformedOrAmbiguousPrefixState() {
    var contextBoundaryMismatch = request("/application/api/orders");
    contextBoundaryMismatch.setContextPath("/app");

    var servletBoundaryMismatch =
        pathMappedRequest("/shop/gateway-other/api/orders", "/shop", "/gateway");

    var malformedEncoding = request("/api/%2");

    assertThatThrownBy(() -> ApplicationRelativeRequestResolver.resolve(contextBoundaryMismatch))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApplicationRelativeRequestResolver.resolve(servletBoundaryMismatch))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApplicationRelativeRequestResolver.resolve(malformedEncoding))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MockHttpServletRequest request(String requestUri) {
    return new MockHttpServletRequest("GET", requestUri);
  }

  private static MockHttpServletRequest pathMappedRequest(
      String requestUri, String contextPath, String servletPath) {
    var request = request(requestUri);
    request.setContextPath(contextPath);
    request.setServletPath(servletPath);
    request.setHttpServletMapping(
        new MockHttpServletMapping("", servletPath + "/*", "dispatcher", MappingMatch.PATH));
    return request;
  }
}
