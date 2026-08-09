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
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }

    boolean paid;
    try {
      paid = isPaid(request, handlerMethod);
    } catch (RuntimeException _) {
      // The final enforcement boundary cannot prove this mapping is safe.
      PaygateResponseWriter.writeInternalError(response);
      return false;
    }
    if (!paid) {
      return true;
    }

    Object successfulHandler = request.getAttribute(SUCCESSFUL_PAID_HANDLER_ATTRIBUTE);
    if (successfulHandler instanceof HandlerMethod markedHandler
        && identifiesSameHandler(handlerMethod, markedHandler)) {
      return true;
    }

    PaygateResponseWriter.writeInternalError(response);
    return false;
  }

  /**
   * Compares the stable MVC handler identity rather than {@link HandlerMethod#equals(Object)}.
   * Spring's mapping catalog can retain a bean name while request dispatch resolves that name to
   * the bean instance, which makes {@code HandlerMethod.equals} false for the same controller
   * method.
   */
  private static boolean identifiesSameHandler(HandlerMethod selected, HandlerMethod marked) {
    return selected.getBeanType().equals(marked.getBeanType())
        && selected.getMethod().equals(marked.getMethod());
  }

  private boolean isPaid(HttpServletRequest request, HandlerMethod handlerMethod) {
    if (handlerMethod.getMethodAnnotation(PaymentRequired.class) != null) {
      return true;
    }
    var endpoint = registry.resolve(request);
    return endpoint != null
        && endpoint.handlerMethod() != null
        && identifiesSameHandler(handlerMethod, endpoint.handlerMethod());
  }
}
