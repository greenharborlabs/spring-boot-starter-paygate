package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateSpringSecurityModeCondition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import java.util.List;

/**
 * Auto-configuration that registers Paygate Spring Security components when both
 * Spring Security and an {@link L402Validator} bean are present on the classpath.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link PaygateAuthenticationProvider} — validates L402 credentials via {@link L402Validator}</li>
 *   <li>{@link PaygateAuthenticationFilter} — extracts L402 credentials from the Authorization header
 *       (requires an {@link AuthenticationManager} bean)</li>
 * </ul>
 *
 * <p>Users must register the filter in their security filter chain configuration. This
 * auto-configuration provides the beans; placement in the filter chain is left to the
 * application's {@code SecurityFilterChain} definition.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true")
@ConditionalOnClass({EnableWebSecurity.class, L402Validator.class})
@ConditionalOnBean(L402Validator.class)
@Conditional(PaygateSpringSecurityModeCondition.class)
public class PaygateSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaygateAuthenticationProvider.class)
    public PaygateAuthenticationProvider paygateAuthenticationProvider(
            L402Validator l402Validator,
            List<PaymentProtocol> protocols,
            @Value("${paygate.service-name:default}") String serviceName) {
        return new PaygateAuthenticationProvider(l402Validator, protocols, serviceName);
    }

    @Bean
    @ConditionalOnMissingBean(PaygateAuthenticationFilter.class)
    @ConditionalOnBean(AuthenticationManager.class)
    public PaygateAuthenticationFilter paygateAuthenticationFilter(
            AuthenticationManager authenticationManager,
            List<PaymentProtocol> protocols,
            PaygateEndpointRegistry paygateEndpointRegistry,
            @Autowired(required = false) ClientIpResolver clientIpResolver,
            @Value("${paygate.service-name:default}") String serviceName) {
        return new PaygateAuthenticationFilter(authenticationManager, protocols, paygateEndpointRegistry, clientIpResolver, serviceName);
    }

    @Bean
    @ConditionalOnMissingBean(PaygateAuthenticationEntryPoint.class)
    public PaygateAuthenticationEntryPoint paygateAuthenticationEntryPoint(
            PaygateChallengeService paygateChallengeService,
            PaygateEndpointRegistry paygateEndpointRegistry,
            List<PaymentProtocol> protocols) {
        return new PaygateAuthenticationEntryPoint(paygateChallengeService, paygateEndpointRegistry, protocols);
    }
}
