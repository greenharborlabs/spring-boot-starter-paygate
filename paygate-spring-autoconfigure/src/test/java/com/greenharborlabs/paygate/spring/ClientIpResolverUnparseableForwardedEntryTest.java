package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Regression tests for a trusted proxy appending a non-literal {@code X-Forwarded-For} entry.
 *
 * <p>{@link ClientIpResolver#resolve} feeds holder-added {@code client_ip} delegation caveats when
 * client-address binding is disabled. Skipping an unparseable proxy-appended entry would return the
 * client-chosen entry to its left and satisfy another client's caveat.
 */
@DisplayName("ClientIpResolver unparseable proxy-appended entries")
class ClientIpResolverUnparseableForwardedEntryTest {

  private static final String TRUSTED_PROXY = "10.0.0.1";
  private static final String DELEGATED_IP = "203.0.113.5";

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"198.51.100.7:51234", "unknown", "[2001:db8::7]:443", "_hidden"})
  @DisplayName("resolution stops at the trusted peer and the delegated client_ip caveat fails")
  void unparseableEntryStopsAtTrustedPeer(String proxyAppendedEntry) {
    var resolver = new ClientIpResolver(true, List.of(TRUSTED_PROXY));
    var request = new MockHttpServletRequest("GET", "/api/x");
    request.setRemoteAddr(TRUSTED_PROXY);
    request.addHeader("X-Forwarded-For", DELEGATED_IP + ", " + proxyAppendedEntry);

    String resolved = resolver.resolve(request);

    assertThat(resolved).isEqualTo(TRUSTED_PROXY);
    assertThat(resolver.resolveBindingAddress(request)).isEmpty();
    var context =
        L402VerificationContext.builder()
            .serviceName("svc")
            .currentTime(Instant.now())
            .requestMetadata(Map.of(VerificationContextKeys.REQUEST_CLIENT_IP, resolved))
            .build();
    assertThatThrownBy(
            () ->
                new ClientIpCaveatVerifier(50)
                    .verify(new Caveat("client_ip", DELEGATED_IP), context))
        .isInstanceOf(MacaroonVerificationException.class);
  }
}
