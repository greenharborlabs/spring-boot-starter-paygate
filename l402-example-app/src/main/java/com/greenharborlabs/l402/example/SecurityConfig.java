package com.greenharborlabs.l402.example;

/*
 * =============================================================================
 * SPRING SECURITY INTEGRATION EXAMPLE
 * =============================================================================
 *
 * This file demonstrates how to compose L402 authentication with Spring Security.
 * It is commented out because the example app does not include the
 * l402-spring-security dependency by default.
 *
 * WHEN TO USE THIS:
 *   Use the Spring Security integration when your application already uses
 *   Spring Security (OAuth2, JWT, form login, etc.) and you want L402 to
 *   participate in the security filter chain alongside those mechanisms.
 *
 *   If you only need L402 protection and do not use Spring Security, the
 *   default servlet filter mode with @L402Protected annotations is simpler
 *   and requires no SecurityFilterChain configuration.
 *
 * TO ENABLE:
 *   1. Add the spring-security dependencies to build.gradle.kts:
 *
 *        implementation(project(":l402-spring-security"))
 *        implementation("org.springframework.boot:spring-boot-starter-security")
 *
 *   2. Set the security mode in application.yml:
 *
 *        l402:
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

import com.greenharborlabs.l402.spring.security.L402AuthenticationEntryPoint;
import com.greenharborlabs.l402.spring.security.L402AuthenticationFilter;
import com.greenharborlabs.l402.spring.security.L402AuthenticationProvider;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     L402AuthenticationFilter l402Filter,
                                                     L402AuthenticationProvider l402Provider,
                                                     L402AuthenticationEntryPoint l402EntryPoint) throws Exception {
        return http
                // Register the L402 authentication provider
                .authenticationProvider(l402Provider)

                // Place the L402 filter before BasicAuthenticationFilter so L402
                // credentials are evaluated first
                .addFilterBefore(l402Filter, BasicAuthenticationFilter.class)

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

                // Use the L402 entry point to issue 402 challenges with
                // Lightning invoices when unauthenticated requests arrive
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(l402EntryPoint)
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
    // When endpoints are annotated with @L402Protected(capability = "analyze"),
    // the minted macaroon includes a {serviceName}_capabilities caveat, and
    // the authenticated L402AuthenticationToken carries L402_CAPABILITY_*
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
    // If no capability is configured on the endpoint (@L402Protected without
    // capability, or capability = ""), the token has only ROLE_L402 and
    // capability checks are permissive (backward-compatible).
}

*/
