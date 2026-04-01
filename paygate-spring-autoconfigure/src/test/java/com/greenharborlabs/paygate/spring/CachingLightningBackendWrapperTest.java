package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

@DisplayName("CachingLightningBackendWrapper")
class CachingLightningBackendWrapperTest {

  @Test
  @DisplayName("caches isHealthy() result within TTL")
  void cachesHealthResultWithinTtl() {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(true);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));

    assertThat(wrapper.isHealthy()).isTrue();
    assertThat(wrapper.isHealthy()).isTrue();
    assertThat(wrapper.isHealthy()).isTrue();

    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("refreshes cached value after TTL expires")
  void refreshesAfterTtlExpiry() throws InterruptedException {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(true);
    LightningBackend delegate = countingBackend(callCount, healthy);

    // Use a very short TTL so we can test expiry
    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofMillis(50));

    assertThat(wrapper.isHealthy()).isTrue();
    assertThat(callCount.get()).isEqualTo(1);

    // Wait for TTL to expire
    Thread.sleep(100);

    healthy.set(false);
    assertThat(wrapper.isHealthy()).isFalse();
    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("caches false result as well as true")
  void cachesFalseResult() {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(false);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));

    assertThat(wrapper.isHealthy()).isFalse();
    healthy.set(true); // Change underlying, but cache should still return false
    assertThat(wrapper.isHealthy()).isFalse();

    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("createInvoice() delegates directly without caching")
  void createInvoiceDelegatesDirectly() {
    var callCount = new AtomicInteger(0);
    LightningBackend delegate =
        new LightningBackend() {
          @Override
          public Invoice createInvoice(long amountSats, String memo) {
            callCount.incrementAndGet();
            return stubInvoice(amountSats, memo);
          }

          @Override
          public Invoice lookupInvoice(byte[] paymentHash) {
            return null;
          }

          @Override
          public boolean isHealthy() {
            return true;
          }
        };

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));
    wrapper.createInvoice(100, "test1");
    wrapper.createInvoice(200, "test2");

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("lookupInvoice() delegates directly without caching")
  void lookupInvoiceDelegatesDirectly() {
    var callCount = new AtomicInteger(0);
    LightningBackend delegate =
        new LightningBackend() {
          @Override
          public Invoice createInvoice(long amountSats, String memo) {
            return stubInvoice(amountSats, memo);
          }

          @Override
          public Invoice lookupInvoice(byte[] paymentHash) {
            callCount.incrementAndGet();
            return null;
          }

          @Override
          public boolean isHealthy() {
            return true;
          }
        };

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));
    wrapper.lookupInvoice(new byte[32]);
    wrapper.lookupInvoice(new byte[32]);

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("rejects null delegate")
  void rejectsNullDelegate() {
    assertThatThrownBy(() -> new CachingLightningBackendWrapper(null, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("delegate");
  }

  @Test
  @DisplayName("rejects null TTL")
  void rejectsNullTtl() {
    LightningBackend delegate = countingBackend(new AtomicInteger(), new AtomicBoolean(true));
    assertThatThrownBy(() -> new CachingLightningBackendWrapper(delegate, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttl");
  }

  @Test
  @DisplayName("rejects negative TTL")
  void rejectsNegativeTtl() {
    LightningBackend delegate = countingBackend(new AtomicInteger(), new AtomicBoolean(true));
    assertThatThrownBy(() -> new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("lastKnownHealthy() returns false when no snapshot exists")
  void lastKnownHealthyReturnsFalseBeforeAnyCall() {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(true);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));

    assertThat(wrapper.lastKnownHealthy()).isFalse();
    assertThat(callCount.get()).isZero();
  }

  @Test
  @DisplayName("lastKnownHealthy() returns cached true value without calling delegate")
  void lastKnownHealthyReturnsCachedTrueValue() {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(true);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));

    // Populate the cache
    assertThat(wrapper.isHealthy()).isTrue();
    assertThat(callCount.get()).isEqualTo(1);

    // lastKnownHealthy() should return true without calling delegate again
    assertThat(wrapper.lastKnownHealthy()).isTrue();
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("lastKnownHealthy() returns cached false value without calling delegate")
  void lastKnownHealthyReturnsCachedFalseValue() {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(false);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));

    // Populate the cache with false
    assertThat(wrapper.isHealthy()).isFalse();
    assertThat(callCount.get()).isEqualTo(1);

    // lastKnownHealthy() returns false without calling delegate
    assertThat(wrapper.lastKnownHealthy()).isFalse();
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("lastKnownHealthy() returns stale value even after TTL expires")
  void lastKnownHealthyReturnsStaleValueAfterTtlExpiry() throws InterruptedException {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(true);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofMillis(50));

    // Populate the cache
    assertThat(wrapper.isHealthy()).isTrue();
    assertThat(callCount.get()).isEqualTo(1);

    // Wait for TTL to expire
    Thread.sleep(100);

    // Change underlying health
    healthy.set(false);

    // lastKnownHealthy() still returns the stale true value without calling delegate
    assertThat(wrapper.lastKnownHealthy()).isTrue();
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("getDelegate() returns the wrapped backend")
  void getDelegateReturnsWrappedBackend() {
    LightningBackend delegate = countingBackend(new AtomicInteger(), new AtomicBoolean(true));
    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(5));
    assertThat(wrapper.getDelegate()).isSameAs(delegate);
  }

  @Test
  @DisplayName("thread safety: concurrent isHealthy() calls converge correctly")
  void threadSafetyConcurrentCalls() throws InterruptedException {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(true);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));

    var threads = new Thread[10];
    for (int i = 0; i < threads.length; i++) {
      threads[i] =
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (int j = 0; j < 100; j++) {
                      wrapper.isHealthy();
                    }
                  });
    }
    for (var thread : threads) {
      thread.join();
    }

    // With a 10-second TTL, there should be very few actual delegate calls
    // (ideally 1, but concurrent first-call race may cause a few more)
    assertThat(callCount.get()).isLessThanOrEqualTo(10);
  }

  @Test
  @DisplayName("PaygateMetrics gauge reads cached health and does not call delegate on scrape")
  void metricsGaugeUsesLastKnownHealthyNotIsHealthy() {
    var callCount = new AtomicInteger(0);
    var healthy = new AtomicBoolean(true);
    LightningBackend delegate = countingBackend(callCount, healthy);

    var wrapper = new CachingLightningBackendWrapper(delegate, Duration.ofSeconds(10));

    MeterRegistry registry = new SimpleMeterRegistry();
    CredentialStore stubStore =
        new CredentialStore() {
          @Override
          public void store(String tokenId, L402Credential credential, long ttlSeconds) {}

          @Override
          public L402Credential get(String tokenId) {
            return null;
          }

          @Override
          public void revoke(String tokenId) {}

          @Override
          public long activeCount() {
            return 0;
          }
        };

    // Prime wrapper health snapshot once; metrics should read this cached value only.
    assertThat(wrapper.isHealthy()).isTrue();
    int callsAfterPrime = callCount.get();

    // Construct PaygateMetrics — this registers a gauge backed by wrapper.lastKnownHealthy()
    new PaygateMetrics(registry, stubStore, wrapper);

    var gauge = registry.find("paygate.lightning.healthy").gauge();
    assertThat(gauge).isNotNull();
    assertThat(gauge.value()).isEqualTo(1.0);
    int callsAfterConstruction = callCount.get();
    assertThat(callsAfterConstruction).isEqualTo(callsAfterPrime);

    // Reading the gauge multiple times must NOT trigger additional delegate calls
    for (int i = 0; i < 10; i++) {
      gauge.value();
    }
    assertThat(callCount.get())
        .as("gauge reads must not trigger additional delegate calls")
        .isEqualTo(callsAfterConstruction);
  }

  // --- Auto-configuration integration tests ---

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(PaygateAutoConfiguration.class, WebMvcAutoConfiguration.class))
          .withPropertyValues("paygate.enabled=true", "paygate.root-key-store=memory");

  @Test
  @DisplayName("auto-config wraps LightningBackend with caching by default")
  void autoConfigWrapsBackendByDefault() {
    contextRunner
        .withBean(LightningBackend.class, StubBackend::new)
        .run(
            context -> {
              // BeanPostProcessor replaces the bean in-place
              assertThat(context).hasSingleBean(LightningBackend.class);
              var bean = context.getBean(LightningBackend.class);
              assertThat(bean).isInstanceOf(CachingLightningBackendWrapper.class);
              // The caching wrapper's delegate is a TimeoutEnforcingLightningBackendWrapper
              assertThat(((CachingLightningBackendWrapper) bean).getDelegate())
                  .isInstanceOf(TimeoutEnforcingLightningBackendWrapper.class);
            });
  }

  @Test
  @DisplayName("auto-config does not wrap with caching when health-cache.enabled=false")
  void autoConfigDoesNotWrapWhenDisabled() {
    contextRunner
        .withPropertyValues("paygate.health-cache.enabled=false")
        .withBean(LightningBackend.class, StubBackend::new)
        .run(
            context -> {
              assertThat(context).hasSingleBean(LightningBackend.class);
              var bean = context.getBean(LightningBackend.class);
              assertThat(bean).isNotInstanceOf(CachingLightningBackendWrapper.class);
            });
  }

  @Test
  @DisplayName("auto-config respects custom ttl-seconds property")
  void autoConfigRespectsCustomTtl() {
    contextRunner
        .withPropertyValues("paygate.health-cache.ttl-seconds=30")
        .withBean(LightningBackend.class, StubBackend::new)
        .run(
            context -> {
              var bean = context.getBean(LightningBackend.class);
              assertThat(bean).isInstanceOf(CachingLightningBackendWrapper.class);
              var props = context.getBean(PaygateProperties.class);
              assertThat(props.getHealthCache().getTtlSeconds()).isEqualTo(30);
            });
  }

  // --- Helpers ---

  private static LightningBackend countingBackend(AtomicInteger callCount, AtomicBoolean healthy) {
    return new LightningBackend() {
      @Override
      public Invoice createInvoice(long amountSats, String memo) {
        return stubInvoice(amountSats, memo);
      }

      @Override
      public Invoice lookupInvoice(byte[] paymentHash) {
        return null;
      }

      @Override
      public boolean isHealthy() {
        callCount.incrementAndGet();
        return healthy.get();
      }
    };
  }

  private static Invoice stubInvoice(long amountSats, String memo) {
    byte[] hash = new byte[32];
    new SecureRandom().nextBytes(hash);
    Instant now = Instant.now();
    return new Invoice(
        hash,
        "lnbc" + amountSats + "n1pstub",
        amountSats,
        memo,
        InvoiceStatus.PENDING,
        null,
        now,
        now.plus(1, ChronoUnit.HOURS));
  }

  static class StubBackend implements LightningBackend {
    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      return stubInvoice(amountSats, memo);
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
      return null;
    }

    @Override
    public boolean isHealthy() {
      return true;
    }
  }
}
