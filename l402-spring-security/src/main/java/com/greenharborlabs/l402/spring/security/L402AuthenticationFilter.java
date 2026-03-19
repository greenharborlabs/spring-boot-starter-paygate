package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.protocol.L402HeaderComponents;
import com.greenharborlabs.l402.spring.L402EndpointConfig;
import com.greenharborlabs.l402.spring.L402EndpointRegistry;
import com.greenharborlabs.l402.spring.L402PathUtils;
import com.greenharborlabs.l402.spring.L402ResponseWriter;
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
 * is populated with an authenticated {@link L402AuthenticationToken}.
 *
 * <p>If the header is absent or does not match the L402/LSAT pattern, the filter chain
 * continues without setting authentication, allowing other filters to handle the request.
 */
public final class L402AuthenticationFilter extends OncePerRequestFilter {

    private static final System.Logger log = System.getLogger(L402AuthenticationFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final AuthenticationManager authenticationManager;
    private final L402EndpointRegistry endpointRegistry;

    public L402AuthenticationFilter(AuthenticationManager authenticationManager,
                                     L402EndpointRegistry endpointRegistry) {
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
        var unauthenticatedToken = new L402AuthenticationToken(components, capability);

        try {
            Authentication authenticated = authenticationManager.authenticate(unauthenticatedToken);
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authenticated);
            SecurityContextHolder.setContext(securityContext);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            L402ResponseWriter.writeAuthenticationFailed(response);
            return;
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "L402 authentication encountered an unexpected error", e);
            SecurityContextHolder.clearContext();
            L402ResponseWriter.writeLightningUnavailable(response);
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
            String path = L402PathUtils.normalizePath(request.getRequestURI());
            L402EndpointConfig config = endpointRegistry.findConfig(
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
