package com.greenharborlabs.paygate.lightning.lnbits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

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
        .hasMessageContaining("scheme")
        .hasMessageContaining("file");
  }

  @Test
  void shouldRejectFtpSchemeUrl() {
    assertThatThrownBy(() -> new LnbitsConfig("ftp://evil.com", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scheme")
        .hasMessageContaining("ftp");
  }

  @Test
  void shouldRejectGopherSchemeUrl() {
    assertThatThrownBy(() -> new LnbitsConfig("gopher://evil.com", API_KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scheme")
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
  void shouldAcceptHttpScheme() {
    var config = new LnbitsConfig("http://localhost:5000", API_KEY);
    assertThat(config.baseUrl()).isEqualTo("http://localhost:5000");
  }

  @Test
  void shouldAcceptHttpsScheme() {
    var config = new LnbitsConfig("https://lnbits.example.com", API_KEY);
    assertThat(config.baseUrl()).isEqualTo("https://lnbits.example.com");
  }

  @Test
  void shouldAcceptUppercaseHttpScheme() {
    var config = new LnbitsConfig("HTTP://EXAMPLE.COM", API_KEY);
    assertThat(config.baseUrl()).isEqualTo("HTTP://EXAMPLE.COM");
  }
}
