package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.MappingMatch;
import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/** Parity tests between Paygate's route selection and Spring MVC's request path semantics. */
@DisplayName("MVC route matching parity")
class RouteMatchingParityTest {

  @Test
  @DisplayName("case-sensitive mappings do not protect a differently cased request path")
  void respectsSpringDefaultCaseSensitivity() {
    var registry = new PaygateEndpointRegistry();
    registry.register(new PaygateEndpointConfig("GET", "/Orders", 10, 600, "", "", "read"));

    assertThat(registry.resolve("GET", "/Orders")).isNotNull();
    assertThat(registry.resolve("GET", "/orders")).isNull();
  }

  @Test
  @DisplayName(
      "a custom case-insensitive Spring parser and the paid-route registry select the same route")
  void matchesCustomCaseInsensitivePathPatternParser() {
    var parser = new PathPatternParser();
    parser.setCaseSensitive(false);
    var options = new RequestMappingInfo.BuilderConfiguration();
    options.setPatternParser(parser);
    var mapping = mock(RequestMappingHandlerMapping.class);
    var mappings = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
    mappings.put(
        RequestMappingInfo.paths("/Orders").options(options).methods(RequestMethod.GET).build(),
        handler(paymentRequired()));
    when(mapping.getHandlerMethods()).thenReturn(mappings);
    var registry = new PaygateEndpointRegistry();

    registry.scanAnnotatedEndpoints(mapping);

    assertThat(
            parser
                .parse("/Orders")
                .matches(org.springframework.http.server.PathContainer.parsePath("/orders")))
        .isTrue();
    assertThat(registry.resolve("GET", "/orders")).isNotNull();
  }

  @Test
  @DisplayName("decoded UTF-8 application path is the path used for route matching")
  void matchesTheDecodedUtf8ApplicationRelativePath() {
    var request = new MockHttpServletRequest("GET", "/shop/orders/caf%C3%A9");
    request.setContextPath("/shop");
    var registry = new PaygateEndpointRegistry();
    registry.register(new PaygateEndpointConfig("GET", "/orders/café", 10, 600, "", "", "read"));

    assertThat(ApplicationRelativeRequestResolver.resolve(request)).isEqualTo("/orders/café");
    assertThat(registry.resolve(request)).isNotNull();
  }

  @Test
  @DisplayName("path servlet mapping is removed before policy lookup")
  void matchesApplicationRelativePathUnderServletMapping() {
    var request = new MockHttpServletRequest("GET", "/shop/gateway/orders/42");
    request.setContextPath("/shop");
    request.setServletPath("/gateway");
    request.setHttpServletMapping(
        new MockHttpServletMapping("orders/42", "/gateway/*", "dispatcher", MappingMatch.PATH));
    var registry = new PaygateEndpointRegistry();
    registry.register(new PaygateEndpointConfig("GET", "/orders/{id}", 10, 600, "", "", "read"));

    assertThat(ApplicationRelativeRequestResolver.resolve(request)).isEqualTo("/orders/42");
    assertThat(registry.resolve(request)).isNotNull();
  }

  @Test
  @DisplayName("an unprotected mapping that Spring selects over a paid pattern remains unprotected")
  void unprotectedHigherPriorityMappingWinsOverPaidPattern() {
    var registry = new PaygateEndpointRegistry();
    var mapping = mock(RequestMappingHandlerMapping.class);
    var mappings = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
    mappings.put(
        RequestMappingInfo.paths("/orders/public").methods(RequestMethod.GET).build(),
        handler(null));
    mappings.put(
        RequestMappingInfo.paths("/orders/{id}").methods(RequestMethod.GET).build(),
        handler(paymentRequired()));
    when(mapping.getHandlerMethods()).thenReturn(mappings);

    registry.scanAnnotatedEndpoints(mapping);

    assertThat(registry.resolve(new MockHttpServletRequest("GET", "/orders/public"))).isNull();
  }

  private static HandlerMethod handler(PaymentRequired annotation) {
    var handler = mock(HandlerMethod.class);
    when(handler.getMethodAnnotation(PaymentRequired.class)).thenReturn(annotation);
    return handler;
  }

  private static PaymentRequired paymentRequired() {
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
        return "read";
      }
    };
  }
}
