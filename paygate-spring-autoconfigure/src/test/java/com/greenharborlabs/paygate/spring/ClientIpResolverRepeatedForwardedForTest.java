package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Regression tests for repeated {@code X-Forwarded-For} field lines behind a trusted proxy.
 *
 * <p>Servlet {@code getHeader} returns only the first field line. A trusted proxy that appends its
 * own line (for example HAProxy {@code option forwardfor}) instead of merging must not leave the
 * client-supplied first line as the only one considered.
 */
@DisplayName("ClientIpResolver repeated X-Forwarded-For lines")
class ClientIpResolverRepeatedForwardedForTest {

  private static final String PROXY = "10.0.0.1";
  private static final String REAL_CLIENT = "203.0.113.50";
  private static final String VICTIM = "198.51.100.7";

  private final ClientIpResolver resolver = new ClientIpResolver(true, List.of(PROXY));

  @Test
  @DisplayName("a client-supplied first line cannot choose the resolved or bound address")
  void clientSuppliedFirstLineCannotChooseAddress() {
    var request = viaProxy();
    request.addHeader("X-Forwarded-For", VICTIM);
    request.addHeader("X-Forwarded-For", REAL_CLIENT);

    assertThat(resolver.resolve(request)).isEqualTo(REAL_CLIENT);
    assertThat(resolver.resolveRateLimitIdentity(request)).isEqualTo(REAL_CLIENT);
    assertThat(resolver.resolveBindingAddress(request)).isEmpty();
  }

  @Test
  @DisplayName("an unparseable proxy-appended entry never falls back to the client-supplied entry")
  void unparseableProxyAppendedEntryReturnsTrustedPeer() {
    var request = viaProxy();
    request.addHeader("X-Forwarded-For", VICTIM + ", " + REAL_CLIENT + ":51234");

    assertThat(resolver.resolve(request)).isEqualTo(PROXY);
    assertThat(resolver.resolveBindingAddress(request)).isEmpty();
  }

  @Test
  @DisplayName("rotating the client-supplied line does not rotate the rate-limit identity")
  void rateLimitIdentityIgnoresClientSuppliedLine() {
    var identities = new HashSet<String>();
    for (int i = 0; i < 50; i++) {
      var request = viaProxy();
      request.addHeader("X-Forwarded-For", "192.0.2." + i);
      request.addHeader("X-Forwarded-For", REAL_CLIENT);
      identities.add(resolver.resolveRateLimitIdentity(request));
    }

    assertThat(identities).containsExactly(REAL_CLIENT);
  }

  @Test
  @DisplayName("a single well-formed line behind the trusted proxy still resolves and binds")
  void singleWellFormedLineStillResolves() {
    var request = viaProxy();
    request.addHeader("X-Forwarded-For", REAL_CLIENT);

    assertThat(resolver.resolve(request)).isEqualTo(REAL_CLIENT);
    assertThat(resolver.resolveBindingAddress(request)).contains(REAL_CLIENT);
  }

  private static MockHttpServletRequest viaProxy() {
    var request = new MockHttpServletRequest("GET", "/api/paid");
    request.setRemoteAddr(PROXY);
    return request;
  }
}
