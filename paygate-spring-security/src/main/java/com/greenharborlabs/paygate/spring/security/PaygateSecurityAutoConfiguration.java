package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.spring.CapabilityCache;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateRateLimiter;
import com.greenharborlabs.paygate.spring.PaygateSpringSecurityModeCondition;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Auto-configuration that registers Paygate Spring Security components when both Spring Security
 * and an {@link L402Validator} bean are present on the classpath.
 *
 * <p>Registers:
 *
 * <ul>
 *   <li>{@link PaygateAuthenticationProvider} — validates L402 credentials via {@link
 *       L402Validator}
 *   <li>{@link PaygateAuthenticationFilter} — extracts L402 credentials from the Authorization
 *       header (requires an {@link AuthenticationManager} bean)
 * </ul>
 *
 * <p>Users must register the filter in their security filter chain configuration. This
 * auto-configuration provides the beans; placement in the filter chain is left to the application's
 * {@code SecurityFilterChain} definition. {@link
 * PaygateSpringSecurityFilterChainGuardAutoConfiguration} verifies that placement after singleton
 * initialization and is intentionally not gated on {@link L402Validator} bean creation.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true")
@ConditionalOnClass({EnableWebSecurity.class, L402Validator.class})
@ConditionalOnBean(L402Validator.class)
@Conditional(PaygateSpringSecurityModeCondition.class)
public class PaygateSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(CapabilityResolver.class)
  public CapabilityResolver defaultCapabilityResolver(
      @Autowired(required = false) CapabilityCache capabilityCache) {
    return new DefaultCapabilityResolver(capabilityCache);
  }

  @Bean
  @ConditionalOnMissingBean(PaygateAuthenticationProvider.class)
  public PaygateAuthenticationProvider paygateAuthenticationProvider(
      L402Validator l402Validator,
      List<PaymentProtocol> protocols,
      @Value("${paygate.service-name:default}") String serviceName,
      CapabilityResolver capabilityResolver) {
    return new PaygateAuthenticationProvider(
        l402Validator, protocols, serviceName, capabilityResolver);
  }

  @Bean
  @ConditionalOnMissingBean(PaygateAuthenticationFilter.class)
  @ConditionalOnBean(AuthenticationManager.class)
  public PaygateAuthenticationFilter paygateAuthenticationFilter(
      AuthenticationManager authenticationManager,
      List<PaymentProtocol> protocols,
      PaygateEndpointRegistry paygateEndpointRegistry,
      @Autowired(required = false) ClientIpResolver clientIpResolver,
      @Value("${paygate.service-name:default}") String serviceName,
      PaygateAuthenticationEntryPoint paygateAuthenticationEntryPoint) {
    return new PaygateAuthenticationFilter(
        authenticationManager,
        protocols,
        paygateEndpointRegistry,
        clientIpResolver,
        serviceName,
        paygateAuthenticationEntryPoint);
  }

  /**
   * Keeps Spring Boot from also registering the security-chain filter with the servlet container.
   * The application must place this filter in its {@code SecurityFilterChain}; registering it in
   * both locations would execute payment authentication twice.
   */
  @Bean
  @ConditionalOnBean(PaygateAuthenticationFilter.class)
  public FilterRegistrationBean<PaygateAuthenticationFilter>
      paygateAuthenticationFilterDisabledRegistration(
          PaygateAuthenticationFilter paygateAuthenticationFilter) {
    var registration = new FilterRegistrationBean<>(paygateAuthenticationFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  @ConditionalOnMissingBean(PaygateAuthFailureRateLimitFilter.class)
  @ConditionalOnBean(PaygateRateLimiter.class)
  public PaygateAuthFailureRateLimitFilter paygateAuthFailureRateLimitFilter(
      PaygateRateLimiter rateLimiter,
      @Autowired(required = false) ClientIpResolver clientIpResolver,
      PaygateEndpointRegistry paygateEndpointRegistry,
      List<PaymentProtocol> protocols) {
    return new PaygateAuthFailureRateLimitFilter(
        rateLimiter, clientIpResolver, paygateEndpointRegistry, protocols);
  }

  @Bean
  @ConditionalOnMissingBean(PaygateAuthenticationEntryPoint.class)
  public PaygateAuthenticationEntryPoint paygateAuthenticationEntryPoint(
      PaygateChallengeService paygateChallengeService,
      PaygateEndpointRegistry paygateEndpointRegistry,
      List<PaymentProtocol> protocols) {
    return new PaygateAuthenticationEntryPoint(
        paygateChallengeService, paygateEndpointRegistry, protocols);
  }
}
