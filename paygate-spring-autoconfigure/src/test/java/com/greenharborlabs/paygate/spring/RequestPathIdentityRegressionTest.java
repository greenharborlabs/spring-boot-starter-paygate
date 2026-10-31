package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.paygate.core.macaroon.PathCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Regression for the 2026-10-18 security review path-identity findings (SR-01, SR-03, SR-04,
 * SR-17): the path Paygate authorizes must be the path Spring MVC dispatches.
 */
@DisplayName("Request path identity")
class RequestPathIdentityRegressionTest {

  private static final PathCaveatVerifier VERIFIER = new PathCaveatVerifier(50);
  private static final Caveat PUBLIC_ONLY = new Caveat("path", "/files/public/**");

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/files/private/%252e%252e/public/report",
        "/files/private/%25252e%25252e/public/report",
        "/files/private/%2e%2e/public/report",
        "/files/private/../public/report",
        "/files/private/x/..;jsessionid=1/../public/report",
        "/files/public/report;tier=premium"
      })
  @DisplayName("rejects paths whose dispatch differs from an over-decoded authorization path")
  void rejectsPathsThatWouldDivergeFromDispatch(String rawUri) {
    var request = new MockHttpServletRequest("GET", rawUri);

    assertThatThrownBy(() -> ApplicationRelativeRequestResolver.resolve(request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/files/private/report", "/files/a%20b/report", "/files/public/report"})
  @DisplayName("authorizes exactly the once-decoded path that Spring MVC dispatches")
  void authorizesTheDispatchedPath(String rawUri) {
    var request = new MockHttpServletRequest("GET", rawUri);

    String paygatePath = ApplicationRelativeRequestResolver.resolve(request);
    var springPath = ServletRequestPathUtils.parse(request).pathWithinApplication();
    var springDecoded = new StringBuilder();
    for (PathContainer.Element element : springPath.elements()) {
      springDecoded.append(
          element instanceof PathContainer.PathSegment segment
              ? segment.valueToMatch()
              : element.value());
    }

    assertThat(paygatePath).isEqualTo(springDecoded.toString());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/files/private/report", "/files/publicity/report"})
  @DisplayName("a public-only path caveat rejects the private zone it is resolved to")
  void publicOnlyCaveatRejectsPrivateZone(String rawUri) {
    String paygatePath =
        ApplicationRelativeRequestResolver.resolve(new MockHttpServletRequest("GET", rawUri));

    assertThatThrownBy(() -> VERIFIER.verify(PUBLIC_ONLY, context(paygatePath)))
        .isInstanceOf(MacaroonVerificationException.class);
  }

  private static L402VerificationContext context(String path) {
    return L402VerificationContext.builder()
        .serviceName("svc")
        .currentTime(Instant.now())
        .requestMetadata(Map.of(VerificationContextKeys.REQUEST_PATH, path))
        .build();
  }
}
