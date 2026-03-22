package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Strategy interface for dynamic pricing of payment-protected endpoints.
 *
 * <p>Implementations are looked up as Spring beans by name, as specified in
 * {@link PaymentRequired#pricingStrategy()}. When a matching bean is found,
 * the strategy determines the invoice price instead of the static
 * {@code priceSats} annotation value.
 */
@FunctionalInterface
public interface PaygatePricingStrategy {

    /**
     * Determines the price in satoshis for the given request.
     *
     * @param request      the current HTTP request
     * @param defaultPrice the static price from the {@code @PaymentRequired} annotation
     * @return the price in satoshis to use for the invoice
     */
    long calculatePrice(HttpServletRequest request, long defaultPrice);
}
