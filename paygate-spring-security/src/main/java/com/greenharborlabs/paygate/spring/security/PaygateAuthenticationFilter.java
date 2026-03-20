package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygatePathUtils;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * Spring Security filter that extracts L402 credentials from the Authorization header
 * and delegates authentication to the {@link AuthenticationManager}.
 *
 * <p>Parses headers matching {@code Authorization: L402 <macaroon>:<preimage>} (also accepts
 * the legacy {@code LSAT} scheme). On successful authentication the {@link SecurityContextHolder}
 * is populated with an authenticated {@link PaygateAuthenticationToken}.
 *
 * <p>If the header is absent or does not match the L402/LSAT pattern, the filter chain
 * continues without setting authentication, allowing other filters to handle the request.
 */
public final class PaygateAuthenticationFilter extends OncePerRequestFilter {

    private static final System.Logger log = System.getLogger(PaygateAuthenticationFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final AuthenticationManager authenticationManager;
    private final PaygateEndpointRegistry endpointRegistry;

    public PaygateAuthenticationFilter(AuthenticationManager authenticationManager,
                                     PaygateEndpointRegistry endpointRegistry) {
        this.authenticationManager = Objects.requireNonNull(authenticationManager,
                "authenticationManager must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry,
                "endpointRegistry must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        var componentsOpt = L402HeaderComponents.extract(authHeader);
        if (componentsOpt.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        var components = componentsOpt.get();
        String capability = resolveCapability(request);
        var unauthenticatedToken = new PaygateAuthenticationToken(components, capability);

        try {
            Authentication authenticated = authenticationManager.authenticate(unauthenticatedToken);
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authenticated);
            SecurityContextHolder.setContext(securityContext);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            PaygateResponseWriter.writeAuthenticationFailed(response);
            return;
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "L402 authentication encountered an unexpected error", e);
            SecurityContextHolder.clearContext();
            PaygateResponseWriter.writeLightningUnavailable(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolves the capability for the current request by looking up the endpoint registry.
     * Returns {@code null} (permissive) if no config is found, the capability is blank,
     * or any error occurs during lookup.
     */
    private String resolveCapability(HttpServletRequest request) {
        try {
            String path = PaygatePathUtils.normalizePath(request.getRequestURI());
            PaygateEndpointConfig config = endpointRegistry.findConfig(
                    request.getMethod(), path);
            if (config == null) {
                return null;
            }
            String capability = config.capability();
            if (capability == null || capability.isBlank()) {
                return null;
            }
            return capability;
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.DEBUG, "Failed to resolve capability for {0} {1}; proceeding without capability enforcement",
                    request.getMethod(), request.getRequestURI(), e);
            return null;
        }
    }
}
