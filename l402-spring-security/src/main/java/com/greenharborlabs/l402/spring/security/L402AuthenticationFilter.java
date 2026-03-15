package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.spring.L402EndpointConfig;
import com.greenharborlabs.l402.spring.L402EndpointRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Logger log = LoggerFactory.getLogger(L402AuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final Pattern L402_PATTERN =
            Pattern.compile("(?:LSAT|L402) ([A-Za-z0-9+/=,]{1,8192}):([a-fA-F0-9]{64})");

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
        if (authHeader == null || authHeader.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        Matcher matcher = L402_PATTERN.matcher(authHeader);
        if (!matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        String macaroonBase64 = matcher.group(1);
        String preimageHex = matcher.group(2);

        String capability = resolveCapability(request);
        var unauthenticatedToken = new L402AuthenticationToken(macaroonBase64, preimageHex, capability);

        try {
            Authentication authenticated = authenticationManager.authenticate(unauthenticatedToken);
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authenticated);
            SecurityContextHolder.setContext(securityContext);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            response.setHeader("WWW-Authenticate", "L402");
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\": \"L402 authentication failed\"}");
            return;
        } catch (RuntimeException e) {
            log.warn("L402 authentication encountered an unexpected error", e);
            SecurityContextHolder.clearContext();
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("{\"error\": \"Service temporarily unavailable\"}");
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
            L402EndpointConfig config = endpointRegistry.findConfig(
                    request.getMethod(), request.getRequestURI());
            if (config == null) {
                return null;
            }
            String capability = config.capability();
            if (capability == null || capability.isBlank()) {
                return null;
            }
            return capability;
        } catch (RuntimeException e) {
            log.debug("Failed to resolve capability for {} {}; proceeding without capability enforcement",
                    request.getMethod(), request.getRequestURI(), e);
            return null;
        }
    }
}
