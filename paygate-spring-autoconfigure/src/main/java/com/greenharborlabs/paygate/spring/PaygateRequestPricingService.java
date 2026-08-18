package com.greenharborlabs.paygate.spring;

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

  /** Request attribute containing the memoized {@link TrustedRequestPrice}. */
  public static final String REQUEST_PRICE_ATTRIBUTE =
      PaygateRequestPricingService.class.getName() + ".TRUSTED_REQUEST_PRICE";

  private final ApplicationContext applicationContext;
  private final Duration timeout;
  private final Semaphore admissions;
  private final ExecutorService executor;

  /**
   * Creates the service using bounded pricing settings.
   *
   * @param applicationContext application context used to resolve named strategy beans
   * @param timeout maximum duration for one named strategy evaluation
   * @param maxConcurrentEvaluations maximum concurrently admitted evaluations
   */
  public PaygateRequestPricingService(
      ApplicationContext applicationContext, Duration timeout, int maxConcurrentEvaluations) {
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
    Object existing = request.getAttribute(REQUEST_PRICE_ATTRIBUTE);
    if (existing instanceof TrustedRequestPrice price) {
      return price;
    }

    TrustedRequestPrice resolved =
        endpoint.pricingStrategy() == null || endpoint.pricingStrategy().isBlank()
            ? new TrustedRequestPrice(endpoint.priceSats())
            : evaluateNamedStrategy(request, endpoint);
    request.setAttribute(REQUEST_PRICE_ATTRIBUTE, resolved);
    return resolved;
  }

  private TrustedRequestPrice evaluateNamedStrategy(
      HttpServletRequest request, PaygateEndpointConfig endpoint) {
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
