package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.protocol.L402Validator;
import com.greenharborlabs.l402.spring.L402ChallengeService;
import com.greenharborlabs.l402.spring.L402EndpointRegistry;
import com.greenharborlabs.l402.spring.L402SpringSecurityModeCondition;
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

/**
 * Auto-configuration that registers L402 Spring Security components when both
 * Spring Security and an {@link L402Validator} bean are present on the classpath.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link L402AuthenticationProvider} — validates L402 credentials via {@link L402Validator}</li>
 *   <li>{@link L402AuthenticationFilter} — extracts L402 credentials from the Authorization header
 *       (requires an {@link AuthenticationManager} bean)</li>
 * </ul>
 *
 * <p>Users must register the filter in their security filter chain configuration. This
 * auto-configuration provides the beans; placement in the filter chain is left to the
 * application's {@code SecurityFilterChain} definition.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "l402.enabled", havingValue = "true")
@ConditionalOnClass({EnableWebSecurity.class, L402Validator.class})
@ConditionalOnBean(L402Validator.class)
@Conditional(L402SpringSecurityModeCondition.class)
public class L402SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(L402AuthenticationProvider.class)
    public L402AuthenticationProvider l402AuthenticationProvider(
            L402Validator l402Validator,
            @Value("${l402.service-name:default}") String serviceName) {
        return new L402AuthenticationProvider(l402Validator, serviceName);
    }

    @Bean
    @ConditionalOnMissingBean(L402AuthenticationFilter.class)
    @ConditionalOnBean(AuthenticationManager.class)
    public L402AuthenticationFilter l402AuthenticationFilter(AuthenticationManager authenticationManager,
                                                                L402EndpointRegistry l402EndpointRegistry) {
        return new L402AuthenticationFilter(authenticationManager, l402EndpointRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(L402AuthenticationEntryPoint.class)
    public L402AuthenticationEntryPoint l402AuthenticationEntryPoint(
            L402ChallengeService l402ChallengeService,
            L402EndpointRegistry l402EndpointRegistry) {
        return new L402AuthenticationEntryPoint(l402ChallengeService, l402EndpointRegistry);
    }
}
