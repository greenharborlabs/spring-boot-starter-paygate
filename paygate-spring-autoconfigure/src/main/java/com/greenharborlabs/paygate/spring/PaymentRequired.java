package com.greenharborlabs.paygate.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring payment before access is granted.
 *
 * <p>This is the protocol-neutral equivalent of {@link PaygateProtected}.
 * When applied, requests to the annotated endpoint must include a valid
 * payment credential in the {@code Authorization} header or a 402 Payment Required
 * response will be returned with an invoice for the specified price.
 *
 * <p>If both {@code @PaymentRequired} and {@code @PaygateProtected} are present
 * on the same method, {@code @PaymentRequired} takes precedence and a warning
 * is logged.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PaymentRequired {

    /**
     * Price in satoshis required to access this endpoint.
     */
    long priceSats();

    /**
     * Credential timeout in seconds. A value of {@code -1} indicates the default
     * timeout from {@link PaygateProperties} should be used.
     */
    long timeoutSeconds() default -1;

    /**
     * Human-readable description of the protected resource.
     */
    String description() default "";

    /**
     * Name of the pricing strategy bean to use. An empty string indicates
     * the default (fixed price from {@link #priceSats()}).
     */
    String pricingStrategy() default "";

    /**
     * Capability required for this endpoint. An empty string indicates
     * no specific capability is required.
     */
    String capability() default "";
}
