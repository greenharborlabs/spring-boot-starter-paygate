package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration coverage for the trust boundary between holder-controlled macaroon caveats and the
 * authenticated Spring Security context.
 */
@Tag("integration")
@SpringBootTest(
    classes = {SecurityExampleApplication.class, TrustedAttributesIT.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=example-api",
      "paygate.protocols.mpp.challenge-binding-secret="
    })
@DisplayName("Trusted Spring Security authentication state")
class TrustedAttributesIT {

  private static final Pattern MACAROON_PATTERN = Pattern.compile("macaroon=\"([^\"]+)\"");
  private static final String INSPECTION_PATH = "/api/v1/protocol-info";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @LocalServerPort private int port;

  @Test
  @DisplayName("holder-added role caveat cannot become a trusted grant or retained credential")
  @SuppressWarnings("unchecked")
  void holderAddedRoleCannotBecomeTrustedGrantOrRemainInSecurityContext() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var credential = obtainL402Credential(client);
      String holderAttenuatedMacaroon =
          appendHolderCaveat(credential.macaroonBase64(), "role", "admin");
      String authorization = "L402 " + holderAttenuatedMacaroon + ":" + credential.preimageHex();

      var response =
          client.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl() + INSPECTION_PATH))
                  .header("Authorization", authorization)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      Map<String, Object> state = MAPPER.readValue(response.body(), Map.class);

      assertThat((Map<String, String>) state.get("attributes"))
          .as("protocol-info must expose only verifier-approved attributes")
          .doesNotContainKey("role");
      assertThat(response.headers().firstValue("X-Test-Authorities").orElseThrow().split(","))
          .contains("ROLE_PAYMENT", "ROLE_L402")
          .doesNotContain("ROLE_ADMIN", "L402_CAPABILITY_admin", "PAYGATE_CAPABILITY_admin");
      assertThat(response.headers().firstValue("X-Test-Raw-Authorization-Accessible"))
          .contains("false");
      assertThat(response.headers().firstValue("X-Test-L402-Credential-Accessible"))
          .contains("false");
      assertThat(response.headers().firstValue("X-Test-Payment-Credential-Accessible"))
          .contains("false");
      assertThat(response.headers().firstValue("X-Test-Credentials-Redacted")).contains("true");
    }
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @SuppressWarnings("unchecked")
  private L402Credential obtainL402Credential(HttpClient client) throws Exception {
    var challenge =
        client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + INSPECTION_PATH)).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(challenge.statusCode()).isEqualTo(402);
    String l402Header =
        challenge.headers().allValues("WWW-Authenticate").stream()
            .filter(header -> header.startsWith("L402"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No L402 WWW-Authenticate header in challenge"));
    Matcher matcher = MACAROON_PATTERN.matcher(l402Header);
    assertThat(matcher.find()).as("L402 challenge must include a macaroon").isTrue();

    Map<String, Object> body = MAPPER.readValue(challenge.body(), Map.class);
    String preimage = (String) body.get("test_preimage");
    assertThat(preimage).hasSize(64);
    return new L402Credential(matcher.group(1), preimage);
  }

  private String appendHolderCaveat(String macaroonBase64, String key, String value) {
    Macaroon macaroon =
        MacaroonSerializer.deserializeV2(Base64.getDecoder().decode(macaroonBase64));
    Caveat holderCaveat = new Caveat(key, value);
    var caveats = new ArrayList<>(macaroon.caveats());
    caveats.add(holderCaveat);
    byte[] signature =
        MacaroonCrypto.hmac(
            macaroon.signature(), holderCaveat.toString().getBytes(StandardCharsets.UTF_8));
    var attenuated = new Macaroon(macaroon.identifier(), macaroon.location(), caveats, signature);
    return Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(attenuated));
  }

  private record L402Credential(String macaroonBase64, String preimageHex) {}

  @TestConfiguration
  static class TestConfig {

    @Bean
    FilterRegistrationBean<SecurityContextInspectionFilter> securityContextInspectionFilter() {
      var registration = new FilterRegistrationBean<>(new SecurityContextInspectionFilter());
      registration.setOrder(-90);
      registration.addUrlPatterns(INSPECTION_PATH);
      return registration;
    }

    static class SecurityContextInspectionFilter extends OncePerRequestFilter {

      @Override
      protected void doFilterInternal(
          jakarta.servlet.http.HttpServletRequest request,
          jakarta.servlet.http.HttpServletResponse response,
          jakarta.servlet.FilterChain filterChain)
          throws jakarta.servlet.ServletException, java.io.IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
          try {
            response.setHeader(
                "X-Test-Authorities",
                String.join(
                    ",",
                    authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList()));
            response.setHeader(
                "X-Test-Raw-Authorization-Accessible",
                Boolean.toString(invoke(authentication, "getAuthorizationHeader") != null));
            response.setHeader(
                "X-Test-L402-Credential-Accessible",
                Boolean.toString(invoke(authentication, "getL402Credential") != null));
            response.setHeader(
                "X-Test-Payment-Credential-Accessible",
                Boolean.toString(invoke(authentication, "getPaymentCredential") != null));
            response.setHeader(
                "X-Test-Credentials-Redacted",
                Boolean.toString("[REDACTED]".equals(authentication.getCredentials())));
          } catch (ReflectiveOperationException e) {
            throw new jakarta.servlet.ServletException(e);
          }
        }
        filterChain.doFilter(request, response);
      }

      private static Object invoke(Object target, String accessor)
          throws ReflectiveOperationException {
        var method = target.getClass().getDeclaredMethod(accessor);
        if (!method.trySetAccessible()) {
          throw new IllegalAccessException("Cannot inspect " + accessor);
        }
        return method.invoke(target);
      }
    }
  }
}
