package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link L402MetricsAutoConfiguration}, verifying that the
 * {@link L402MeterFilter} bean is created and correctly caps endpoint
 * tag cardinality based on {@link L402Properties} values.
 */
@DisplayName("L402MetricsAutoConfiguration")
class L402MetricsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(L402MetricsAutoConfiguration.class))
            .withUserConfiguration(TestBeans.class)
            .withPropertyValues(
                    "l402.enabled=true",
                    "l402.metrics.max-endpoint-cardinality=5",
                    "l402.metrics.overflow-tag-value=_overflow"
            );

    @Configuration
    @EnableConfigurationProperties(L402Properties.class)
    static class TestBeans {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LightningBackend lightningBackend() {
            return new StubLightningBackend();
        }

        @Bean
        CredentialStore credentialStore() {
            return new InMemoryCredentialStore(1000);
        }
    }

    @Test
    @DisplayName("L402MeterFilter bean is created when l402.enabled=true and Micrometer is on classpath")
    void meterFilterBeanIsCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(L402MeterFilter.class);
        });
    }

    @Test
    @DisplayName("L402Metrics bean is created alongside L402MeterFilter")
    void metricsAndFilterBothCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(L402Metrics.class);
            assertThat(context).hasSingleBean(L402MeterFilter.class);
        });
    }

    @Test
    @DisplayName("filter caps endpoint cardinality at configured max and applies overflow tag")
    void filterCapsEndpointCardinality() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            // Register counters with distinct endpoint tags beyond the configured cap of 5
            for (int i = 0; i < 10; i++) {
                Counter.builder("l402.requests")
                        .tag("endpoint", "/api/endpoint-" + i)
                        .tag("result", "challenged")
                        .register(registry)
                        .increment();
            }

            // Count how many distinct endpoint tag values exist for l402.requests
            var endpointValues = registry.find("l402.requests").counters().stream()
                    .flatMap(counter -> counter.getId().getTags().stream())
                    .filter(tag -> "endpoint".equals(tag.getKey()))
                    .map(Tag::getValue)
                    .distinct()
                    .toList();

            // Should have at most 5 real endpoints + the overflow value
            long nonOverflowCount = endpointValues.stream()
                    .filter(v -> !"_overflow".equals(v))
                    .count();
            assertThat(nonOverflowCount).isLessThanOrEqualTo(5);

            // The overflow tag should be present since we registered 10 distinct endpoints
            assertThat(endpointValues).contains("_overflow");
        });
    }

    @Test
    @DisplayName("non-L402 meters are not affected by the filter")
    void nonL402MetersUnaffected() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            // Register counters with a non-L402 prefix; they should not be capped
            for (int i = 0; i < 10; i++) {
                Counter.builder("http.requests")
                        .tag("endpoint", "/other/endpoint-" + i)
                        .register(registry)
                        .increment();
            }

            var endpointValues = registry.find("http.requests").counters().stream()
                    .flatMap(counter -> counter.getId().getTags().stream())
                    .filter(tag -> "endpoint".equals(tag.getKey()))
                    .map(Tag::getValue)
                    .distinct()
                    .toList();

            // All 10 distinct values should exist (no overflow applied)
            assertThat(endpointValues).hasSize(10);
            assertThat(endpointValues).doesNotContain("_overflow");
        });
    }

    @Test
    @DisplayName("L402MeterFilter bean is not created when l402.enabled is false")
    void filterNotCreatedWhenDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(L402MetricsAutoConfiguration.class))
                .withUserConfiguration(TestBeans.class)
                .withPropertyValues("l402.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(L402MeterFilter.class);
                });
    }

    @Test
    @DisplayName("user-provided L402MeterFilter bean takes precedence")
    void userProvidedFilterTakesPrecedence() {
        contextRunner
                .withBean("l402MeterFilter", L402MeterFilter.class, () -> new L402MeterFilter(50, "_custom"))
                .run(context -> {
                    assertThat(context).hasSingleBean(L402MeterFilter.class);
                    // The user-provided filter should be used (cardinality 50, not 5)
                    // We verify by registering more than 5 but fewer than 50 endpoints
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    for (int i = 0; i < 10; i++) {
                        Counter.builder("l402.requests")
                                .tag("endpoint", "/api/user-ep-" + i)
                                .tag("result", "passed")
                                .register(registry)
                                .increment();
                    }

                    var endpointValues = registry.find("l402.requests").counters().stream()
                            .flatMap(counter -> counter.getId().getTags().stream())
                            .filter(tag -> "endpoint".equals(tag.getKey()))
                            .map(Tag::getValue)
                            .distinct()
                            .toList();

                    // All 10 should pass through since cap is 50
                    assertThat(endpointValues).doesNotContain("_custom");
                });
    }

    static class StubLightningBackend implements LightningBackend {

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            byte[] paymentHash = new byte[32];
            new SecureRandom().nextBytes(paymentHash);
            Instant now = Instant.now();
            return new Invoice(paymentHash, "lnbc" + amountSats + "n1pstub", amountSats,
                    memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
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
