package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ensures complete secret values cannot cross operational or authentication boundaries. */
@Tag("integration")
@SpringBootTest(
    classes = {SecurityExampleApplication.class, SecretExposureIT.TestConfig.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.service-name=secret-exposure-test",
      "paygate.protocols.mpp.challenge-binding-secret=COMPLETE_CONFIG_SECRET_MARKER_6b1f",
      "paygate.actuator.enabled=true"
    })
@DisplayName("Secret exposure boundaries")
class SecretExposureIT {

  private static final String CONFIG_SECRET = "COMPLETE_CONFIG_SECRET_MARKER_6b1f";
  private static final String PRESENTED_SECRET = "COMPLETE_PRESENTED_SECRET_MARKER_1d72";
  private static final String PROTECTED_PATH = "/api/v1/protocol-info";

  @LocalServerPort private int port;

  @org.springframework.beans.factory.annotation.Autowired
  private ApplicationContext applicationContext;

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();

  @Test
  @DisplayName("complete secret markers never appear in logs, errors, metrics, health, or auth")
  void completeSecretMarkersNeverCrossOperationalOrAuthenticationBoundaries() throws Exception {
    attachRootLogCapture();
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(baseUrl() + PROTECTED_PATH))
                  .header("Authorization", "L402 " + PRESENTED_SECRET)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isIn(400, 401, 402);
      assertContainsNoSecret("error response", response.body());
      assertContainsNoSecret("error headers", response.headers().map().toString());
      assertContainsNoSecret(
          "authentication context", response.headers().firstValue("X-Test-Auth").orElse(""));
      assertContainsNoSecret("metrics", meterSnapshots());
      assertContainsNoSecret("health", healthSnapshots(client));
      assertContainsNoSecret(
          "logs",
          logEvents.list.stream()
              .map(event -> event.getFormattedMessage() + " " + event.getThrowableProxy())
              .collect(Collectors.joining("\n")));
    } finally {
      detachRootLogCapture();
    }
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private void assertContainsNoSecret(String boundary, String value) {
    assertThat(value).as(boundary).doesNotContain(CONFIG_SECRET, PRESENTED_SECRET);
  }

  private void attachRootLogCapture() {
    Logger root =
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    logEvents.start();
    root.addAppender(logEvents);
  }

  private void detachRootLogCapture() {
    Logger root =
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    root.detachAppender(logEvents);
    logEvents.stop();
  }

  @AfterEach
  void clearAuthenticationContext() {
    SecurityContextHolder.clearContext();
  }

  private String meterSnapshots() {
    return applicationContext.getBeansOfType(Object.class).values().stream()
        .filter(bean -> bean.getClass().getName().contains("MeterRegistry"))
        .map(Object::toString)
        .collect(Collectors.joining("\n"));
  }

  private String healthSnapshots(HttpClient client) throws Exception {
    var healthResponse =
        client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/api/v1/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(healthResponse.statusCode()).isEqualTo(200);
    return healthResponse.body()
        + applicationContext.getBeansOfType(Object.class).values().stream()
            .filter(bean -> bean.getClass().getName().contains("HealthIndicator"))
            .map(this::invokeHealth)
            .collect(Collectors.joining("\n"));
  }

  private String invokeHealth(Object indicator) {
    try {
      Method health = indicator.getClass().getMethod("health");
      return String.valueOf(health.invoke(indicator));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Could not inspect health indicator " + indicator.getClass(), e);
    }
  }

  @TestConfiguration
  static class TestConfig {

    @Bean
    FilterRegistrationBean<AuthenticationInspectionFilter> authenticationInspectionFilter() {
      var registration = new FilterRegistrationBean<>(new AuthenticationInspectionFilter());
      registration.setOrder(-90);
      registration.addUrlPatterns(PROTECTED_PATH);
      return registration;
    }
  }

  static class AuthenticationInspectionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response,
        jakarta.servlet.FilterChain filterChain)
        throws jakarta.servlet.ServletException, java.io.IOException {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null) {
        response.setHeader(
            "X-Test-Auth",
            Arrays.asList(
                    authentication, authentication.getPrincipal(), authentication.getCredentials())
                .stream()
                .map(String::valueOf)
                .collect(Collectors.joining("|")));
      }
      filterChain.doFilter(request, response);
    }
  }
}
