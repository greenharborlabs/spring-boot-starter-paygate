package com.greenharborlabs.paygate.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@DisplayName("Local LNbits wallet bootstrap")
class LocalWalletBootstrapInitializerTest {

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("a supplied API key bypasses bootstrap without making a wallet request")
  void suppliedApiKeyBypassesBootstrap() {
    var context = context(Map.of("paygate.lnbits.api-key", "supplied-disposable-key"));

    new LocalWalletBootstrapInitializer().initialize(context);

    assertThat(context.getEnvironment().getProperty("paygate.lnbits.api-key"))
        .isEqualTo("supplied-disposable-key");
  }

  @Test
  @DisplayName("missing consent fails before any network operation")
  void missingConsentFailsClosed() {
    var context = context(Map.of());

    assertThatThrownBy(() -> new LocalWalletBootstrapInitializer().initialize(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit-consent");
  }

  @Test
  @DisplayName("explicit local consent publishes a complete API key only after health and creation")
  void explicitLocalConsentPublishesCompleteKey() throws Exception {
    var calls = new AtomicInteger();
    startServer(
        exchange -> {
          calls.incrementAndGet();
          if ("/api/v1/health".equals(exchange.getRequestURI().getPath())) {
            reply(exchange, 204, "");
          } else {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            reply(exchange, 201, "{\"adminkey\":\"disposable-admin-key\"}");
          }
        });
    var context = context(consentedProperties());

    new LocalWalletBootstrapInitializer().initialize(context);

    assertThat(calls).hasValue(2);
    assertThat(context.getEnvironment().getProperty("paygate.lnbits.api-key"))
        .isEqualTo("disposable-admin-key");
    assertThat(
            context
                .getEnvironment()
                .getPropertySources()
                .contains("paygate-example-lnbits-bootstrap"))
        .isTrue();
  }

  @Test
  @DisplayName("unsafe profiles, nonlocal hosts, and plaintext without permission fail closed")
  void unsafeConfigurationFailsClosed() {
    var production = context(consentedProperties());
    production.getEnvironment().setActiveProfiles("prod");
    assertThatThrownBy(() -> new LocalWalletBootstrapInitializer().initialize(production))
        .hasMessageContaining("active-profiles");

    var remote = context(consentedProperties("paygate.lnbits.url", "https://example.com"));
    assertThatThrownBy(() -> new LocalWalletBootstrapInitializer().initialize(remote))
        .hasMessageContaining("local-target");

    var plaintext = context(consentedProperties("paygate.lnbits.allow-plaintext-http", "false"));
    assertThatThrownBy(() -> new LocalWalletBootstrapInitializer().initialize(plaintext))
        .hasMessageContaining("plaintext-permission");
  }

  @Test
  @DisplayName(
      "redirects, non-success responses, oversized bodies, and malformed JSON publish no key")
  void invalidWalletResponsesFailWithoutPublishingPartialKey() throws Exception {
    startServer(exchange -> reply(exchange, 302, "{\"adminkey\":\"secret-never-published\"}"));
    var context = context(consentedProperties());

    assertThatThrownBy(() -> new LocalWalletBootstrapInitializer().initialize(context))
        .hasMessageContaining("health-response");
    assertThat(context.getEnvironment().getProperty("paygate.lnbits.api-key")).isNull();
  }

  @Test
  @DisplayName("localhost resolution rejects any mixed non-loopback response")
  void mixedLocalhostResolutionFailsClosed() {
    var context = context(consentedProperties("paygate.lnbits.url", "https://localhost:8443"));
    var initializer =
        new LocalWalletBootstrapInitializer(
            HttpClient.newHttpClient(),
            ignored ->
                new InetAddress[] {
                  InetAddress.getLoopbackAddress(), InetAddress.getByName("192.0.2.1")
                });

    assertThatThrownBy(() -> initializer.initialize(context)).hasMessageContaining("local-target");
  }

  private GenericApplicationContext context(Map<String, String> overrides) {
    var environment = new StandardEnvironment();
    environment.setActiveProfiles("dev");
    Map<String, Object> properties =
        new java.util.HashMap<>(
            Map.of(
                "paygate.backend", "lnbits",
                "paygate.lnbits.url", "http://localhost:1",
                "paygate.lnbits.allow-plaintext-http", "true"));
    properties.putAll(overrides);
    environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
    var context = new GenericApplicationContext();
    context.setEnvironment(environment);
    return context;
  }

  private Map<String, String> consentedProperties(String... overrides) {
    var properties = new java.util.HashMap<String, String>();
    properties.put("paygate.example.lnbits.auto-provision", "true");
    int port = server == null ? 1 : server.getAddress().getPort();
    properties.put("paygate.lnbits.url", "http://localhost:" + port);
    for (var index = 0; index < overrides.length; index += 2) {
      properties.put(overrides[index], overrides[index + 1]);
    }
    return properties;
  }

  private void startServer(ExchangeHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/api/v1/health", handler::handle);
    server.createContext("/api/v1/wallet", handler::handle);
    server.start();
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @FunctionalInterface
  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
