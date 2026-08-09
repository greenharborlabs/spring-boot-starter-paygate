package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Covers the final MVC handler boundary for paid mappings. */
@DisplayName("Paygate handler interceptor")
class PaygateHandlerInterceptorTest {

  private static final String DECLARED_PAID_PATH = "/declared/paid";
  private static final String DYNAMIC_PAID_PATH = "/runtime/paid";

  /**
   * Internal request contract: a successful filter decision marks the exact paid handler it
   * authorized. The interceptor must fail closed when MVC selects a different paid handler.
   */
  private static final String SUCCESSFUL_PAID_HANDLER_ATTRIBUTE =
      "com.greenharborlabs.paygate.spring.PaygateSecurityFilter.successfulPaidHandler";

  @Test
  @DisplayName("rejects a selected paid handler when the filter policy marker is missing")
  void rejectsSelectedPaidHandlerWhenFilterPolicyMarkerIsMissing() throws Exception {
    var registry = new PaygateEndpointRegistry();
    registry.register(new PaygateEndpointConfig("GET", DECLARED_PAID_PATH, 1, 60, "paid", "", ""));
    var controller = new PaidController();
    var handler = dynamicallyRegisteredHandler(DECLARED_PAID_PATH, controller);
    var request = request(DECLARED_PAID_PATH);
    var response = new MockHttpServletResponse();

    var proceed = interceptor(registry).preHandle(request, response, handler);
    invokeSelectedHandlerIfAllowed(proceed, handler);

    assertThat(proceed).isFalse();
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(controller.calls).hasValue(0);
  }

  @Test
  @DisplayName("rejects a dynamically registered paid handler before its controller runs")
  void dynamicallyRegisteredPaidHandlerFailsBeforeController() throws Exception {
    var controller = new PaidController();
    var handler = dynamicallyRegisteredHandler(DYNAMIC_PAID_PATH, controller);
    var request = request(DYNAMIC_PAID_PATH);
    var response = new MockHttpServletResponse();

    var proceed = interceptor(new PaygateEndpointRegistry()).preHandle(request, response, handler);
    invokeSelectedHandlerIfAllowed(proceed, handler);

    assertThat(proceed).isFalse();
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(controller.calls).hasValue(0);
  }

  @Test
  @DisplayName("rejects a paid handler when the successful policy marker names another handler")
  void rejectsSelectedPaidHandlerWhenSuccessfulPolicyMarkerMismatches() throws Exception {
    var policyAController = new PolicyAController();
    var policyBController = new PolicyBController();
    var policyAMarker = dynamicallyRegisteredHandler("/runtime/policy-a", policyAController);
    var selectedPolicyBHandler =
        dynamicallyRegisteredHandler("/runtime/policy-b", policyBController);
    var request = request("/runtime/policy-b");
    request.setAttribute(SUCCESSFUL_PAID_HANDLER_ATTRIBUTE, policyAMarker);
    var response = new MockHttpServletResponse();

    var proceed =
        interceptor(new PaygateEndpointRegistry())
            .preHandle(request, response, selectedPolicyBHandler);
    invokeSelectedHandlerIfAllowed(proceed, selectedPolicyBHandler);

    assertThat(proceed).isFalse();
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(policyBController.calls).hasValue(0);
  }

  @Test
  @DisplayName("allows a paid handler when the successful policy marker names that handler")
  void allowsSelectedPaidHandlerWhenSuccessfulPolicyMarkerMatches() throws Exception {
    var policyBController = new PolicyBController();
    var selectedPolicyBHandler =
        dynamicallyRegisteredHandler("/runtime/policy-b", policyBController);
    var request = request("/runtime/policy-b");
    request.setAttribute(SUCCESSFUL_PAID_HANDLER_ATTRIBUTE, selectedPolicyBHandler);
    var response = new MockHttpServletResponse();

    var proceed =
        interceptor(new PaygateEndpointRegistry())
            .preHandle(request, response, selectedPolicyBHandler);
    invokeSelectedHandlerIfAllowed(proceed, selectedPolicyBHandler);

    assertThat(proceed).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(policyBController.calls).hasValue(1);
  }

  @Test
  @DisplayName("allows the same handler when MVC resolves the marker bean name to an instance")
  void allowsSameHandlerWhenMvcResolvesMarkerBeanName() throws Exception {
    var applicationContext = new StaticApplicationContext();
    var controller = new PaidController();
    applicationContext.getBeanFactory().registerSingleton("paidController", controller);
    applicationContext.refresh();
    var method = controller.getClass().getMethod("paid");
    var marker = new HandlerMethod("paidController", applicationContext, method);
    var selectedHandler = marker.createWithResolvedBean();
    var request = request("/runtime/paid");
    request.setAttribute(SUCCESSFUL_PAID_HANDLER_ATTRIBUTE, marker);
    var response = new MockHttpServletResponse();

    var proceed =
        interceptor(new PaygateEndpointRegistry()).preHandle(request, response, selectedHandler);
    invokeSelectedHandlerIfAllowed(proceed, selectedHandler);

    assertThat(marker).isNotEqualTo(selectedHandler);
    assertThat(proceed).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(controller.calls).hasValue(1);
  }

  private static HandlerInterceptor interceptor(PaygateEndpointRegistry registry) throws Exception {
    // T046 intentionally remains source-compatible until T054 introduces this final MVC boundary.
    // The runtime lookup becomes an executable contract as soon as the interceptor exists.
    var type = Class.forName("com.greenharborlabs.paygate.spring.PaygateHandlerInterceptor");
    try {
      return (HandlerInterceptor)
          type.getConstructor(PaygateEndpointRegistry.class).newInstance(registry);
    } catch (InvocationTargetException e) {
      throw new AssertionError(
          "Paygate handler interceptor could not be constructed", e.getCause());
    }
  }

  private static HandlerMethod dynamicallyRegisteredHandler(String path, Object controller)
      throws Exception {
    var applicationContext = new StaticApplicationContext();
    applicationContext.refresh();
    var mapping = new RequestMappingHandlerMapping();
    mapping.setApplicationContext(applicationContext);
    mapping.afterPropertiesSet();
    var method = controller.getClass().getMethod("paid");
    mapping.registerMapping(
        RequestMappingInfo.paths(path).methods(RequestMethod.GET).build(), controller, method);

    HandlerExecutionChain executionChain = mapping.getHandler(request(path));
    assertThat(executionChain).isNotNull();
    assertThat(executionChain.getHandler()).isInstanceOf(HandlerMethod.class);
    return (HandlerMethod) executionChain.getHandler();
  }

  private static MockHttpServletRequest request(String path) {
    var request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    return request;
  }

  private static void invokeSelectedHandlerIfAllowed(boolean proceed, HandlerMethod handler)
      throws Exception {
    if (proceed) {
      handler.getMethod().invoke(handler.getBean());
    }
  }

  private static final class PaidController {
    private final AtomicInteger calls = new AtomicInteger();

    @PaymentRequired(priceSats = 1)
    public void paid() {
      calls.incrementAndGet();
    }
  }

  private static final class PolicyAController {
    private final AtomicInteger calls = new AtomicInteger();

    @PaymentRequired(priceSats = 1, description = "policy-a")
    public void paid() {
      calls.incrementAndGet();
    }
  }

  private static final class PolicyBController {
    private final AtomicInteger calls = new AtomicInteger();

    @PaymentRequired(priceSats = 2, description = "policy-b")
    public void paid() {
      calls.incrementAndGet();
    }
  }
}
