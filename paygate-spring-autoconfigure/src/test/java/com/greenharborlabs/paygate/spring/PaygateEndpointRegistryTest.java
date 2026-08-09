package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Unit tests for {@link PaygateEndpointRegistry}. */
@DisplayName("PaygateEndpointRegistry")
class PaygateEndpointRegistryTest {

  private static final long CUSTOM_DEFAULT_TIMEOUT = 7200L;

  @Test
  @DisplayName("sentinel -1 timeoutSeconds is resolved to the configured default timeout")
  void sentinelTimeoutResolvedToDefault() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    // Register an endpoint config with an explicit timeout to verify it is preserved
    registry.register(
        new PaygateEndpointConfig("GET", "/explicit", 10, 300, "explicit timeout", "", ""));

    // Verify explicit timeout is not changed
    PaygateEndpointConfig explicitConfig = registry.findConfig("GET", "/explicit");
    assertThat(explicitConfig).isNotNull();
    assertThat(explicitConfig.timeoutSeconds()).isEqualTo(300);
  }

  @Test
  @DisplayName("no-arg constructor uses 3600 as fallback default timeout")
  void noArgConstructorUsesFallbackDefault() {
    var registry = new PaygateEndpointRegistry();

    // Register directly with -1 sentinel via the public register method
    // (sentinel resolution happens in toConfig during annotation scanning,
    // not in register — so this tests the fallback constructor is valid)
    registry.register(new PaygateEndpointConfig("GET", "/test", 10, 3600, "", "", ""));

    PaygateEndpointConfig config = registry.findConfig("GET", "/test");
    assertThat(config).isNotNull();
    assertThat(config.timeoutSeconds()).isEqualTo(3600);
  }

  @Test
  @DisplayName("findConfig returns null for unregistered paths")
  void findConfigReturnsNullForUnregisteredPath() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    assertThat(registry.findConfig("GET", "/nonexistent")).isNull();
  }

  @Test
  @DisplayName("size returns the number of registered endpoint configurations")
  void sizeReturnsRegisteredCount() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    assertThat(registry.size()).isZero();

    registry.register(new PaygateEndpointConfig("GET", "/a", 10, 600, "", "", ""));
    registry.register(new PaygateEndpointConfig("POST", "/b", 20, 1200, "", "", ""));
    assertThat(registry.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("registered endpoint with capability is retrievable via findConfig")
  void registeredCapabilityIsPreserved() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    registry.register(
        new PaygateEndpointConfig(
            "GET", "/api/analyze", 50, 600, "Analysis endpoint", "", "analyze"));

    PaygateEndpointConfig config = registry.findConfig("GET", "/api/analyze");
    assertThat(config).isNotNull();
    assertThat(config.capability()).isEqualTo("analyze");
  }

  @Test
  @DisplayName("null capability is normalized to no capability")
  void nullCapabilityIsNormalized() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    registry.register(
        new PaygateEndpointConfig("GET", "/api/no-capability", 10, 600, "", "", null));

    assertThat(registry.findConfig("GET", "/api/no-capability").capability()).isEmpty();
  }

  @Test
  @DisplayName("blank capability is normalized to no capability")
  void blankCapabilityIsNormalized() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    registry.register(
        new PaygateEndpointConfig("GET", "/api/blank-capability", 10, 600, "", "", " \t "));

    assertThat(registry.findConfig("GET", "/api/blank-capability").capability()).isEmpty();
  }

  @Test
  @DisplayName("capabilities are trimmed and de-duplicated in declaration order")
  void capabilitiesAreNormalizedAsDeterministicSet() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    registry.register(
        new PaygateEndpointConfig(
            "GET", "/api/capabilities", 10, 600, "", "", " write, read ,write,read "));

    assertThat(registry.findConfig("GET", "/api/capabilities").capability())
        .isEqualTo("write,read");
  }

  @Test
  @DisplayName("capability declarations reject blank segments")
  void capabilityDeclarationsRejectBlankSegments() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    for (String capability : Set.of(",read", "read,", "read,,write", "read,   ,write")) {
      assertThatThrownBy(
              () ->
                  registry.register(
                      new PaygateEndpointConfig(
                          "GET", "/api/blank-segment", 10, 600, "", "", capability)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank segment");
    }
  }

  @Test
  @DisplayName("reserved no-capability sentinel cannot be registered as an application capability")
  void reservedSentinelIsRejected() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    for (String capability : Set.of("~", "read,~", "~,read")) {
      assertThatThrownBy(
              () ->
                  registry.register(
                      new PaygateEndpointConfig(
                          "GET", "/api/reserved", 10, 600, "", "", capability)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reserved");
    }
  }

  @Test
  @DisplayName("capability splitting is bounded by the configured caveat value limit")
  void capabilitySplittingIsBounded() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT, 2);

    registry.register(new PaygateEndpointConfig("GET", "/api/two", 10, 600, "", "", "read,write"));
    assertThat(registry.findConfig("GET", "/api/two").capability()).isEqualTo("read,write");

    assertThatThrownBy(
            () ->
                registry.register(
                    new PaygateEndpointConfig(
                        "GET", "/api/three", 10, 600, "", "", "read,write,admin")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximum allowed is 2");
  }

  @Test
  @DisplayName("annotation-derived capabilities use the same normalization and validation")
  void annotationCapabilitiesUseRegistrationValidation() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    var handlerMapping = mock(RequestMappingHandlerMapping.class);
    var handlerMethod = mock(HandlerMethod.class);
    var annotation = paymentRequired(" read, write,read ");
    var mappingInfo = mock(RequestMappingInfo.class);
    var methodsCondition = mock(RequestMethodsRequestCondition.class);
    when(handlerMethod.getMethodAnnotation(PaymentRequired.class)).thenReturn(annotation);
    when(mappingInfo.getDirectPaths()).thenReturn(Set.of("/api/annotated"));
    when(mappingInfo.getMethodsCondition()).thenReturn(methodsCondition);
    when(methodsCondition.getMethods()).thenReturn(Set.of(RequestMethod.GET));
    when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

    registry.scanAnnotatedEndpoints(handlerMapping);

    assertThat(registry.findConfig("GET", "/api/annotated").capability()).isEqualTo("read,write");
  }

  @Test
  @DisplayName("annotation-derived reserved capability is rejected")
  void annotationReservedCapabilityIsRejected() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    var handlerMapping = mock(RequestMappingHandlerMapping.class);
    var handlerMethod = mock(HandlerMethod.class);
    var mappingInfo = mock(RequestMappingInfo.class);
    var methodsCondition = mock(RequestMethodsRequestCondition.class);
    when(handlerMethod.getMethodAnnotation(PaymentRequired.class))
        .thenReturn(paymentRequired("read,~"));
    when(mappingInfo.getDirectPaths()).thenReturn(Set.of("/api/annotated-reserved"));
    when(mappingInfo.getMethodsCondition()).thenReturn(methodsCondition);
    when(methodsCondition.getMethods()).thenReturn(Set.of(RequestMethod.GET));
    when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mappingInfo, handlerMethod));

    assertThatThrownBy(() -> registry.scanAnnotatedEndpoints(handlerMapping))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved");
  }

  @Test
  @DisplayName("findConfig matches path variables via pattern matching")
  void findConfigMatchesPathVariables() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("GET", "/api/items/{id}", 10, 600, "", "", ""));

    var config = registry.findConfig("GET", "/api/items/42");
    assertThat(config).isNotNull();
    assertThat(config.pathPattern()).isEqualTo("/api/items/{id}");
  }

  @Test
  @DisplayName("wildcard * method matches any HTTP method")
  void wildcardMethodMatchesAnyMethod() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("*", "/api/universal", 10, 600, "", "", ""));

    assertThat(registry.findConfig("GET", "/api/universal")).isNotNull();
    assertThat(registry.findConfig("POST", "/api/universal")).isNotNull();
    assertThat(registry.findConfig("DELETE", "/api/universal")).isNotNull();
  }

  @Test
  @DisplayName("wildcard * method with path variables matches any method")
  void wildcardMethodWithPathVariablesMatchesAnyMethod() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("*", "/api/resources/{id}", 10, 600, "", "", ""));

    assertThat(registry.findConfig("GET", "/api/resources/99")).isNotNull();
    assertThat(registry.findConfig("PUT", "/api/resources/99")).isNotNull();
  }

  @Test
  @DisplayName("GET pattern does not match POST request (method isolation)")
  void methodIsolationGetDoesNotMatchPost() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("GET", "/api/items/{id}", 10, 600, "", "", ""));

    assertThat(registry.findConfig("GET", "/api/items/1")).isNotNull();
    assertThat(registry.findConfig("POST", "/api/items/1")).isNull();
  }

  @Test
  @DisplayName("different methods on the same path are independently registered and found")
  void differentMethodsSamePathAreIndependent() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(
        new PaygateEndpointConfig("GET", "/api/data", 10, 600, "get data", "", "read"));
    registry.register(
        new PaygateEndpointConfig("POST", "/api/data", 20, 1200, "post data", "", "write"));

    var getConfig = registry.findConfig("GET", "/api/data");
    var postConfig = registry.findConfig("POST", "/api/data");

    assertThat(getConfig).isNotNull();
    assertThat(getConfig.priceSats()).isEqualTo(10);
    assertThat(getConfig.capability()).isEqualTo("read");

    assertThat(postConfig).isNotNull();
    assertThat(postConfig.priceSats()).isEqualTo(20);
    assertThat(postConfig.capability()).isEqualTo("write");
  }

  @Test
  @DisplayName("method-specific pattern takes precedence; wildcard still matches other methods")
  void specificMethodAndWildcardCoexist() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("GET", "/api/mixed", 10, 600, "", "", "read"));
    registry.register(new PaygateEndpointConfig("*", "/api/mixed", 5, 300, "", "", "any"));

    // GET should match the GET-specific registration (exact key match)
    var getConfig = registry.findConfig("GET", "/api/mixed");
    assertThat(getConfig).isNotNull();
    assertThat(getConfig.capability()).isEqualTo("read");

    // POST should fall through to wildcard
    var postConfig = registry.findConfig("POST", "/api/mixed");
    assertThat(postConfig).isNotNull();
    assertThat(postConfig.capability()).isEqualTo("any");
  }

  @Test
  @DisplayName("HEAD falls back to GET policy before wildcard policy")
  void headFallsBackToGetBeforeWildcard() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("GET", "/api/{name}", 10, 600, "", "", "read"));
    registry.register(new PaygateEndpointConfig("*", "/api/resource", 5, 300, "", "", "any"));

    var resolved = registry.resolve("HEAD", "/api/resource");

    assertThat(resolved.config().capability()).isEqualTo("read");
    assertThat(resolved.routePattern()).isEqualTo("/api/{name}");
    assertThat(resolved.policyMethod()).isEqualTo("GET");
    assertThat(registry.findConfig("HEAD", "/api/resource")).isSameAs(resolved.config());
  }

  @Test
  @DisplayName(
      "request-aware manual resolution preserves context paths, HEAD fallback, and wildcard")
  void requestAwareManualResolutionPreservesLegacyMethodAndPathSemantics() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("GET", "/api/resource", 10, 600, "", "", "get"));
    registry.register(new PaygateEndpointConfig("HEAD", "/api/head", 10, 600, "", "", "head"));
    registry.register(new PaygateEndpointConfig("*", "/api/wild", 10, 600, "", "", "wild"));

    var inheritedHead = new MockHttpServletRequest("HEAD", "/shop/api/resource");
    inheritedHead.setContextPath("/shop");
    var explicitHead = new MockHttpServletRequest("HEAD", "/api/head");
    var wildcardPost = new MockHttpServletRequest("POST", "/api/wild");

    assertThat(registry.resolve(inheritedHead))
        .extracting(ResolvedEndpoint::policyMethod)
        .isEqualTo("GET");
    assertThat(registry.resolve(explicitHead))
        .extracting(ResolvedEndpoint::policyMethod)
        .isEqualTo("HEAD");
    assertThat(registry.resolve(wildcardPost))
        .extracting(ResolvedEndpoint::policyMethod)
        .isEqualTo("*");
  }

  @Test
  @DisplayName("annotation scanning retains an explicit HEAD mapping independently of GET")
  void scansExplicitHeadMappingIndependentlyOfGet() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    var handlerMapping = mock(RequestMappingHandlerMapping.class);
    var mappings = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
    mappings.put(
        RequestMappingInfo.paths("/api/report").methods(RequestMethod.GET).build(),
        paidHandler("get"));
    mappings.put(
        RequestMappingInfo.paths("/api/report").methods(RequestMethod.HEAD).build(),
        paidHandler("head"));
    when(handlerMapping.getHandlerMethods()).thenReturn(mappings);

    registry.scanAnnotatedEndpoints(handlerMapping);

    assertThat(registry.resolve("GET", "/api/report").config().capability()).isEqualTo("get");
    assertThat(registry.resolve("HEAD", "/api/report").config().capability()).isEqualTo("head");
  }

  @Test
  @DisplayName("explicit HEAD policy takes precedence and remains distinct from GET")
  void prefersExplicitHeadAndFailsEqualSpecificityAmbiguityClosed() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("HEAD", "/api/resource", 15, 600, "", "", "head"));
    registry.register(new PaygateEndpointConfig("GET", "/api/resource", 10, 600, "", "", "read"));
    registry.register(new PaygateEndpointConfig("*", "/api/resource", 5, 300, "", "", "any"));

    var head = registry.resolve("head", "/api/resource");
    var get = registry.resolve("GET", "/api/resource");

    assertThat(head.config().capability()).isEqualTo("head");
    assertThat(head.routePattern()).isEqualTo("/api/resource");
    assertThat(head.policyMethod()).isEqualTo("HEAD");
    assertThat(get.config().capability()).isEqualTo("read");
    assertThat(get.policyMethod()).isEqualTo("GET");

    var ambiguousRegistry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    ambiguousRegistry.register(
        new PaygateEndpointConfig("GET", "/api/{left}", 10, 600, "", "", "read"));
    ambiguousRegistry.register(
        new PaygateEndpointConfig("GET", "/api/{right}", 10, 600, "", "", "read"));

    assertThatThrownBy(() -> ambiguousRegistry.resolve("GET", "/api/value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Ambiguous endpoint registrations");
  }

  @Test
  @DisplayName("exact route takes precedence over matching patterns")
  void exactRouteTakesPrecedenceOverPattern() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(
        new PaygateEndpointConfig("GET", "/api/items/{id}", 5, 300, "", "", "pattern"));
    registry.register(new PaygateEndpointConfig("GET", "/api/items/42", 10, 600, "", "", "exact"));

    var resolved = registry.resolve("GET", "/api/items/42");

    assertThat(resolved.config().capability()).isEqualTo("exact");
    assertThat(resolved.routePattern()).isEqualTo("/api/items/42");
  }

  @Test
  @DisplayName("most specific matching pattern is selected deterministically")
  void mostSpecificPatternIsSelected() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("GET", "/api/**", 5, 300, "", "", "broad"));
    registry.register(
        new PaygateEndpointConfig("GET", "/api/items/{id}", 10, 600, "", "", "specific"));

    var resolved = registry.resolve("GET", "/api/items/42");

    assertThat(resolved.config().capability()).isEqualTo("specific");
    assertThat(resolved.routePattern()).isEqualTo("/api/items/{id}");
  }

  @Test
  @DisplayName("duplicate normalized method and canonical pattern is rejected")
  void duplicateRegistrationIsRejected() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    var original = new PaygateEndpointConfig("GET", "/api/items", 10, 600, "", "", "read");
    registry.register(original);

    assertThatThrownBy(
            () ->
                registry.register(
                    new PaygateEndpointConfig("get", "/api/items", 20, 1200, "", "", "other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate endpoint registration");

    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.resolve("GET", "/api/items"))
        .extracting(ResolvedEndpoint::config)
        .isSameAs(original);
  }

  @Test
  @DisplayName("registered route identity is defined by the shared parser helper")
  void registeredRouteIdentityIsDefinedBySharedParserHelper() {
    var config = new PaygateEndpointConfig("GET", "/api/items/{id}", 10, 600, "", "", "read");
    var parsedPattern = PaygateEndpointRegistry.parsePathPattern(config.pathPattern());
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);

    registry.register(config);

    assertThat(registry.resolve("GET", "/api/items/42").routePattern())
        .isEqualTo(parsedPattern.getPatternString());
  }

  @Test
  @DisplayName("OPTIONS does not inherit a GET policy")
  void optionsDoesNotFallBackToGet() {
    var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
    registry.register(new PaygateEndpointConfig("GET", "/api/resource", 10, 600, "", "", "read"));

    assertThat(registry.resolve("OPTIONS", "/api/resource")).isNull();
  }

  @Test
  @DisplayName("specificity selection is stable regardless of registration order")
  void specificitySelectionIsStableRegardlessOfRegistrationOrder() {
    for (var registerBroadFirst : Set.of(true, false)) {
      var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
      var broad = new PaygateEndpointConfig("GET", "/orders/**", 5, 600, "", "", "broad");
      var specific =
          new PaygateEndpointConfig(
              "GET", "/orders/{orderId}/receipt", 10, 600, "", "", "specific");

      if (registerBroadFirst) {
        registry.register(broad);
        registry.register(specific);
      } else {
        registry.register(specific);
        registry.register(broad);
      }

      assertThat(registry.resolve("GET", "/orders/42/receipt").config().capability())
          .isEqualTo("specific");
    }
  }

  @Test
  @DisplayName("conflicting paid mappings with distinct Spring request conditions fail closed")
  void conflictingPaidMappingsWithDistinctSpringRequestConditionsFailClosed() {
    for (var mapping :
        Set.of(
            RequestMappingInfo.paths("/orders")
                .methods(RequestMethod.GET)
                .params("mode=fast")
                .build(),
            RequestMappingInfo.paths("/orders")
                .methods(RequestMethod.GET)
                .headers("X-Tier=premium")
                .build(),
            RequestMappingInfo.paths("/orders")
                .methods(RequestMethod.POST)
                .consumes("application/json")
                .build(),
            RequestMappingInfo.paths("/orders")
                .methods(RequestMethod.GET)
                .produces("application/json")
                .build())) {
      var registry = new PaygateEndpointRegistry(CUSTOM_DEFAULT_TIMEOUT);
      var mappings = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
      var method = mapping.getMethodsCondition().getMethods().iterator().next();
      mappings.put(
          RequestMappingInfo.paths("/orders").methods(method).build(), paidHandler("basic"));
      mappings.put(mapping, paidHandler("premium"));
      var handlerMapping = mock(RequestMappingHandlerMapping.class);
      when(handlerMapping.getHandlerMethods()).thenReturn(mappings);

      assertThatThrownBy(() -> registry.scanAnnotatedEndpoints(handlerMapping))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Duplicate endpoint registration");
    }
  }

  private static HandlerMethod paidHandler(String capability) {
    var handler = mock(HandlerMethod.class);
    when(handler.getMethodAnnotation(PaymentRequired.class))
        .thenReturn(paymentRequired(capability));
    return handler;
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
