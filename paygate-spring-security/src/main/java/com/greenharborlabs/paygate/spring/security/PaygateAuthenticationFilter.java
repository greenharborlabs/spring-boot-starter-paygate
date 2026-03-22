package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.spring.ClientIpResolver;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spring Security filter that extracts payment credentials from the Authorization header
 * and delegates authentication to the {@link AuthenticationManager}.
 *
 * <p>First attempts to parse L402/LSAT credentials ({@code Authorization: L402 <macaroon>:<preimage>}).
 * If the header does not match L402/LSAT, iterates the registered {@link PaymentProtocol} instances
 * to detect other credential formats (e.g., MPP {@code Payment} scheme).
 *
 * <p>On successful authentication the {@link SecurityContextHolder}
 * is populated with an authenticated {@link PaygateAuthenticationToken}.
 *
 * <p>If the header is absent or does not match any known protocol, the filter chain
 * continues without setting authentication, allowing other filters to handle the request.
 */
public final class PaygateAuthenticationFilter extends OncePerRequestFilter {

    private static final System.Logger log = System.getLogger(PaygateAuthenticationFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final AuthenticationManager authenticationManager;
    private final List<PaymentProtocol> protocols;
    private final PaygateEndpointRegistry endpointRegistry;
    private final ClientIpResolver clientIpResolver;

    public PaygateAuthenticationFilter(AuthenticationManager authenticationManager,
                                     List<PaymentProtocol> protocols,
                                     PaygateEndpointRegistry endpointRegistry) {
        this(authenticationManager, protocols, endpointRegistry, null);
    }

    public PaygateAuthenticationFilter(AuthenticationManager authenticationManager,
                                     List<PaymentProtocol> protocols,
                                     PaygateEndpointRegistry endpointRegistry,
                                     ClientIpResolver clientIpResolver) {
        this.authenticationManager = Objects.requireNonNull(authenticationManager,
                "authenticationManager must not be null");
        this.protocols = protocols != null ? List.copyOf(protocols) : List.of();
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry,
                "endpointRegistry must not be null");
        this.clientIpResolver = clientIpResolver;
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

        String normalizedPath = PaygatePathUtils.normalizePath(request.getRequestURI());

        String capability;
        try {
            capability = resolveCapability(request, normalizedPath);
        } catch (RuntimeException e) {
            SecurityContextHolder.clearContext();
            PaygateResponseWriter.writeLightningUnavailable(response);
            return;
        }

        Map<String, String> requestMetadata = extractRequestMetadata(request, normalizedPath);

        PaygateAuthenticationToken unauthenticatedToken;
        var componentsOpt = L402HeaderComponents.extract(authHeader);
        if (componentsOpt.isPresent()) {
            unauthenticatedToken = new PaygateAuthenticationToken(
                    componentsOpt.get(), capability, requestMetadata);
        } else if (matchesAnyProtocol(authHeader)) {
            unauthenticatedToken = PaygateAuthenticationToken.unauthenticated(
                    authHeader, capability, requestMetadata);
        } else {
            filterChain.doFilter(request, response);
            return;
        }

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
            log.log(System.Logger.Level.WARNING, "Payment authentication encountered an unexpected error", e);
            SecurityContextHolder.clearContext();
            PaygateResponseWriter.writeLightningUnavailable(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesAnyProtocol(String authHeader) {
        for (PaymentProtocol protocol : protocols) {
            if (protocol.canHandle(authHeader)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> extractRequestMetadata(HttpServletRequest request, String normalizedPath) {
        Map<String, String> metadata = new HashMap<>(3);
        metadata.put(VerificationContextKeys.REQUEST_PATH, normalizedPath);
        metadata.put(VerificationContextKeys.REQUEST_METHOD, request.getMethod());
        String clientIp = clientIpResolver != null
                ? clientIpResolver.resolve(request)
                : request.getRemoteAddr();
        metadata.put(VerificationContextKeys.REQUEST_CLIENT_IP, clientIp);
        return metadata;
    }

    /**
     * Resolves the capability for the current request by looking up the endpoint registry.
     * Returns {@code null} (permissive) if no config is found or the capability is blank.
     * Re-throws {@link RuntimeException} to enforce fail-closed behavior.
     */
    private String resolveCapability(HttpServletRequest request, String normalizedPath) {
        try {
            PaygateEndpointConfig config = endpointRegistry.findConfig(
                    request.getMethod(), normalizedPath);
            if (config == null) {
                return null;
            }
            String capability = config.capability();
            if (capability == null || capability.isBlank()) {
                return null;
            }
            return capability;
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING,
                    "Failed to resolve capability for {0} {1}; denying request",
                    request.getMethod(), sanitizeForLog(request.getRequestURI()), e);
            throw e;
        }
    }

    static String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }
        return value.codePoints()
                .filter(cp -> cp >= 0x20 && cp != 0x7F)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
