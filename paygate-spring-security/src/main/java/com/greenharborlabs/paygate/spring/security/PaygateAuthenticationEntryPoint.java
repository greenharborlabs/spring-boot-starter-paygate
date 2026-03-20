package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.spring.PaygateChallengeResult;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateLightningUnavailableException;
import com.greenharborlabs.paygate.spring.PaygatePathUtils;
import com.greenharborlabs.paygate.spring.PaygateRateLimitedException;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Objects;

/**
 * Spring Security {@link AuthenticationEntryPoint} that issues HTTP 402 Payment Required
 * challenges with Lightning invoices when an unauthenticated request hits a protected endpoint.
 *
 * <p>Follows the pattern of {@code BearerTokenAuthenticationEntryPoint} from Spring OAuth2
 * Resource Server. Produces response bodies byte-identical to {@code PaygateSecurityFilter}.
 */
public final class PaygateAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final System.Logger log = System.getLogger(PaygateAuthenticationEntryPoint.class.getName());

    private final PaygateChallengeService challengeService;
    private final PaygateEndpointRegistry endpointRegistry;

    public PaygateAuthenticationEntryPoint(PaygateChallengeService challengeService,
                                         PaygateEndpointRegistry endpointRegistry) {
        this.challengeService = Objects.requireNonNull(challengeService, "challengeService must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    }

    @Override
    public void commence(HttpServletRequest request,
                          HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        try {
            String method = request.getMethod();
            String path;
            try {
                path = normalizePath(request.getRequestURI());
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "Rejected request with malformed URI: {0}",
                        sanitizeForLog(request.getRequestURI()));
                PaygateResponseWriter.writeLightningUnavailable(response);
                return;
            }

            PaygateEndpointConfig config = endpointRegistry.findConfig(method, path);
            if (config == null) {
                PaygateResponseWriter.writeUnauthorized(response);
                return;
            }

            PaygateChallengeResult result = challengeService.createChallenge(request, config);
            PaygateResponseWriter.writePaymentRequired(response, result);

        } catch (PaygateRateLimitedException _) {
            PaygateResponseWriter.writeRateLimited(response);
        } catch (PaygateLightningUnavailableException e) {
            log.log(System.Logger.Level.WARNING, "Lightning unavailable during entry point challenge: {0}", e.getMessage());
            PaygateResponseWriter.writeLightningUnavailable(response);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Unexpected error in L402 entry point: {0}", e.getMessage());
            PaygateResponseWriter.writeLightningUnavailable(response);
        }
    }

    /**
     * Delegates to {@link PaygatePathUtils#normalizePath(String)}.
     */
    static String normalizePath(String rawPath) {
        return PaygatePathUtils.normalizePath(rawPath);
    }

    /**
     * Strips newlines and control characters from user input to prevent log injection.
     */
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
