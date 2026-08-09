package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

  @Nested
  @DisplayName("rate-limit identity")
  class RateLimitIdentity {

    @Test
    @DisplayName("IPv6 addresses in the same default /64 share a canonical binary-masked identity")
    void ipv6AddressesInSameDefaultPrefixShareIdentity() {
      var resolver = new ClientIpResolver(false, List.of());
      var first = requestFrom("2001:db8:abcd:1234::1");
      var second = requestFrom("2001:0db8:abcd:1234:ffff:eeee:dddd:cccc");

      assertThat(resolveRateLimitIdentity(resolver, first))
          .isEqualTo(resolveRateLimitIdentity(resolver, second))
          .isEqualTo("2001:db8:abcd:1234:0:0:0:0");
    }

    @Test
    @DisplayName("configured IPv6 prefix is applied as a binary mask")
    void configurableIpv6PrefixIsAppliedAsBinaryMask() {
      var resolver = resolverWithIpv6Prefix(56);
      var first = requestFrom("2001:db8:abcd:1201::1");
      var second = requestFrom("2001:db8:abcd:12ff:ffff::1");

      assertThat(resolveRateLimitIdentity(resolver, first))
          .isEqualTo(resolveRateLimitIdentity(resolver, second))
          .isEqualTo("2001:db8:abcd:1200:0:0:0:0");
    }

    @Test
    @DisplayName("an IPv6 /0 rate identity masks every address to zero")
    void ipv6ZeroPrefixMasksEveryAddress() {
      var resolver = resolverWithIpv6Prefix(0);

      assertThat(resolveRateLimitIdentity(resolver, requestFrom("2001:db8:abcd:1234::1")))
          .isEqualTo("0:0:0:0:0:0:0:0");
    }

    @Test
    @DisplayName("an IPv6 /128 rate identity retains the complete canonical address")
    void ipv6FullPrefixRetainsCompleteAddress() {
      var resolver = resolverWithIpv6Prefix(128);

      assertThat(resolveRateLimitIdentity(resolver, requestFrom("2001:db8:abcd:1234::1")))
          .isEqualTo("2001:db8:abcd:1234:0:0:0:1");
    }

    @Test
    @DisplayName("a non-byte-aligned IPv6 prefix clears only trailing bits in its boundary byte")
    void nonByteAlignedIpv6PrefixMasksBoundaryByte() {
      var resolver = resolverWithIpv6Prefix(61);

      assertThat(resolveRateLimitIdentity(resolver, requestFrom("2001:db8:abcd:1234:9abc::1")))
          .isEqualTo("2001:db8:abcd:1230:0:0:0:0");
    }

    @Test
    @DisplayName("IPv4 identity remains the exact canonical /32 address")
    void ipv4IdentityRemainsExactCanonicalAddress() {
      var resolver = new ClientIpResolver(false, List.of());

      assertThat(resolveRateLimitIdentity(resolver, requestFrom("192.0.2.9")))
          .isEqualTo("192.0.2.9");
      assertThat(resolveRateLimitIdentity(resolver, requestFrom("192.0.2.10")))
          .isEqualTo("192.0.2.10");
    }

    @Test
    @DisplayName("IPv6 identity is masked after resolving the client through a trusted proxy")
    void trustedProxyResolutionPrecedesIpv6Masking() {
      var resolver = resolverWithIpv6Prefix(true, List.of("10.0.0.1"), 64);
      var request = requestFrom("10.0.0.1");
      request.addHeader("X-Forwarded-For", "2001:db8:5:6::abcd");

      assertThat(resolver.resolve(request)).isEqualTo("2001:db8:5:6::abcd");
      assertThat(resolveRateLimitIdentity(resolver, request)).isEqualTo("2001:db8:5:6:0:0:0:0");
    }

    @Test
    @DisplayName("untrusted peers cannot spoof rate-limit identity with forwarded headers")
    void untrustedPeerCannotSpoofRateLimitIdentityWithForwardedHeaders() {
      var resolver = resolverWithIpv6Prefix(true, List.of("10.0.0.1"), 64);
      var request = requestFrom("2001:db8:7:8::1234");
      request.addHeader("X-Forwarded-For", "2001:db8:dead:beef::1");

      assertThat(resolver.resolve(request)).isEqualTo("2001:db8:7:8::1234");
      assertThat(resolveRateLimitIdentity(resolver, request)).isEqualTo("2001:db8:7:8:0:0:0:0");
    }
  }

  @Nested
  @DisplayName("when trustForwardedHeaders=false")
  class UntrustedMode {

    private final ClientIpResolver resolver = new ClientIpResolver(false, List.of());

    @Test
    @DisplayName("returns remoteAddr ignoring X-Forwarded-For header")
    void returnsRemoteAddrIgnoringXff() {
      var request = new MockHttpServletRequest();
      request.setRemoteAddr("192.168.1.100");
      request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");

      String result = resolver.resolve(request);

      assertThat(result).isEqualTo("192.168.1.100");
    }
  }

  @Nested
  @DisplayName("when trustForwardedHeaders=true")
  class TrustedMode {

    @Nested
    @DisplayName("without X-Forwarded-For header")
    class NoXff {

      private final ClientIpResolver resolver = new ClientIpResolver(true, List.of());

      @Test
      @DisplayName("returns remoteAddr when no XFF header present")
      void returnsRemoteAddr() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("172.16.0.50");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("172.16.0.50");
      }
    }

    @Nested
    @DisplayName("with X-Forwarded-For header")
    class WithXff {

      @Test
      @DisplayName(
          "scans right-to-left skipping trusted proxies and returns first non-trusted entry")
      void rightToLeftScanSkipsTrustedProxies() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.2"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.50, 10.0.0.1, 10.0.0.2");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("10.0.0.1");
      }

      @Test
      @DisplayName("returns remoteAddr when all XFF entries are trusted")
      void returnsRemoteAddrWhenAllTrusted() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2, 10.0.0.3");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("10.0.0.1");
      }

      @Test
      @DisplayName("returns single XFF value when present and caller is trusted")
      void returnsSingleXffValue() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.50");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("203.0.113.50");
      }

      @Test
      @DisplayName("trims whitespace from XFF entries")
      void trimsWhitespaceFromXffEntries() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.2"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "  203.0.113.50 ,  10.0.0.1 ,  10.0.0.2 ");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("10.0.0.1");
      }

      @Test
      @DisplayName("skips malformed XFF entries while finding valid untrusted client IP")
      void skipsMalformedXffEntries() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "not-an-ip, 203.0.113.50, 10.0.0.1");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("203.0.113.50");
      }

      @Test
      @DisplayName("falls back to remoteAddr when XFF contains no valid untrusted client IP")
      void fallsBackWhenXffContainsNoValidUntrustedClientIp() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "not-an-ip, also-bad");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("10.0.0.1");
      }

      @Test
      @DisplayName("returns remoteAddr when XFF header is empty")
      void returnsRemoteAddrWhenXffEmpty() {
        var resolver = new ClientIpResolver(true, List.of());
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("10.0.0.1");
      }

      @Test
      @DisplayName("ignores XFF when direct caller is not a trusted proxy")
      void untrustedCallerWithXffIsIgnored() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("1.2.3.4");
        request.addHeader("X-Forwarded-For", "5.6.7.8");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("1.2.3.4");
      }

      @Test
      @DisplayName("honors XFF when direct caller is a trusted proxy")
      void trustedCallerXffIsHonored() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "5.6.7.8");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("5.6.7.8");
      }

      @Test
      @DisplayName("empty trusted proxies never trusts XFF")
      void emptyTrustedProxiesNeverTrustsXff() {
        var resolver = new ClientIpResolver(true, List.of());
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("1.2.3.4");
        request.addHeader("X-Forwarded-For", "5.6.7.8");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("1.2.3.4");
      }

      @Test
      @DisplayName("trusted caller with no XFF returns remoteAddr")
      void trustedCallerNoXffReturnsRemoteAddr() {
        var resolver = new ClientIpResolver(true, List.of("10.0.0.1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("10.0.0.1");
      }

      @Test
      @DisplayName("IPv6 proxy recognized in short form")
      void ipv6ProxyRecognizedShortForm() {
        var resolver = new ClientIpResolver(true, List.of("0:0:0:0:0:0:0:1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        request.addHeader("X-Forwarded-For", "5.6.7.8");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("5.6.7.8");
      }

      @Test
      @DisplayName("IPv6 proxy recognized in long form")
      void ipv6ProxyRecognizedLongForm() {
        var resolver = new ClientIpResolver(true, List.of("::1"));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        request.addHeader("X-Forwarded-For", "5.6.7.8");

        String result = resolver.resolve(request);

        assertThat(result).isEqualTo("5.6.7.8");
      }
    }
  }

  @Nested
  @DisplayName("constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("throws NullPointerException when trustedProxyAddresses is null")
    void throwsOnNullTrustedProxyAddresses() {
      assertThatThrownBy(() -> new ClientIpResolver(true, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  private static MockHttpServletRequest requestFrom(String remoteAddress) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    return request;
  }

  private static ClientIpResolver resolverWithIpv6Prefix(int prefixLength) {
    return resolverWithIpv6Prefix(false, List.of(), prefixLength);
  }

  private static ClientIpResolver resolverWithIpv6Prefix(
      boolean trustForwardedHeaders, List<String> trustedProxyAddresses, int prefixLength) {
    try {
      return ClientIpResolver.class
          .getConstructor(boolean.class, List.class, int.class)
          .newInstance(trustForwardedHeaders, trustedProxyAddresses, prefixLength);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          "ClientIpResolver must accept the configured IPv6 rate-limit prefix length", e);
    }
  }

  private static String resolveRateLimitIdentity(
      ClientIpResolver resolver, HttpServletRequest request) {
    try {
      return (String)
          ClientIpResolver.class
              .getMethod("resolveRateLimitIdentity", HttpServletRequest.class)
              .invoke(resolver, request);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          "ClientIpResolver must expose a rate-limit identity separate from the literal client IP",
          e);
    }
  }
}
