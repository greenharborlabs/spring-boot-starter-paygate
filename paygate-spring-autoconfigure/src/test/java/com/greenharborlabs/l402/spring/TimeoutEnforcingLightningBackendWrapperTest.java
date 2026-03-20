package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.lightning.LightningException;
import com.greenharborlabs.l402.core.lightning.LightningTimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TimeoutEnforcingLightningBackendWrapper")
class TimeoutEnforcingLightningBackendWrapperTest {

    // --- Unit tests ---

    @Test
    @DisplayName("createInvoice delegates to backend and returns result")
    void createInvoiceDelegates() {
        var delegate = new StubBackend();
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 5);

        Invoice result = wrapper.createInvoice(100, "test");

        assertThat(result).isNotNull();
        assertThat(result.amountSats()).isEqualTo(100);
        assertThat(result.memo()).isEqualTo("test");
    }

    @Test
    @DisplayName("lookupInvoice delegates to backend and returns result")
    void lookupInvoiceDelegates() {
        var delegate = new StubBackend();
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 5);

        Invoice result = wrapper.lookupInvoice(new byte[32]);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("isHealthy delegates to backend and returns result")
    void isHealthyDelegates() {
        var delegate = new StubBackend();
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 5);

        assertThat(wrapper.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("createInvoice throws LightningTimeoutException on slow backend")
    void createInvoiceTimesOut() {
        var delegate = new SlowBackend();
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 1);

        assertThatThrownBy(() -> wrapper.createInvoice(100, "test"))
                .isInstanceOf(LightningTimeoutException.class)
                .hasMessageContaining("createInvoice")
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("lookupInvoice throws LightningTimeoutException on slow backend")
    void lookupInvoiceTimesOut() {
        var delegate = new SlowBackend();
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 1);

        assertThatThrownBy(() -> wrapper.lookupInvoice(new byte[32]))
                .isInstanceOf(LightningTimeoutException.class)
                .hasMessageContaining("lookupInvoice")
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("isHealthy returns false on slow backend (does not throw)")
    void isHealthyReturnsFalseOnTimeout() {
        var delegate = new SlowBackend();
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 1);

        assertThat(wrapper.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("LightningException from backend is propagated unchanged")
    void lightningExceptionPropagatedUnchanged() {
        var expected = new LightningException("backend failure");
        LightningBackend delegate = new LightningBackend() {
            @Override
            public Invoice createInvoice(long amountSats, String memo) {
                throw expected;
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
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 5);

        assertThatThrownBy(() -> wrapper.createInvoice(100, "test"))
                .isSameAs(expected);
    }

    @Test
    @DisplayName("non-LightningException from backend is wrapped in LightningException")
    void nonLightningExceptionWrapped() {
        LightningBackend delegate = new LightningBackend() {
            @Override
            public Invoice createInvoice(long amountSats, String memo) {
                throw new RuntimeException("unexpected error");
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
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 5);

        assertThatThrownBy(() -> wrapper.createInvoice(100, "test"))
                .isInstanceOf(LightningException.class)
                .isNotInstanceOf(LightningTimeoutException.class)
                .hasMessageContaining("createInvoice")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("rejects null delegate")
    void rejectsNullDelegate() {
        assertThatThrownBy(() -> new TimeoutEnforcingLightningBackendWrapper(null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegate");
    }

    @Test
    @DisplayName("rejects zero timeout")
    void rejectsZeroTimeout() {
        assertThatThrownBy(() -> new TimeoutEnforcingLightningBackendWrapper(new StubBackend(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
    }

    @Test
    @DisplayName("rejects negative timeout")
    void rejectsNegativeTimeout() {
        assertThatThrownBy(() -> new TimeoutEnforcingLightningBackendWrapper(new StubBackend(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
    }

    @Test
    @DisplayName("close shuts down the executor without error")
    void closeShutdownsExecutor() {
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(new StubBackend(), 5);
        wrapper.close();
        // After close, submitting new tasks should fail
        assertThatThrownBy(() -> wrapper.createInvoice(100, "test"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("getDelegate returns the wrapped backend")
    void getDelegateReturnsWrappedBackend() {
        var delegate = new StubBackend();
        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 5);

        assertThat(wrapper.getDelegate()).isSameAs(delegate);
    }

    @Test
    @DisplayName("timeout cancels the virtual thread")
    void timeoutCancelsVirtualThread() throws InterruptedException {
        var interrupted = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        LightningBackend delegate = new LightningBackend() {
            @Override
            public Invoice createInvoice(long amountSats, String memo) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    latch.countDown();
                }
                return null;
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

        var wrapper = new TimeoutEnforcingLightningBackendWrapper(delegate, 1);

        assertThatThrownBy(() -> wrapper.createInvoice(100, "test"))
                .isInstanceOf(LightningTimeoutException.class);

        // Wait for the virtual thread to receive the interrupt
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.get()).isTrue();
    }

    // --- Auto-configuration integration tests ---

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    L402AutoConfiguration.class,
                    WebMvcAutoConfiguration.class
            ))
            .withPropertyValues(
                    "l402.enabled=true",
                    "l402.root-key-store=memory"
            );

    @Test
    @DisplayName("auto-config wraps backend with timeout wrapper")
    void autoConfigWrapsBackendWithTimeout() {
        contextRunner
                .withPropertyValues("l402.health-cache.enabled=false")
                .withBean(LightningBackend.class, StubBackend::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(LightningBackend.class);
                    var bean = context.getBean(LightningBackend.class);
                    assertThat(bean).isInstanceOf(TimeoutEnforcingLightningBackendWrapper.class);
                    assertThat(((TimeoutEnforcingLightningBackendWrapper) bean).getDelegate())
                            .isInstanceOf(StubBackend.class);
                });
    }

    @Test
    @DisplayName("auto-config does not wrap TestModeLightningBackend")
    void autoConfigDoesNotWrapTestMode() {
        contextRunner
                .withPropertyValues("l402.health-cache.enabled=false")
                .withBean(LightningBackend.class, TestModeLightningBackend::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(LightningBackend.class);
                    var bean = context.getBean(LightningBackend.class);
                    assertThat(bean).isInstanceOf(TestModeLightningBackend.class);
                });
    }

    @Test
    @DisplayName("wrapping order is CachingWrapper(TimeoutWrapper(Backend))")
    void correctWrappingOrder() {
        contextRunner
                .withBean(LightningBackend.class, StubBackend::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(LightningBackend.class);
                    var bean = context.getBean(LightningBackend.class);

                    // Outermost should be CachingLightningBackendWrapper
                    assertThat(bean).isInstanceOf(CachingLightningBackendWrapper.class);
                    var cachingWrapper = (CachingLightningBackendWrapper) bean;

                    // Inner should be TimeoutEnforcingLightningBackendWrapper
                    assertThat(cachingWrapper.getDelegate())
                            .isInstanceOf(TimeoutEnforcingLightningBackendWrapper.class);
                    var timeoutWrapper = (TimeoutEnforcingLightningBackendWrapper) cachingWrapper.getDelegate();

                    // Innermost should be the original backend
                    assertThat(timeoutWrapper.getDelegate())
                            .isInstanceOf(StubBackend.class);
                });
    }

    @Test
    @DisplayName("auto-config respects custom timeout-seconds property")
    void autoConfigRespectsCustomTimeout() {
        contextRunner
                .withPropertyValues(
                        "l402.lightning.timeout-seconds=15",
                        "l402.health-cache.enabled=false"
                )
                .withBean(LightningBackend.class, StubBackend::new)
                .run(context -> {
                    var bean = context.getBean(LightningBackend.class);
                    assertThat(bean).isInstanceOf(TimeoutEnforcingLightningBackendWrapper.class);
                    var props = context.getBean(L402Properties.class);
                    assertThat(props.getLightning().getTimeoutSeconds()).isEqualTo(15);
                });
    }

    // --- Helpers ---

    static class StubBackend implements LightningBackend {
        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            return stubInvoice(amountSats, memo);
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            return stubInvoice(1, "lookup");
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }

    static class SlowBackend implements LightningBackend {
        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }

        @Override
        public boolean isHealthy() {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    private static Invoice stubInvoice(long amountSats, String memo) {
        byte[] hash = new byte[32];
        new SecureRandom().nextBytes(hash);
        Instant now = Instant.now();
        return new Invoice(hash, "lnbc" + amountSats + "n1pstub", amountSats,
                memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
    }
}
