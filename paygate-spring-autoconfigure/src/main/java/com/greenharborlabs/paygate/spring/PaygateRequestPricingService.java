package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.SecurityDecision;
import com.greenharborlabs.paygate.api.SecurityDecisionObserver;
import com.greenharborlabs.paygate.api.SecurityDecisionProtocol;
import com.greenharborlabs.paygate.api.SecurityDecisionReason;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.context.ApplicationContext;

/**
 * Resolves one bounded, trusted price for each protected request.
 *
 * <p>A named strategy runs in a virtual thread subject to zero-queue concurrency admission and a
 * timeout. The immutable result is memoized on the request so validation and challenge creation use
 * exactly the same price. Any missing strategy, failure, timeout, saturation, or invalid result
 * fails closed with {@link PricingEvaluationException}.
 */
public final class PaygateRequestPricingService implements AutoCloseable {

  /**
   * Request attribute containing the memoized {@link TrustedRequestPrice} together with the
   * endpoint policy it was resolved for. Read and write it only through {@link
   * #memoizedPrice(HttpServletRequest, PaygateEndpointConfig)} and {@link
   * #memoize(HttpServletRequest, PaygateEndpointConfig, TrustedRequestPrice)}.
   */
  public static final String REQUEST_PRICE_ATTRIBUTE =
      PaygateRequestPricingService.class.getName() + ".TRUSTED_REQUEST_PRICE";

  private final ApplicationContext applicationContext;
  private final Duration timeout;
  private final Semaphore admissions;
  private final ExecutorService executor;
  private final SecurityDecisionObserver decisionObserver;

  /**
   * Creates the service using bounded pricing settings.
   *
   * @param applicationContext application context used to resolve named strategy beans
   * @param timeout maximum duration for one named strategy evaluation
   * @param maxConcurrentEvaluations maximum concurrently admitted evaluations
   */
  public PaygateRequestPricingService(
      ApplicationContext applicationContext, Duration timeout, int maxConcurrentEvaluations) {
    this(applicationContext, timeout, maxConcurrentEvaluations, SecurityDecisionObserver.NOOP);
  }

  /**
   * Creates the service using bounded pricing settings and a best-effort decision observer.
   *
   * @param applicationContext application context used to resolve named strategy beans
   * @param timeout maximum duration for one named strategy evaluation
   * @param maxConcurrentEvaluations maximum concurrently admitted evaluations
   * @param decisionObserver optional observer for sanitized security decisions
   */
  public PaygateRequestPricingService(
      ApplicationContext applicationContext,
      Duration timeout,
      int maxConcurrentEvaluations,
      SecurityDecisionObserver decisionObserver) {
    this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (maxConcurrentEvaluations < 1) {
      throw new IllegalArgumentException("maxConcurrentEvaluations must be positive");
    }
    this.admissions = new Semaphore(maxConcurrentEvaluations);
    this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    this.decisionObserver =
        decisionObserver != null ? decisionObserver : SecurityDecisionObserver.NOOP;
  }

  /**
   * Resolves and memoizes the trusted price for this request and endpoint policy.
   *
   * @param request protected request
   * @param endpoint resolved endpoint policy
   * @return one validated, memoized request price
   */
  public TrustedRequestPrice resolve(HttpServletRequest request, PaygateEndpointConfig endpoint) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(endpoint, "endpoint");
    TrustedRequestPrice existing = memoizedPrice(request, endpoint);
    if (existing != null) {
      return existing;
    }

    TrustedRequestPrice resolved =
        endpoint.pricingStrategy() == null || endpoint.pricingStrategy().isBlank()
            ? new TrustedRequestPrice(endpoint.priceSats())
            : evaluateNamedStrategy(request, endpoint);
    memoize(request, endpoint, resolved);
    return resolved;
  }

  /**
   * Returns the price memoized on this request for the given endpoint policy.
   *
   * <p>A FORWARD, ASYNC or ERROR redispatch shares the request attributes of the original dispatch.
   * A price is reused only when it was resolved for an equal endpoint policy, so a redispatch to a
   * different paid endpoint resolves its own price.
   *
   * @param request protected request
   * @param endpoint endpoint policy the price must belong to
   * @return the memoized price, or {@code null} when none was resolved for this endpoint
   */
  public static TrustedRequestPrice memoizedPrice(
      HttpServletRequest request, PaygateEndpointConfig endpoint) {
    Objects.requireNonNull(endpoint, "endpoint");
    return request.getAttribute(REQUEST_PRICE_ATTRIBUTE)
                instanceof
                EndpointPrice(PaygateEndpointConfig resolvedFor, TrustedRequestPrice price)
            && endpoint.equals(resolvedFor)
        ? price
        : null;
  }

  /**
   * Memoizes a trusted price on this request, bound to the endpoint policy it was resolved for.
   *
   * @param request protected request
   * @param endpoint endpoint policy the price was resolved for
   * @param price trusted price
   */
  public static void memoize(
      HttpServletRequest request, PaygateEndpointConfig endpoint, TrustedRequestPrice price) {
    request.setAttribute(
        REQUEST_PRICE_ATTRIBUTE,
        new EndpointPrice(
            Objects.requireNonNull(endpoint, "endpoint"), Objects.requireNonNull(price, "price")));
  }

  /** A trusted price and the endpoint policy it was resolved for. */
  private record EndpointPrice(PaygateEndpointConfig endpoint, TrustedRequestPrice price) {}

  private TrustedRequestPrice evaluateNamedStrategy(
      HttpServletRequest request, PaygateEndpointConfig endpoint) {
    if (request instanceof BoundedRequestBody body && !body.hasIdentityContentEncoding()) {
      SecurityDecisionObserver.notifySafely(
          decisionObserver,
          new SecurityDecision(
              SecurityDecisionReason.COMPRESSED_BODY_REJECTED,
              SecurityDecisionProtocol.UNKNOWN,
              request.getMethod().toUpperCase(java.util.Locale.ROOT),
              endpoint.pathPattern()));
      throw new UnsupportedRequestEncodingException();
    }
    final PaygatePricingStrategy strategy;
    try {
      strategy =
          applicationContext.getBean(endpoint.pricingStrategy(), PaygatePricingStrategy.class);
    } catch (RuntimeException failure) {
      throw new PricingEvaluationException("Configured pricing strategy is unavailable", failure);
    }
    if (!admissions.tryAcquire()) {
      throw new PricingEvaluationException("Pricing evaluation capacity is exhausted");
    }

    Future<Long> future;
    try {
      future =
          executor.submit(
              () -> {
                try {
                  return strategy.calculatePrice(request, endpoint.priceSats());
                } finally {
                  admissions.release();
                }
              });
    } catch (RuntimeException failure) {
      admissions.release();
      throw new PricingEvaluationException("Pricing evaluation could not start", failure);
    }

    try {
      return new TrustedRequestPrice(future.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
    } catch (TimeoutException failure) {
      future.cancel(true);
      throw new PricingEvaluationException("Pricing evaluation timed out", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new PricingEvaluationException("Pricing evaluation was interrupted", failure);
    } catch (ExecutionException failure) {
      throw new PricingEvaluationException("Pricing evaluation failed", failure.getCause());
    }
  }

  /**
   * Shuts down the virtual-thread executor when the service is replaced or the application stops.
   */
  @Override
  public void close() {
    executor.close();
  }
}
