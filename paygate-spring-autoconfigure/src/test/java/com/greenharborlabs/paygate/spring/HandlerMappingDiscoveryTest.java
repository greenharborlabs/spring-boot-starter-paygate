package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import java.lang.annotation.Annotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Discovery tests for payment-protected handler mapping sources. */
@DisplayName("HandlerMapping discovery")
class HandlerMappingDiscoveryTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PaygateAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true", "paygate.backend=lnbits", "paygate.root-key-store=memory")
          .withBean(LightningBackend.class, () -> mock(LightningBackend.class));

  @Test
  @DisplayName(
      "auto-configuration discovers paid handlers from every supported HandlerMapping bean")
  void autoConfigurationDiscoversPaidHandlersFromEverySupportedSource() {
    contextRunner
        .withBean(
            "requestMappingHandlerMapping",
            RequestMappingHandlerMapping.class,
            () -> handlerMapping("/first", "first"))
        .withBean(
            "additionalRequestMappingHandlerMapping",
            RequestMappingHandlerMapping.class,
            () -> handlerMapping("/second", "second"))
        .run(
            context -> {
              var registry = context.getBean(PaygateEndpointRegistry.class);

              assertThat(registry.findConfig("GET", "/first").capability()).isEqualTo("first");
              assertThat(registry.findConfig("GET", "/second").capability()).isEqualTo("second");
            });
  }

  @Test
  @DisplayName("an unsupported handler source capable of returning a paid handler fails startup")
  void unsupportedPaidHandlerSourceFailsClosedAtStartup() throws Exception {
    var handler = mock(HandlerMethod.class);
    when(handler.getMethodAnnotation(PaymentRequired.class)).thenReturn(paymentRequired("paid"));
    var unsupported = mock(HandlerMapping.class);
    when(unsupported.getHandler(any())).thenReturn(new HandlerExecutionChain(handler));

    contextRunner
        .withBean(
            "requestMappingHandlerMapping",
            RequestMappingHandlerMapping.class,
            () -> handlerMapping("/known", "known"))
        .withBean("unsupportedPaidHandlerMapping", HandlerMapping.class, () -> unsupported)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("unsupported");
            });
  }

  private static RequestMappingHandlerMapping handlerMapping(String path, String capability) {
    var mapping = mock(RequestMappingHandlerMapping.class);
    var handler = mock(HandlerMethod.class);
    when(handler.getMethodAnnotation(PaymentRequired.class))
        .thenReturn(paymentRequired(capability));
    when(mapping.getHandlerMethods())
        .thenReturn(
            java.util.Map.of(
                RequestMappingInfo.paths(path).methods(RequestMethod.GET).build(), handler));
    return mapping;
  }

  private static PaymentRequired paymentRequired(String capability) {
    return new PaymentRequired() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return PaymentRequired.class;
      }

      @Override
      public long priceSats() {
        return 10;
      }

      @Override
      public long timeoutSeconds() {
        return 600;
      }

      @Override
      public String description() {
        return "";
      }

      @Override
      public String pricingStrategy() {
        return "";
      }

      @Override
      public String capability() {
        return capability;
      }
    };
  }
}
