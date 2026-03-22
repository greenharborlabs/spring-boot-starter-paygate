package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for annotation scanning via {@link PaygateEndpointRegistry#scanAnnotatedEndpoints}.
 * Covers {@link PaymentRequired}, {@link PaygateProtected}, and dual-annotation precedence.
 */
@DisplayName("PaymentRequired annotation scanning")
class PaymentRequiredAnnotationTest {

    private static final long DEFAULT_TIMEOUT = 7200L;

    private PaygateEndpointRegistry registry;
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void setUp() {
        registry = new PaygateEndpointRegistry(DEFAULT_TIMEOUT);
        handlerMapping = mock(RequestMappingHandlerMapping.class);
    }

    @Test
    @DisplayName("@PaymentRequired annotation is discovered and registered with correct values")
    void paymentRequiredOnlyIsDiscovered() {
        var handlerMethod = mockHandlerMethod(
                mockPaymentRequired(100L, 900L, "premium endpoint", "dynamic", "read"),
                null
        );
        var mappingInfo = mockMappingInfo("/api/premium", RequestMethod.GET);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

        registry.scanAnnotatedEndpoints(handlerMapping);

        assertThat(registry.size()).isEqualTo(1);
        PaygateEndpointConfig config = registry.findConfig("GET", "/api/premium");
        assertThat(config).isNotNull();
        assertThat(config.priceSats()).isEqualTo(100L);
        assertThat(config.timeoutSeconds()).isEqualTo(900L);
        assertThat(config.description()).isEqualTo("premium endpoint");
        assertThat(config.pricingStrategy()).isEqualTo("dynamic");
        assertThat(config.capability()).isEqualTo("read");
    }

    @Test
    @DisplayName("@PaygateProtected annotation still works for backward compatibility")
    void paygateProtectedOnlyStillWorks() {
        var handlerMethod = mockHandlerMethod(
                null,
                mockPaygateProtected(50L, 600L, "legacy endpoint", "", "write")
        );
        var mappingInfo = mockMappingInfo("/api/legacy", RequestMethod.POST);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

        registry.scanAnnotatedEndpoints(handlerMapping);

        assertThat(registry.size()).isEqualTo(1);
        PaygateEndpointConfig config = registry.findConfig("POST", "/api/legacy");
        assertThat(config).isNotNull();
        assertThat(config.priceSats()).isEqualTo(50L);
        assertThat(config.timeoutSeconds()).isEqualTo(600L);
        assertThat(config.description()).isEqualTo("legacy endpoint");
        assertThat(config.capability()).isEqualTo("write");
    }

    @Test
    @DisplayName("both annotations on same method uses @PaymentRequired values and ignores @PaygateProtected")
    void dualAnnotationUsesPaymentRequiredValues() {
        // Use distinct values for each annotation so we can verify which one wins
        var handlerMethod = mockHandlerMethod(
                mockPaymentRequired(200L, 1800L, "payment-required desc", "tiered", "admin"),
                mockPaygateProtected(999L, 300L, "paygate-protected desc", "flat", "user")
        );
        var mappingInfo = mockMappingInfo("/api/dual", RequestMethod.PUT);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

        registry.scanAnnotatedEndpoints(handlerMapping);

        assertThat(registry.size()).isEqualTo(1);
        PaygateEndpointConfig config = registry.findConfig("PUT", "/api/dual");
        assertThat(config).isNotNull();
        // @PaymentRequired values must win
        assertThat(config.priceSats()).isEqualTo(200L);
        assertThat(config.timeoutSeconds()).isEqualTo(1800L);
        assertThat(config.description()).isEqualTo("payment-required desc");
        assertThat(config.pricingStrategy()).isEqualTo("tiered");
        assertThat(config.capability()).isEqualTo("admin");
    }

    @Test
    @DisplayName("methods without either annotation are not registered")
    void neitherAnnotationIsIgnored() {
        var handlerMethod = mockHandlerMethod(null, null);
        var mappingInfo = mockMappingInfo("/api/free", RequestMethod.GET);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

        registry.scanAnnotatedEndpoints(handlerMapping);

        assertThat(registry.size()).isZero();
        assertThat(registry.findConfig("GET", "/api/free")).isNull();
    }

    @Test
    @DisplayName("@PaymentRequired with timeoutSeconds=-1 resolves to default timeout")
    void sentinelTimeoutResolvesToDefault() {
        var handlerMethod = mockHandlerMethod(
                mockPaymentRequired(25L, -1L, "", "", ""),
                null
        );
        var mappingInfo = mockMappingInfo("/api/default-timeout", RequestMethod.GET);

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

        registry.scanAnnotatedEndpoints(handlerMapping);

        PaygateEndpointConfig config = registry.findConfig("GET", "/api/default-timeout");
        assertThat(config).isNotNull();
        assertThat(config.timeoutSeconds()).isEqualTo(DEFAULT_TIMEOUT);
    }

    // --- helpers ---

    private static HandlerMethod mockHandlerMethod(PaymentRequired paymentRequired,
                                                   PaygateProtected paygateProtected) {
        HandlerMethod hm = mock(HandlerMethod.class);
        when(hm.getMethodAnnotation(PaymentRequired.class)).thenReturn(paymentRequired);
        when(hm.getMethodAnnotation(PaygateProtected.class)).thenReturn(paygateProtected);
        // Needed for the warning log message when both annotations are present
        doReturn(PaymentRequiredAnnotationTest.class).when(hm).getBeanType();
        try {
            when(hm.getMethod()).thenReturn(PaymentRequiredAnnotationTest.class.getDeclaredMethod("stubMethod"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        return hm;
    }

    @SuppressWarnings("unused")
    private void stubMethod() {
        // Target method for HandlerMethod mock
    }

    private static RequestMappingInfo mockMappingInfo(String path, RequestMethod... methods) {
        RequestMappingInfo info = mock(RequestMappingInfo.class);
        when(info.getDirectPaths()).thenReturn(Set.of(path));
        var methodsCondition = mock(RequestMethodsRequestCondition.class);
        when(methodsCondition.getMethods()).thenReturn(Set.of(methods));
        when(info.getMethodsCondition()).thenReturn(methodsCondition);
        return info;
    }

    private static PaymentRequired mockPaymentRequired(long priceSats, long timeoutSeconds,
                                                       String description, String pricingStrategy,
                                                       String capability) {
        return new PaymentRequired() {
            @Override public Class<? extends Annotation> annotationType() { return PaymentRequired.class; }
            @Override public long priceSats() { return priceSats; }
            @Override public long timeoutSeconds() { return timeoutSeconds; }
            @Override public String description() { return description; }
            @Override public String pricingStrategy() { return pricingStrategy; }
            @Override public String capability() { return capability; }
        };
    }

    private static PaygateProtected mockPaygateProtected(long priceSats, long timeoutSeconds,
                                                         String description, String pricingStrategy,
                                                         String capability) {
        return new PaygateProtected() {
            @Override public Class<? extends Annotation> annotationType() { return PaygateProtected.class; }
            @Override public long priceSats() { return priceSats; }
            @Override public long timeoutSeconds() { return timeoutSeconds; }
            @Override public String description() { return description; }
            @Override public String pricingStrategy() { return pricingStrategy; }
            @Override public String capability() { return capability; }
        };
    }
}
