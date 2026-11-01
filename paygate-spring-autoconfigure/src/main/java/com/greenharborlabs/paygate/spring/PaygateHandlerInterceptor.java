package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Final MVC enforcement boundary for payment-protected controller methods.
 *
 * <p>The servlet filter authorizes a policy before MVC selects a handler. This interceptor closes
 * that gap by requiring the selected paid handler to be the handler marked by that successful
 * authorization.
 *
 * <p>For handlers that are not MVC handler methods, such as {@code RouterFunction} or URL-mapped
 * handlers, a manually registered paid route that matches the raw dispatch path requires a
 * successful decision for that route. Otherwise the request fails closed with 500.
 */
public final class PaygateHandlerInterceptor implements HandlerInterceptor {

  private static final String SUCCESSFUL_PAID_HANDLER_ATTRIBUTE =
      "com.greenharborlabs.paygate.spring.PaygateSecurityFilter.successfulPaidHandler";
  private static final String SUCCESSFUL_POLICY_DECISION_ATTRIBUTE =
      "com.greenharborlabs.paygate.spring.PaygateSecurityFilter.successfulPolicyDecision";

  private final PaygateEndpointRegistry registry;

  public PaygateHandlerInterceptor(PaygateEndpointRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return preHandleNonMethodHandler(request, response);
    }

    ResolvedEndpoint endpoint;
    try {
      endpoint = resolveSelectedEndpoint(request, handlerMethod);
    } catch (RuntimeException _) {
      // The final enforcement boundary cannot prove this mapping is safe.
      PaygateResponseWriter.writeInternalError(response);
      return false;
    }
    if (endpoint == null && handlerMethod.getMethodAnnotation(PaymentRequired.class) == null) {
      return true;
    }

    Object successfulHandler = request.getAttribute(SUCCESSFUL_PAID_HANDLER_ATTRIBUTE);
    if (successfulHandler instanceof HandlerMethod markedHandler
        && identifiesSameHandler(handlerMethod, markedHandler)
        && (endpoint == null
            || endpoint.equals(request.getAttribute(SUCCESSFUL_POLICY_DECISION_ATTRIBUTE)))) {
      // A handler mapping several methods has one policy per method. The authorized policy must be
      // the one for the method MVC dispatches, not merely the same handler.
      return true;
    }

    PaygateResponseWriter.writeInternalError(response);
    return false;
  }

  /**
   * Final enforcement for handlers that are not MVC handler methods, such as {@code RouterFunction}
   * or URL-mapped handlers. Manual routes are matched against the raw dispatch path, independently
   * of the filter's canonical path. A paid match without a successful Paygate decision for that
   * same policy fails closed.
   */
  private boolean preHandleNonMethodHandler(
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    ResolvedEndpoint paidRoute;
    try {
      paidRoute = registry.resolveManualRoute(request);
    } catch (RuntimeException _) {
      PaygateResponseWriter.writeInternalError(response);
      return false;
    }
    if (paidRoute == null
        || paidRoute.equals(request.getAttribute(SUCCESSFUL_POLICY_DECISION_ATTRIBUTE))) {
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

  private ResolvedEndpoint resolveSelectedEndpoint(
      HttpServletRequest request, HandlerMethod handlerMethod) {
    var endpoint = registry.resolve(request);
    if (endpoint != null) {
      var registeredHandler = endpoint.handlerMethod();
      if (registeredHandler == null || !identifiesSameHandler(handlerMethod, registeredHandler)) {
        throw new IllegalStateException("Registry mapping does not identify the selected handler");
      }
    }
    return endpoint;
  }
}
