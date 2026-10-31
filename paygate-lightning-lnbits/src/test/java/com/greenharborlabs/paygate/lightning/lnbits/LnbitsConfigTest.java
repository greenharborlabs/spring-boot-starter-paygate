package com.greenharborlabs.paygate.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LnbitsConfigTest {

  private static final String BASE_URL = "https://lnbits.example.com";
  private static final String API_KEY = "super-secret-api-key-12345";

  @Test
  void toStringShouldRedactApiKey() {
    var config = new LnbitsConfig(BASE_URL, API_KEY);
    String result = config.toString();

    assertThat(result).contains(BASE_URL);
    assertThat(result).contains("***REDACTED***");
    assertThat(result).doesNotContain(API_KEY);
  }

  @Test
  void accessorsShouldReturnOriginalValues() {
    var config = new LnbitsConfig(BASE_URL, API_KEY);

    assertThat(config.baseUrl()).isEqualTo(BASE_URL);
    assertThat(config.apiKey()).isEqualTo(API_KEY);
  }

  @Test
  void equalityShouldUseActualValues() {
    var config1 = new LnbitsConfig(BASE_URL, API_KEY);
    var config2 = new LnbitsConfig(BASE_URL, API_KEY);

    assertThat(config1).isEqualTo(config2);
    assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
  }

  @Test
  void equalityShouldPreserveFormerRecordComponentSemanticsAfterClassConversion() {
    var defaultConfig = new LnbitsConfig(BASE_URL, API_KEY, 5, 10);
    var plaintextOptInConfig = new LnbitsConfig(BASE_URL, API_KEY, 5, 10, true);

    assertThat(defaultConfig).isEqualTo(plaintextOptInConfig);
    assertThat(defaultConfig.hashCode()).isEqualTo(plaintextOptInConfig.hashCode());
  }

  @Test
  void shouldRejectNullBaseUrl() {
    assertThatThrownBy(() -> new LnbitsConfig(null, API_KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectBlankApiKey() {
    assertThatThrownBy(() -> new LnbitsConfig(BASE_URL, "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void twoArgConstructorShouldDefaultTimeoutToFiveSeconds() {
    var config = new LnbitsConfig(BASE_URL, API_KEY);
    assertThat(config.requestTimeoutSeconds()).isEqualTo(5);
  }

  @Test
  void threeArgConstructorShouldAcceptCustomTimeout() {
    var config = new LnbitsConfig(BASE_URL, API_KEY, 30);
    assertThat(config.requestTimeoutSeconds()).isEqualTo(30);
  }

  @Test
  void shouldRejectZeroTimeoutSeconds() {
    assertThatThrownBy(() -> new LnbitsConfig(BASE_URL, API_KEY, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestTimeoutSeconds must be positive");
  }

  @Test
  void shouldRejectNegativeTimeoutSeconds() {
    assertThatThrownBy(() -> new LnbitsConfig(BASE_URL, API_KEY, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestTimeoutSeconds must be positive");
  }

  @Test
  void toStringShouldIncludeTimeoutSeconds() {
    var config = new LnbitsConfig(BASE_URL, API_KEY, 10);
    assertThat(config.toString()).contains("requestTimeoutSeconds=10");
  }

  // --- connectTimeoutSeconds tests ---

  @Test
  void twoArgConstructorShouldDefaultConnectTimeoutToTenSeconds() {
    var config = new LnbitsConfig(BASE_URL, API_KEY);
    assertThat(config.connectTimeoutSeconds()).isEqualTo(10);
  }

  @Test
  void threeArgConstructorShouldDefaultConnectTimeoutToTenSeconds() {
    var config = new LnbitsConfig(BASE_URL, API_KEY, 30);
    assertThat(config.connectTimeoutSeconds()).isEqualTo(10);
  }

  @Test
  void fourArgConstructorShouldAcceptCustomConnectTimeout() {
    var config = new LnbitsConfig(BASE_URL, API_KEY, 5, 20);
    assertThat(config.connectTimeoutSeconds()).isEqualTo(20);
  }

  @Test
  void shouldRejectZeroConnectTimeoutSeconds() {
    assertThatThrownBy(() -> new LnbitsConfig(BASE_URL, API_KEY, 5, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectTimeoutSeconds must be positive");
  }

  @Test
  void shouldRejectNegativeConnectTimeoutSeconds() {
    assertThatThrownBy(() -> new LnbitsConfig(BASE_URL, API_KEY, 5, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("connectTimeoutSeconds must be positive");
  }

  @Test
  void toStringShouldIncludeConnectTimeoutSeconds() {
    var config = new LnbitsConfig(BASE_URL, API_KEY, 5, 15);
    assertThat(config.toString()).contains("connectTimeoutSeconds=15");
  }

  @Test
  void toStringShouldRedactApiKeyWithFourArgConstructor() {
    var config = new LnbitsConfig(BASE_URL, API_KEY, 5, 15);
    assertThat(config.toString()).contains("***REDACTED***");
    assertThat(config.toString()).doesNotContain(API_KEY);
  }

  // --- URL scheme validation tests (SSRF prevention) ---

  @Test
  void shouldRejectFileSchemeUrl() {
    assertThatThrownBy(() -> new LnbitsConfig("file:///etc/passwd", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("scheme")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp")
        .hasMessageContaining("file");
  }

  @Test
  void shouldRejectFtpSchemeUrl() {
    assertThatThrownBy(() -> new LnbitsConfig("ftp://evil.com", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("scheme")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp")
        .hasMessageContaining("ftp");
  }

  @Test
  void shouldRejectGopherSchemeUrl() {
    assertThatThrownBy(() -> new LnbitsConfig("gopher://evil.com", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("scheme")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp")
        .hasMessageContaining("gopher");
  }

  @Test
  void shouldRejectUrlWithNoScheme() {
    assertThatThrownBy(() -> new LnbitsConfig("not-a-url", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scheme");
  }

  @Test
  void shouldRejectMalformedUri() {
    assertThatThrownBy(() -> new LnbitsConfig("://missing-scheme", API_KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectNonLoopbackHttpSchemeByDefault() {
    assertThatThrownBy(() -> new LnbitsConfig("http://lnbits.example.com", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp");
  }

  @Test
  void shouldRejectLocalHttpSchemeWithoutExplicitOptIn() {
    assertThatThrownBy(() -> new LnbitsConfig("http://localhost:5000", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp");
  }

  @Test
  void shouldRejectNonLoopbackHttpSchemeEvenWithExplicitOptIn() {
    assertThatThrownBy(() -> new LnbitsConfig("http://lnbits.example.com", API_KEY, 5, 10, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp");
  }

  @Test
  void shouldAcceptLocalHttpSchemeWithExplicitOptIn() {
    var config = new LnbitsConfig("http://localhost:5000", API_KEY, 5, 10, true);
    assertThat(config.baseUrl()).isEqualTo("http://localhost:5000");
    assertThat(config.allowPlaintextHttp()).isTrue();
  }

  @Test
  void shouldAcceptLoopbackHttpSchemeWithExplicitOptIn() {
    var config = new LnbitsConfig("http://127.0.0.1:5000", API_KEY, 5, 10, true);
    assertThat(config.baseUrl()).isEqualTo("http://127.0.0.1:5000");
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://127.0.0.1:5000", "http://[::1]:5000"})
  void shouldAcceptCanonicalLoopbackAddressesWithExplicitOptIn(String baseUrl) {
    var config = new LnbitsConfig(baseUrl, API_KEY, 5, 10, true);

    assertThat(config.baseUrl()).isEqualTo(baseUrl);
    assertThat(config.allowPlaintextHttp()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.00.0.1:5000",
        "http://127.0.00.1:5000",
        "http://127.0.0.01:5000",
        "http://127.000.000.001:5000",
        "http://0177.0.0.1:5000",
        "http://127.1:5000",
        "http://2130706433:5000",
        "http://0x7f.0.0.1:5000"
      })
  void shouldRejectAmbiguousNumericLoopbackFormsWithExplicitOptIn(String baseUrl) {
    assertThatThrownBy(() -> new LnbitsConfig(baseUrl, API_KEY, 5, 10, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'");
  }

  @Test
  void shouldRejectExpandedIpv6LoopbackFormWithExplicitOptIn() {
    assertThatThrownBy(
            () -> new LnbitsConfig("http://[0:0:0:0:0:0:0:1]:5000", API_KEY, 5, 10, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'");
  }

  @Test
  void shouldAcceptDockerComposeServiceNameHttpSchemeWithExplicitOptIn() {
    var config = new LnbitsConfig("http://lnbits:5000", API_KEY, 5, 10, true);
    assertThat(config.baseUrl()).isEqualTo("http://lnbits:5000");
    assertThat(config.allowPlaintextHttp()).isTrue();
  }

  @Test
  void shouldRejectUnknownSingleLabelHttpHostEvenWithExplicitOptIn() {
    assertThatThrownBy(() -> new LnbitsConfig("http://payments:5000", API_KEY, 5, 10, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp");
  }

  @Test
  void shouldRejectIpv4WildcardHttpHostEvenWithExplicitOptIn() {
    assertThatThrownBy(() -> new LnbitsConfig("http://0.0.0.0:5000", API_KEY, 5, 10, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp");
  }

  @Test
  void shouldRejectIpv6WildcardHttpHostEvenWithExplicitOptIn() {
    assertThatThrownBy(() -> new LnbitsConfig("http://[::]:5000", API_KEY, 5, 10, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'")
        .hasMessageContaining("allow-plaintext-http")
        .hasMessageContaining("allowPlaintextHttp");
  }

  @Test
  void shouldAcceptHttpsScheme() {
    var config = new LnbitsConfig("https://lnbits.example.com", API_KEY);
    assertThat(config.baseUrl()).isEqualTo("https://lnbits.example.com");
  }

  @Test
  void shouldRejectUppercaseHttpSchemeWithoutExplicitOptIn() {
    assertThatThrownBy(() -> new LnbitsConfig("HTTP://EXAMPLE.COM", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS")
        .hasMessageContaining("unsafe URL scheme 'http'");
  }

  @Test
  void existingConstructorsShouldDefaultToNoPlaintextOptIn() {
    assertThatThrownBy(() -> new LnbitsConfig("http://localhost:5000", API_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LnbitsConfig("http://localhost:5000", API_KEY, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LnbitsConfig("http://localhost:5000", API_KEY, 5, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- *.localhost resolution (SR-21) ---

  private static LnbitsConfig.HostResolver resolvingTo(String... literals) {
    return host -> {
      var addresses = new InetAddress[literals.length];
      for (int index = 0; index < literals.length; index++) {
        addresses[index] = InetAddress.ofLiteral(literals[index]);
      }
      return addresses;
    };
  }

  @ParameterizedTest
  @ValueSource(strings = {"203.0.113.7", "10.0.0.1", "0.0.0.0", "::", "2001:db8::1"})
  void shouldRejectLocalhostSubdomainResolvingToNonLoopback(String resolvedAddress) {
    assertThatThrownBy(
            () ->
                new LnbitsConfig(
                    "http://lnbits.localhost:5000",
                    API_KEY,
                    5,
                    10,
                    true,
                    resolvingTo(resolvedAddress)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe URL scheme 'http'");
  }

  @Test
  void shouldRejectLocalhostSubdomainWhenAnyResolvedAddressIsNotLoopback() {
    assertThatThrownBy(
            () ->
                new LnbitsConfig(
                    "http://lnbits.localhost:5000",
                    API_KEY,
                    5,
                    10,
                    true,
                    resolvingTo("127.0.0.1", "198.51.100.4")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe URL scheme 'http'");
  }

  @Test
  void shouldRejectLocalhostSubdomainThatDoesNotResolve() {
    LnbitsConfig.HostResolver unresolvable =
        host -> {
          throw new UnknownHostException(host);
        };
    assertThatThrownBy(
            () ->
                new LnbitsConfig(
                    "http://lnbits.localhost:5000", API_KEY, 5, 10, true, unresolvable))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe URL scheme 'http'");
  }

  @Test
  void shouldRejectLocalhostSubdomainWithEmptyResolution() {
    assertThatThrownBy(
            () ->
                new LnbitsConfig(
                    "http://lnbits.localhost:5000",
                    API_KEY,
                    5,
                    10,
                    true,
                    host -> new InetAddress[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldAcceptLocalhostSubdomainResolvingOnlyToLoopback() {
    var config =
        new LnbitsConfig(
            "http://lnbits.localhost:5000",
            API_KEY,
            5,
            10,
            true,
            resolvingTo("127.0.0.1", "::1", "127.0.0.2"));
    assertThat(config.allowPlaintextHttp()).isTrue();
  }

  @Test
  void localhostSubdomainIsNotResolvedWithoutPlaintextOptIn() {
    var lookups = new AtomicInteger();
    LnbitsConfig.HostResolver counting =
        host -> {
          lookups.incrementAndGet();
          return new InetAddress[] {InetAddress.getLoopbackAddress()};
        };
    assertThatThrownBy(
            () -> new LnbitsConfig("http://lnbits.localhost:5000", API_KEY, 5, 10, false, counting))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(lookups).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:5000",
        "http://127.0.0.1:5000",
        "http://[::1]:5000",
        "http://lnbits:5000"
      })
  void literalLocalTargetsAreAcceptedWithoutResolution(String baseUrl) {
    LnbitsConfig.HostResolver failing =
        host -> {
          throw new AssertionError("unexpected resolution of " + host);
        };
    assertThat(new LnbitsConfig(baseUrl, API_KEY, 5, 10, true, failing).allowPlaintextHttp())
        .isTrue();
  }

  // --- Equality and string hygiene (I-15) ---

  @Test
  void equalsAndHashCodeShouldIgnoreApiKey() {
    var first = new LnbitsConfig(BASE_URL, API_KEY, 5, 10);
    var second = new LnbitsConfig(BASE_URL, "a-completely-different-key", 5, 10);

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void equalsShouldStillDistinguishBaseUrlAndTimeouts() {
    var config = new LnbitsConfig(BASE_URL, API_KEY, 5, 10);

    assertThat(config).isNotEqualTo(new LnbitsConfig("https://other.example.com", API_KEY, 5, 10));
    assertThat(config).isNotEqualTo(new LnbitsConfig(BASE_URL, API_KEY, 6, 10));
    assertThat(config).isNotEqualTo(new LnbitsConfig(BASE_URL, API_KEY, 5, 11));
  }

  @Test
  void toStringShouldRedactUserInfoInBaseUrl() {
    var config = new LnbitsConfig("https://alice:hunter2@lnbits.example.com:8443/base", API_KEY);

    assertThat(config.toString())
        .contains("baseUrl=https://***@lnbits.example.com:8443/base")
        .doesNotContain("alice")
        .doesNotContain("hunter2")
        .doesNotContain(API_KEY);
    assertThat(config.baseUrl()).isEqualTo("https://alice:hunter2@lnbits.example.com:8443/base");
  }

  @Test
  void plaintextRejectionMessageShouldRedactUserInfo() {
    assertThatThrownBy(
            () -> new LnbitsConfig("http://alice:hunter2@lnbits.example.com", API_KEY, 5, 10, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http://***@lnbits.example.com")
        .hasMessageNotContaining("hunter2")
        .hasMessageNotContaining("alice");
  }

  @Test
  void malformedUriMessageAndCauseChainShouldNotExposeUserInfo() {
    assertThatThrownBy(() -> new LnbitsConfig("http://alice:hun ter2@lnbits.example.com", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a valid URI")
        .hasMessageNotContaining("hun ter2")
        .hasNoCause();
  }

  @Test
  void missingSchemeMessageShouldNotExposeUserInfo() {
    assertThatThrownBy(() -> new LnbitsConfig("//alice:hunter2@lnbits.example.com", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no scheme")
        .hasMessageNotContaining("hunter2");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "https://lnbits.example.com|https://lnbits.example.com",
        "https://u:p@h.example/p?q=a@b#f@g|https://***@h.example/p?q=a@b#f@g",
        "https://u@p@h.example:1|https://***@h.example:1",
        "http://[::1]:5000/x|http://[::1]:5000/x",
        "not-a-url|not-a-url",
        "mailto:u:p@h|[redacted-url]"
      })
  void redactUserInfoShouldStripOnlyAuthorityUserInfo(String input, String expected) {
    assertThat(LnbitsUrlRedaction.redactUserInfo(input)).isEqualTo(expected);
  }
}
