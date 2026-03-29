package com.greenharborlabs.paygate.example.security;

import com.greenharborlabs.paygate.spring.security.PaygateAuthFailureRateLimitFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationEntryPoint;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Demonstrates Spring Security integration with Paygate payment authentication.
 *
 * <p>This configuration shows how to use Paygate's Spring Security components to protect
 * endpoints with payment-based authorization. Key concepts:
 *
 * <ul>
 *   <li>{@code hasRole("PAYMENT")} accepts credentials from any payment protocol (both L402
 *       and MPP). This role is granted to all authenticated payment tokens regardless of
 *       which protocol was used.</li>
 *   <li>{@code hasRole("L402")} restricts access to L402 credentials only. MPP credentials
 *       will be rejected with a 403 Forbidden because they are not granted the {@code ROLE_L402}
 *       authority.</li>
 *   <li>{@code @EnableMethodSecurity} enables the use of {@code @PreAuthorize} annotations on
 *       controller methods for fine-grained, capability-based authorization. The recommended
 *       authority prefix is {@code PAYGATE_CAPABILITY_*} (e.g.,
 *       {@code @PreAuthorize("hasAuthority('PAYGATE_CAPABILITY_premium-analyze')")}). The legacy
 *       {@code L402_CAPABILITY_*} prefix is still emitted for backward compatibility, so existing
 *       {@code @PreAuthorize} expressions using it will continue to work.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Exposes the {@link AuthenticationManager} as a bean so that Paygate's auto-configuration
     * can create the {@link PaygateAuthenticationFilter}. Spring Security does not register
     * the {@code AuthenticationManager} as a bean by default — it is only accessible via
     * {@link AuthenticationConfiguration#getAuthenticationManager()}.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PaygateAuthenticationFilter paygateFilter,
            PaygateAuthenticationProvider paygateProvider,
            PaygateAuthenticationEntryPoint paygateEntryPoint,
            ObjectProvider<PaygateAuthFailureRateLimitFilter> rateLimitFilterProvider) throws Exception {

        PaygateAuthFailureRateLimitFilter rateLimitFilter = rateLimitFilterProvider.getIfAvailable();
        if (rateLimitFilter != null) {
            http.addFilterBefore(rateLimitFilter, BasicAuthenticationFilter.class);
        }

        return http
                .authenticationProvider(paygateProvider)
                .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Health endpoint is public
                        .requestMatchers("/api/v1/health").permitAll()

                        // L402-only endpoint: requires L402 protocol specifically
                        .requestMatchers("/api/v1/l402-only").hasRole("L402")

                        // All other /api/** endpoints accept any payment protocol
                        .requestMatchers("/api/**").hasRole("PAYMENT")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(paygateEntryPoint)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
