package com.greenharborlabs.paygate.example;

/*
 * =============================================================================
 * SPRING SECURITY INTEGRATION EXAMPLE
 * =============================================================================
 *
 * This file demonstrates how to compose L402 authentication with Spring Security.
 * It is commented out because the example app does not include the
 * paygate-spring-security dependency by default.
 *
 * WHEN TO USE THIS:
 *   Use the Spring Security integration when your application already uses
 *   Spring Security (OAuth2, JWT, form login, etc.) and you want L402 to
 *   participate in the security filter chain alongside those mechanisms.
 *
 *   If you only need L402 protection and do not use Spring Security, the
 *   default servlet filter mode with @PaymentRequired annotations is simpler
 *   and requires no SecurityFilterChain configuration.
 *
 * TO ENABLE:
 *   1. Add the spring-security dependencies to build.gradle.kts:
 *
 *        implementation(project(":paygate-spring-security"))
 *        implementation("org.springframework.boot:spring-boot-starter-security")
 *
 *   2. Set the security mode in application.yml:
 *
 *        paygate:
 *          security-mode: spring-security
 *
 *   3. Uncomment the code below.
 *
 * =============================================================================
 *

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationEntryPoint;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationFilter;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationProvider;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     PaygateAuthenticationFilter paygateFilter,
                                                     PaygateAuthenticationProvider paygateProvider,
                                                     PaygateAuthenticationEntryPoint paygateEntryPoint) throws Exception {
        return http
                // Register the Paygate authentication provider
                .authenticationProvider(paygateProvider)

                // Place the Paygate filter before BasicAuthenticationFilter so L402
                // credentials are evaluated first
                .addFilterBefore(paygateFilter, BasicAuthenticationFilter.class)

                // Configure authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Health endpoint is public
                        .requestMatchers("/api/v1/health").permitAll()

                        // L402-protected endpoints require ROLE_L402
                        .requestMatchers("/api/v1/data").hasRole("L402")
                        .requestMatchers("/api/v1/analyze").hasRole("L402")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // Use the Paygate entry point to issue 402 challenges with
                // Lightning invoices when unauthenticated requests arrive
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(paygateEntryPoint)
                )

                // L402 is stateless -- no sessions needed
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CSRF is not needed for stateless API authentication
                .csrf(csrf -> csrf.disable())

                .build();
    }

    // --- Capability-Based Authorization with @PreAuthorize ---
    //
    // When endpoints are annotated with @PaymentRequired(capability = "analyze"),
    // the minted macaroon includes a {serviceName}_capabilities caveat, and
    // the authenticated PaygateAuthenticationToken carries L402_CAPABILITY_*
    // GrantedAuthority entries. Use @PreAuthorize to enforce them:
    //
    //   @PreAuthorize("hasAuthority('L402_CAPABILITY_analyze')")
    //   @GetMapping("/api/v1/analyze")
    //   public AnalysisResult analyze() { ... }
    //
    //   // Combined: require both L402 authentication AND a specific capability
    //   @PreAuthorize("hasRole('L402') and hasAuthority('L402_CAPABILITY_search')")
    //   @GetMapping("/api/v1/search")
    //   public SearchResult search() { ... }
    //
    // If no capability is configured on the endpoint (@PaymentRequired without
    // capability, or capability = ""), the token has only ROLE_L402 and
    // capability checks are permissive (backward-compatible).
}

*/
