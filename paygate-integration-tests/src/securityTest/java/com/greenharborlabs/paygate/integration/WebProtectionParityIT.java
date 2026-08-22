package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.example.security.SecurityExampleApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Verifies that Spring Security applies the same aggregate invoice ceiling as servlet mode. */
@Tag("integration")
@SpringBootTest(
    classes = SecurityExampleApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=true",
      "paygate.root-key-store=memory",
      "paygate.protocols.mpp.challenge-binding-secret=integration-test-secret-at-least-32-bytes-long",
      "paygate.request-body.max-bytes=16",
      "paygate.rate-limit.burst-size=1000",
      "paygate.rate-limit.aggregate.requests-per-second=0.001",
      "paygate.rate-limit.aggregate.burst-size=1"
    })
@DisplayName("Spring Security web-protection parity")
class WebProtectionParityIT {

  @LocalServerPort private int port;

  @Test
  @DisplayName("the aggregate ceiling rejects a second invoice challenge across fresh requests")
  void aggregateCeilingRejectsSecondChallenge() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/v1/data"))
              .GET()
              .build();

      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(402);
      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(429);
    }
  }

  @Test
  @DisplayName("custom security chain applies the MPP challenge request-body ceiling")
  void oversizedMppChallengeRequestFailsClosed() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/v1/data"))
              .header("Content-Type", "application/json")
              .method(
                  "GET",
                  HttpRequest.BodyPublishers.ofString(
                      "{\"content\":\"this body exceeds sixteen bytes\"}"))
              .build();

      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(400);
    }
  }
}
