package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Final MVC enforcement boundary for payment-protected controller methods.
 *
 * <p>The servlet filter authorizes a policy before MVC selects a handler. This interceptor closes
 * that gap by requiring the selected paid handler to be the handler marked by that successful
 * authorization.
 */
public final class PaygateHandlerInterceptor implements HandlerInterceptor {

  private static final String SUCCESSFUL_PAID_HANDLER_ATTRIBUTE =
      "com.greenharborlabs.paygate.spring.PaygateSecurityFilter.successfulPaidHandler";

  private final PaygateEndpointRegistry registry;

  public PaygateHandlerInterceptor(PaygateEndpointRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!(handler instanceof HandlerMethod handlerMethod) || !isPaid(request, handlerMethod)) {
      return true;
    }

    Object successfulHandler = request.getAttribute(SUCCESSFUL_PAID_HANDLER_ATTRIBUTE);
    if (handlerMethod.equals(successfulHandler)) {
      return true;
    }

    PaygateResponseWriter.writeInternalError(response);
    return false;
  }

  private boolean isPaid(HttpServletRequest request, HandlerMethod handlerMethod) {
    if (handlerMethod.getMethodAnnotation(PaymentRequired.class) != null) {
      return true;
    }
    try {
      var endpoint = registry.resolve(request);
      return endpoint != null && handlerMethod.equals(endpoint.handlerMethod());
    } catch (RuntimeException _) {
      // A mapping resolution failure at the final boundary is paid/unsafe until proven otherwise.
      return true;
    }
  }
}
