package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Captures the bounded body of a paid request before any other servlet filter can consume it.
 *
 * <p>In Spring Security mode, Paygate authenticates inside the {@code springSecurityFilterChain},
 * which runs after Spring's {@code FormContentFilter}, {@code HiddenHttpMethodFilter}, and any
 * {@code CsrfFilter} that reads form parameters. Those components drain an {@code
 * application/x-www-form-urlencoded} body through {@code getParameter}, so a later capture would
 * price and bind an empty body. This filter is registered at the same early order as the
 * servlet-mode {@link PaygateSecurityFilter} and, for a request that resolves to a paid route whose
 * body Paygate needs (a named pricing strategy, or any route while MPP is enabled), it captures the
 * body with {@link BoundedRequestBody}, enforces {@code paygate.request-body.max-bytes}, and passes
 * the replaying wrapper downstream. Later Paygate captures of the same request reuse these bytes.
 *
 * <p>It performs no authentication. Requests that do not resolve to a paid route, and requests
 * whose path cannot be resolved, pass through unchanged so the security chain's own handling
 * applies.
 */
public final class PaygateRequestBodyCaptureFilter implements Filter {

  private static final System.Logger log =
      System.getLogger(PaygateRequestBodyCaptureFilter.class.getName());

  private final PaygateEndpointRegistry registry;
  private final boolean mppEnabled;
  private final int requestBodyMaxBytes;

  /**
   * Creates the capture filter.
   *
   * @param registry paid endpoint registry used to resolve the request's policy
   * @param protocols enabled payment protocols; MPP requires the body for its request digest
   * @param requestBodyMaxBytes configured inclusive body bound
   */
  public PaygateRequestBodyCaptureFilter(
      PaygateEndpointRegistry registry, List<PaymentProtocol> protocols, int requestBodyMaxBytes) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.mppEnabled =
        protocols != null && protocols.stream().anyMatch(RequestDigestSupport::isMppProtocol);
    if (requestBodyMaxBytes < 1) {
      throw new IllegalArgumentException("requestBodyMaxBytes must be positive");
    }
    this.requestBodyMaxBytes = requestBodyMaxBytes;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)
        || !(response instanceof HttpServletResponse httpResponse)) {
      chain.doFilter(request, response);
      return;
    }

    ResolvedEndpoint endpoint;
    try {
      endpoint = registry.resolve(httpRequest);
    } catch (RuntimeException e) {
      // Malformed or ambiguous paths and policy-resolution failures are answered by the Paygate
      // authentication filter in the security chain, which resolves the same request again.
      chain.doFilter(request, response);
      return;
    }
    if (endpoint == null || !needsBody(endpoint.config())) {
      chain.doFilter(request, response);
      return;
    }

    BoundedRequestBody captured;
    try {
      captured = BoundedRequestBody.capture(httpRequest, requestBodyMaxBytes);
    } catch (RequestBodyTooLargeException e) {
      PaygateResponseWriter.writeRequestBodyTooLarge(httpResponse);
      return;
    } catch (RequestBodyIncompleteException e) {
      PaygateResponseWriter.writeRequestBodyIncomplete(httpResponse);
      return;
    } catch (IOException e) {
      log.log(System.Logger.Level.WARNING, "Request body observation failed; failing closed");
      PaygateResponseWriter.writeLightningUnavailable(httpResponse);
      return;
    }
    chain.doFilter(captured, response);
  }

  private boolean needsBody(PaygateEndpointConfig config) {
    return mppEnabled || (config.pricingStrategy() != null && !config.pricingStrategy().isBlank());
  }
}
