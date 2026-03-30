package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.spring.PaygateTestSupport.InMemoryTestCredentialStore;
import com.greenharborlabs.paygate.spring.PaygateTestSupport.StubLightningBackend;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("L402 Micrometer Metrics (Unit)")
class PaygateMetricsUnitTest {

  private SimpleMeterRegistry meterRegistry;
  private PaygateMetrics paygateMetrics;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    var lightningBackend = new StubLightningBackend();
    lightningBackend.setHealthy(true);
    paygateMetrics =
        new PaygateMetrics(meterRegistry, new InMemoryTestCredentialStore(), lightningBackend);
  }

  @Nested
  @DisplayName("caveat verification metrics")
  class CaveatVerificationMetrics {

    @Test
    @DisplayName("recordCaveatRejected increments counter with caveat_type=path and protocol=l402")
    void recordCaveatRejectedPathType() {
      double before =
          counterValue("paygate.caveats.rejected", "caveat_type", "path", "protocol", "l402");

      paygateMetrics.recordCaveatRejected("path");

      double after =
          counterValue("paygate.caveats.rejected", "caveat_type", "path", "protocol", "l402");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName(
        "recordCaveatRejected increments counter with caveat_type=method and protocol=l402")
    void recordCaveatRejectedMethodType() {
      double before =
          counterValue("paygate.caveats.rejected", "caveat_type", "method", "protocol", "l402");

      paygateMetrics.recordCaveatRejected("method");

      double after =
          counterValue("paygate.caveats.rejected", "caveat_type", "method", "protocol", "l402");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName(
        "recordCaveatRejected increments counter with caveat_type=client_ip and protocol=l402")
    void recordCaveatRejectedClientIpType() {
      double before =
          counterValue("paygate.caveats.rejected", "caveat_type", "client_ip", "protocol", "l402");

      paygateMetrics.recordCaveatRejected("client_ip");

      double after =
          counterValue("paygate.caveats.rejected", "caveat_type", "client_ip", "protocol", "l402");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName(
        "recordCaveatRejected increments counter with caveat_type=escalation and protocol=l402")
    void recordCaveatRejectedEscalationType() {
      double before =
          counterValue("paygate.caveats.rejected", "caveat_type", "escalation", "protocol", "l402");

      paygateMetrics.recordCaveatRejected("escalation");

      double after =
          counterValue("paygate.caveats.rejected", "caveat_type", "escalation", "protocol", "l402");
      assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    @DisplayName("each caveat_type tag is independent — incrementing one does not affect others")
    void caveatTypeTagsAreIndependent() {
      double pathBefore =
          counterValue("paygate.caveats.rejected", "caveat_type", "path", "protocol", "l402");
      double methodBefore =
          counterValue("paygate.caveats.rejected", "caveat_type", "method", "protocol", "l402");
      double clientIpBefore =
          counterValue("paygate.caveats.rejected", "caveat_type", "client_ip", "protocol", "l402");

      paygateMetrics.recordCaveatRejected("path");
      paygateMetrics.recordCaveatRejected("path");
      paygateMetrics.recordCaveatRejected("method");

      assertThat(
              counterValue("paygate.caveats.rejected", "caveat_type", "path", "protocol", "l402"))
          .isEqualTo(pathBefore + 2.0);
      assertThat(
              counterValue("paygate.caveats.rejected", "caveat_type", "method", "protocol", "l402"))
          .isEqualTo(methodBefore + 1.0);
      assertThat(
              counterValue(
                  "paygate.caveats.rejected", "caveat_type", "client_ip", "protocol", "l402"))
          .isEqualTo(clientIpBefore);
    }

    @Test
    @DisplayName("recordCaveatVerifyDuration records timer with protocol=l402")
    void recordCaveatVerifyDuration() {
      long before = timerCount("paygate.caveats.verify.duration", "protocol", "l402");

      paygateMetrics.recordCaveatVerifyDuration(1_000_000L);

      long after = timerCount("paygate.caveats.verify.duration", "protocol", "l402");
      assertThat(after).isEqualTo(before + 1);
    }
  }

  @Nested
  @DisplayName("classifyCaveatType")
  class ClassifyCaveatTypeTests {

    @Test
    @DisplayName("classifies path rejection messages correctly")
    void classifiesPathRejection() {
      assertThat(
              PaygateSecurityFilter.classifyCaveatType(
                  "Request path does not match any allowed path pattern"))
          .isEqualTo("path");
      assertThat(PaygateSecurityFilter.classifyCaveatType("Invalid path pattern: bad glob"))
          .isEqualTo("path");
      assertThat(PaygateSecurityFilter.classifyCaveatType("Request path contains encoded slash"))
          .isEqualTo("path");
    }

    @Test
    @DisplayName("classifies method rejection messages correctly")
    void classifiesMethodRejection() {
      assertThat(
              PaygateSecurityFilter.classifyCaveatType(
                  "Request method does not match any allowed method"))
          .isEqualTo("method");
      assertThat(PaygateSecurityFilter.classifyCaveatType("Empty method in caveat value"))
          .isEqualTo("method");
    }

    @Test
    @DisplayName("classifies client_ip rejection messages correctly")
    void classifiesClientIpRejection() {
      assertThat(
              PaygateSecurityFilter.classifyCaveatType(
                  "Request client IP does not match any allowed IP"))
          .isEqualTo("client_ip");
      assertThat(
              PaygateSecurityFilter.classifyCaveatType(
                  "Client IP missing from verification context"))
          .isEqualTo("client_ip");
    }

    @Test
    @DisplayName("classifies escalation messages correctly")
    void classifiesEscalation() {
      assertThat(
              PaygateSecurityFilter.classifyCaveatType("caveat escalation detected for key: path"))
          .isEqualTo("escalation");
    }

    @Test
    @DisplayName("escalation takes priority over other keywords")
    void escalationTakesPriority() {
      assertThat(
              PaygateSecurityFilter.classifyCaveatType("caveat escalation detected for key: path"))
          .isEqualTo("escalation");
    }

    @Test
    @DisplayName("returns unknown for null message")
    void returnsUnknownForNull() {
      assertThat(PaygateSecurityFilter.classifyCaveatType(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("returns unknown for unrecognized message")
    void returnsUnknownForUnrecognized() {
      assertThat(PaygateSecurityFilter.classifyCaveatType("some other error")).isEqualTo("unknown");
    }
  }

  @Nested
  @DisplayName("rate limiter metrics")
  class RateLimiterMetrics {

    @Test
    @DisplayName("registerRateLimiterMetrics registers gauge with supplier value")
    void gaugeRegistered() {
      paygateMetrics.registerRateLimiterMetrics(() -> 42L);

      double value = meterRegistry.get("paygate.ratelimiter.buckets.active").gauge().value();
      assertThat(value).isEqualTo(42.0);
    }

    @Test
    @DisplayName("recordRateLimiterEviction increments counter tagged with reason")
    void evictionCounter() {
      paygateMetrics.recordRateLimiterEviction("size");
      paygateMetrics.recordRateLimiterEviction("size");

      double count =
          meterRegistry
              .get("paygate.ratelimiter.evictions")
              .tag("reason", "size")
              .counter()
              .count();
      assertThat(count).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordRateLimiterEviction tracks different reasons independently")
    void evictionReasonsIndependent() {
      paygateMetrics.recordRateLimiterEviction("size");
      paygateMetrics.recordRateLimiterEviction("expired");
      paygateMetrics.recordRateLimiterEviction("expired");

      assertThat(
              meterRegistry
                  .get("paygate.ratelimiter.evictions")
                  .tag("reason", "size")
                  .counter()
                  .count())
          .isEqualTo(1.0);
      assertThat(
              meterRegistry
                  .get("paygate.ratelimiter.evictions")
                  .tag("reason", "expired")
                  .counter()
                  .count())
          .isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordRateLimitRejection increments counter tagged with endpoint")
    void rejectionCounter() {
      paygateMetrics.recordRateLimitRejection("/api/data");
      paygateMetrics.recordRateLimitRejection("/api/data");
      paygateMetrics.recordRateLimitRejection("/api/data");

      double count =
          meterRegistry
              .get("paygate.ratelimiter.rejections")
              .tag("endpoint", "/api/data")
              .counter()
              .count();
      assertThat(count).isEqualTo(3.0);
    }

    @Test
    @DisplayName("no rate limiter meters registered when registerRateLimiterMetrics not called")
    void notRegistered() {
      assertThat(meterRegistry.find("paygate.ratelimiter.buckets.active").gauge()).isNull();
      assertThat(meterRegistry.find("paygate.ratelimiter.evictions").counter()).isNull();
      assertThat(meterRegistry.find("paygate.ratelimiter.rejections").counter()).isNull();
    }
  }

  private double counterValue(String name, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    return counter != null ? counter.count() : 0.0;
  }

  private long timerCount(String name, String... tags) {
    var timer = meterRegistry.find(name).tags(tags).timer();
    return timer != null ? timer.count() : 0L;
  }
}
