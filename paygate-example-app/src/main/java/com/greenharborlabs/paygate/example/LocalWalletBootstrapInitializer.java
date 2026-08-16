package com.greenharborlabs.paygate.example;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicitly provisions a disposable LNbits wallet for the example application only.
 *
 * <p>The initializer deliberately runs before backend binding. It accepts only an opted-in local
 * development target and publishes a complete key only after the direct, bounded health and wallet
 * requests both succeed. Configuration strings cannot be reliably erased by the JVM; this bounded
 * residual risk is limited to the opted-in example process.
 */
final class LocalWalletBootstrapInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final String BACKEND = "paygate.backend";
  private static final String API_KEY = "paygate.lnbits.api-key";
  private static final String URL = "paygate.lnbits.url";
  private static final String ALLOW_PLAINTEXT = "paygate.lnbits.allow-plaintext-http";
  private static final String AUTO_PROVISION = "paygate.example.lnbits.auto-provision";
  private static final String CONNECT_TIMEOUT_SECONDS =
      "paygate.example.lnbits.connect-timeout-seconds";
  private static final String REQUEST_TIMEOUT_SECONDS =
      "paygate.example.lnbits.request-timeout-seconds";
  private static final String MAX_RESPONSE_BYTES = "paygate.example.lnbits.max-response-bytes";
  private static final String PROPERTY_SOURCE_NAME = "paygate-example-lnbits-bootstrap";
  private static final Set<String> LOCAL_PROFILES = Set.of("dev", "local", "development", "test");
  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 2;
  private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;
  private static final int DEFAULT_MAX_RESPONSE_BYTES = 8192;
  private static final int MAX_API_KEY_LENGTH = 4096;
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final HttpClient client;
  private final AddressResolver resolver;

  LocalWalletBootstrapInitializer() {
    this(null, InetAddress::getAllByName);
  }

  LocalWalletBootstrapInitializer(HttpClient client, AddressResolver resolver) {
    this.client = client;
    this.resolver = resolver;
  }

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    ConfigurableEnvironment environment = applicationContext.getEnvironment();
    if (!"lnbits".equalsIgnoreCase(environment.getProperty(BACKEND, ""))) {
      return;
    }
    if (hasText(environment.getProperty(API_KEY))) {
      return;
    }
    if (!environment.getProperty(AUTO_PROVISION, Boolean.class, false)) {
      throw rejected("explicit-consent");
    }

    validateProfiles(environment.getActiveProfiles());
    URI baseUri = validateBaseUri(environment.getProperty(URL));
    if ("http".equalsIgnoreCase(baseUri.getScheme())
        && !environment.getProperty(ALLOW_PLAINTEXT, Boolean.class, false)) {
      throw rejected("plaintext-permission");
    }

    int connectTimeout =
        positive(environment, CONNECT_TIMEOUT_SECONDS, DEFAULT_CONNECT_TIMEOUT_SECONDS);
    int requestTimeout =
        positive(environment, REQUEST_TIMEOUT_SECONDS, DEFAULT_REQUEST_TIMEOUT_SECONDS);
    int maxResponseBytes = positive(environment, MAX_RESPONSE_BYTES, DEFAULT_MAX_RESPONSE_BYTES);
    HttpClient httpClient =
        client != null
            ? client
            : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

    verifyHealthy(httpClient, baseUri, requestTimeout, maxResponseBytes);
    String apiKey = createWallet(httpClient, baseUri, requestTimeout, maxResponseBytes);
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(API_KEY, apiKey)));
  }

  private void validateProfiles(String[] profiles) {
    if (profiles.length == 0) {
      throw rejected("active-profiles");
    }
    for (String profile : profiles) {
      if (profile == null || !LOCAL_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
        throw rejected("active-profiles");
      }
    }
  }

  private URI validateBaseUri(String configuredUrl) {
    if (!hasText(configuredUrl)) {
      throw rejected("base-uri");
    }
    try {
      URI uri = URI.create(configuredUrl);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !hasText(uri.getHost())
          || uri.getPort() > 65535) {
        throw rejected("base-uri");
      }
      validateLocalHost(uri.getHost());
      return uri;
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("Unsafe LNbits bootstrap")) {
        throw e;
      }
      throw rejected("base-uri");
    }
  }

  private void validateLocalHost(String host) {
    try {
      if ("localhost".equalsIgnoreCase(host)) {
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0
            || Arrays.stream(addresses).anyMatch(address -> !address.isLoopbackAddress())) {
          throw rejected("local-target");
        }
        return;
      }
      if (!isIpLiteral(host) || !InetAddress.getByName(host).isLoopbackAddress()) {
        throw rejected("local-target");
      }
    } catch (IOException e) {
      throw rejected("local-target");
    }
  }

  private static boolean isIpLiteral(String host) {
    return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
  }

  private static int positive(
      ConfigurableEnvironment environment, String property, int defaultValue) {
    int value = environment.getProperty(property, Integer.class, defaultValue);
    if (value <= 0) {
      throw rejected("finite-limits");
    }
    return value;
  }

  private static void verifyHealthy(
      HttpClient client, URI baseUri, int requestTimeout, int maxResponseBytes) {
    int status =
        send(
            client,
            baseUri.resolve("/api/v1/health"),
            "GET",
            null,
            requestTimeout,
            maxResponseBytes);
    if (status < 200 || status >= 300) {
      throw rejected("health-response");
    }
  }

  private static String createWallet(
      HttpClient client, URI baseUri, int requestTimeout, int maxResponseBytes) {
    HttpResponse<InputStream> response =
        sendResponse(
            client,
            baseUri.resolve("/api/v1/wallet"),
            "POST",
            "{\"name\":\"paygate-auto\"}",
            requestTimeout);
    if (response.statusCode() != 200 && response.statusCode() != 201) {
      close(response.body());
      throw rejected("wallet-response");
    }
    byte[] body = readBounded(response.body(), maxResponseBytes);
    try {
      Map<?, ?> parsed = STRICT_JSON.readValue(body, Map.class);
      Object apiKey = parsed.size() == 1 ? parsed.get("adminkey") : null;
      if (!(apiKey instanceof String key) || !hasText(key) || key.length() > MAX_API_KEY_LENGTH) {
        throw rejected("wallet-response");
      }
      return key;
    } catch (RuntimeException e) {
      if (e instanceof IllegalStateException state
          && state.getMessage() != null
          && state.getMessage().startsWith("Unsafe LNbits bootstrap")) {
        throw state;
      }
      throw rejected("wallet-response");
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  private static int send(
      HttpClient client,
      URI uri,
      String method,
      String body,
      int requestTimeout,
      int maxResponseBytes) {
    HttpResponse<InputStream> response = sendResponse(client, uri, method, body, requestTimeout);
    try {
      readBounded(response.body(), maxResponseBytes);
      return response.statusCode();
    } finally {
      close(response.body());
    }
  }

  private static HttpResponse<InputStream> sendResponse(
      HttpClient client, URI uri, String method, String body, int requestTimeout) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(requestTimeout));
      if (body == null) {
        builder.GET();
      } else {
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
      }
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw rejected("wallet-transport");
    }
  }

  private static byte[] readBounded(InputStream stream, int maxResponseBytes) {
    try (stream) {
      byte[] result = stream.readNBytes(maxResponseBytes + 1);
      if (result.length > maxResponseBytes) {
        Arrays.fill(result, (byte) 0);
        throw rejected("response-size");
      }
      return result;
    } catch (IOException e) {
      throw rejected("wallet-response");
    }
  }

  private static void close(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The startup path already has a deterministic response status.
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static IllegalStateException rejected(String category) {
    return new IllegalStateException("Unsafe LNbits bootstrap configuration category: " + category);
  }

  @FunctionalInterface
  interface AddressResolver {
    InetAddress[] resolve(String hostname) throws IOException;
  }
}
